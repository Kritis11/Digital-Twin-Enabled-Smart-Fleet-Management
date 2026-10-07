package com.fleettwin.driving;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fleettwin.alert.Alert;
import com.fleettwin.alert.AlertService;
import com.fleettwin.config.FleetProperties;
import com.fleettwin.driving.DrivingAnalyzer.Event;
import com.fleettwin.driving.DrivingAnalyzer.Output;
import com.fleettwin.driving.DrivingAnalyzer.Reading;
import com.fleettwin.driving.DrivingAnalyzer.Trip;
import com.fleettwin.telemetry.Telemetry;
import com.fleettwin.twin.Status;
import com.fleettwin.twin.TwinService;
import com.fleettwin.vehicle.Vehicle;
import com.fleettwin.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one {@link DrivingAnalyzer} per vehicle over the live telemetry stream and stores what it finds.
 * {@link #reanalyse} replays stored telemetry through fresh analysers, which is how fast-forwarded
 * history gets its trips and events.
 */
// ponytail: analyser state is in memory, so a backend restart ends the trips in progress early;
// persist the state (or reanalyse the last hour on startup) if that ever matters.
@Service
@RequiredArgsConstructor
@Slf4j
public class DrivingService {

    private final FleetProperties props;
    private final DrivingRepository repository;
    private final VehicleRepository vehicles;
    private final AlertService alerts;
    private final TwinService twins;
    private final Map<Long, DrivingAnalyzer> analyzers = new HashMap<>();

    /** Called for every stored telemetry row. */
    public synchronized void onTelemetry(Telemetry t) {
        Reading reading = new Reading(t.getTs(), t.getLat(), t.getLng(), t.getSpeed(), t.getFuelLevel(),
                t.getAccelMin(), t.getAccelMax(), t.getOdometerKm());
        store(t.getVehicleId(), analyzers.computeIfAbsent(t.getVehicleId(), id -> newAnalyzer()).accept(reading), true);
    }

    /** A vehicle that went quiet never sends the reading that would end its trip, so end it here. */
    @Scheduled(fixedDelayString = "${fleet.driving.flush-interval-ms}")
    public synchronized void flushStale() {
        Instant cutoff = Instant.now().minus(props.driving().tripGap());
        analyzers.forEach((vehicleId, analyzer) -> {
            if (analyzer.lastSeen() != null && analyzer.lastSeen().isBefore(cutoff)) {
                store(vehicleId, analyzer.flush(), true);
            }
        });
    }

    /** Puts each vehicle's recent driver score and fuel efficiency on its twin. Plain SQL: no ML service involved. */
    @Scheduled(fixedDelayString = "${fleet.driving.twin-refresh-ms}")
    public void refreshTwins() {
        Instant since = Instant.now().minus(props.driving().twinPeriod());
        for (Vehicle vehicle : vehicles.findAll()) {
            Map<String, Object> totals = repository.totals(vehicle.getId(), since);
            double km = ((Number) totals.get("distanceKm")).doubleValue();
            double litres = ((Number) totals.get("fuelLitres")).doubleValue();
            double penalty = ((Number) totals.get("penaltyPoints")).doubleValue();
            twins.applyDriving(vehicle.getId(),
                    km > 0 ? Math.round(DrivingAnalyzer.score(penalty, km, props.driving().minScoreKm()) * 10) / 10.0 : null,
                    litres > 0 ? Math.round(km / litres * 100) / 100.0 : null);
        }
    }

    /** Deletes the events and trips in the range and rebuilds them from telemetry. */
    @Transactional
    public synchronized Map<String, Object> reanalyse(Instant from, Instant to) {
        long started = System.currentTimeMillis();
        repository.deleteBetween(from, to);
        int rows = 0;
        int[] counts = new int[2];
        for (Vehicle vehicle : vehicles.findAll()) {
            DrivingAnalyzer analyzer = newAnalyzer();
            rows += repository.replayTelemetry(vehicle.getId(), from, to, reading -> {
                int[] stored = store(vehicle.getId(), analyzer.accept(reading), false);
                counts[0] += stored[0];
                counts[1] += stored[1];
            });
            int[] stored = store(vehicle.getId(), analyzer.flush(), false);
            counts[0] += stored[0];
            counts[1] += stored[1];
        }
        analyzers.clear(); // live analysis starts afresh rather than mixing with what was just rebuilt
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("telemetryRows", rows);
        result.put("events", counts[0]);
        result.put("trips", counts[1]);
        result.put("seconds", (System.currentTimeMillis() - started) / 1000.0);
        log.info("Reanalysed driving from {} to {}: {}", from, to, result);
        return result;
    }

    /** Returns {events stored, trips stored}. Alerts are only raised for live data, not for replayed history. */
    private int[] store(long vehicleId, Output output, boolean live) {
        List<Event> events = new ArrayList<>(output.events());
        for (Trip trip : output.trips()) {
            lowEfficiency(vehicleId, trip).ifPresent(events::add);
            repository.insertTrip(vehicleId, trip);
        }
        if (!events.isEmpty()) {
            repository.insertEvents(vehicleId, events);
        }
        if (live) {
            for (Event e : events) {
                if (e.type().equals("FUEL_DROP") || e.type().equals("LOW_EFFICIENCY")) {
                    alerts.raise(vehicleId, "fuel", e.severity().equals("LOW") ? Status.WARNING : Status.CRITICAL,
                            e.detail(), Alert.Source.RULE);
                }
            }
        }
        return new int[] {events.size(), output.trips().size()};
    }

    /** A trip that got far fewer km per litre than this vehicle's own recent trips. */
    private Optional<Event> lowEfficiency(long vehicleId, Trip trip) {
        FleetProperties.Fuel fuel = props.fuel();
        if (trip.distanceKm() < fuel.minTripKm() || trip.fuelUsedL() <= 0) {
            return Optional.empty();
        }
        Double baseline = repository.baselineKmPerLitre(vehicleId, trip.startedAt(), fuel.minTripKm(),
                fuel.baselineTrips(), fuel.minBaselineTrips());
        double efficiency = trip.distanceKm() / trip.fuelUsedL();
        if (baseline == null || efficiency >= baseline * (1 - fuel.lowEfficiencyFraction())) {
            return Optional.empty();
        }
        double below = 1 - efficiency / baseline;
        return Optional.of(new Event("LOW_EFFICIENCY", below >= 2 * fuel.lowEfficiencyFraction() ? "MEDIUM" : "LOW",
                trip.endedAt(), trip.endLat(), trip.endLng(), efficiency,
                String.format(Locale.ROOT, "Trip got %.2f km/l, %.0f%% below this vehicle's baseline of %.2f km/l",
                        efficiency, below * 100, baseline)));
    }

    private DrivingAnalyzer newAnalyzer() {
        return new DrivingAnalyzer(props.driving(), props.fuel(), props.twin().movingSpeedKmh());
    }
}
