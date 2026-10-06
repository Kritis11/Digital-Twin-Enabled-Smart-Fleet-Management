package com.fleettwin.driving;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fleettwin.config.FleetProperties;
import com.fleettwin.driving.DrivingAnalyzer.Output;
import com.fleettwin.driving.DrivingAnalyzer.Reading;
import com.fleettwin.driving.DrivingAnalyzer.Trip;
import com.fleettwin.telemetry.Telemetry;
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
    private final Map<Long, DrivingAnalyzer> analyzers = new HashMap<>();

    /** Called for every stored telemetry row. */
    public synchronized void onTelemetry(Telemetry t) {
        Reading reading = new Reading(t.getTs(), t.getLat(), t.getLng(), t.getSpeed(), t.getFuelLevel(),
                t.getAccelMin(), t.getAccelMax(), t.getOdometerKm());
        store(t.getVehicleId(), analyzers.computeIfAbsent(t.getVehicleId(), id -> newAnalyzer()).accept(reading));
    }

    /** A vehicle that went quiet never sends the reading that would end its trip, so end it here. */
    @Scheduled(fixedDelayString = "${fleet.driving.flush-interval-ms}")
    public synchronized void flushStale() {
        Instant cutoff = Instant.now().minus(props.driving().tripGap());
        analyzers.forEach((vehicleId, analyzer) -> {
            if (analyzer.lastSeen() != null && analyzer.lastSeen().isBefore(cutoff)) {
                store(vehicleId, analyzer.flush());
            }
        });
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
                int[] stored = store(vehicle.getId(), analyzer.accept(reading));
                counts[0] += stored[0];
                counts[1] += stored[1];
            });
            int[] stored = store(vehicle.getId(), analyzer.flush());
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

    /** Returns {events stored, trips stored}. */
    private int[] store(long vehicleId, Output output) {
        if (!output.events().isEmpty()) {
            repository.insertEvents(vehicleId, output.events());
        }
        for (Trip trip : output.trips()) {
            repository.insertTrip(vehicleId, trip);
        }
        return new int[] {output.events().size(), output.trips().size()};
    }

    private DrivingAnalyzer newAnalyzer() {
        return new DrivingAnalyzer(props.driving(), props.fuel(), props.twin().movingSpeedKmh());
    }
}
