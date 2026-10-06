package com.fleettwin.driving;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fleettwin.config.FleetProperties;
import com.fleettwin.vehicle.Vehicle;
import com.fleettwin.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fuel use, efficiency and idling cost, all aggregated from the trips table. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FuelController {

    private final DrivingRepository repository;
    private final VehicleRepository vehicles;
    private final FleetProperties props;

    /** Totals and a per-day breakdown for one vehicle, with its fuel anomalies and driver score for the period. */
    @GetMapping("/vehicles/{id}/fuel")
    public Map<String, Object> fuel(@PathVariable long id, @RequestParam(defaultValue = "7d") String period) {
        Instant since = Periods.since(period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vehicleId", id);
        out.put("period", period);
        out.putAll(summarise(repository.totals(id, since)));
        out.put("baselineKmPerLitre", round(repository.baselineKmPerLitre(id, Instant.now(), props.fuel().minTripKm(),
                props.fuel().baselineTrips(), props.fuel().minBaselineTrips())));
        out.put("daily", repository.daily(id, since).stream().map(day -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", day.get("day").toString());
            row.putAll(summarise(day));
            return row;
        }).toList());
        out.put("anomalies", repository.fuelEvents(id, since, props.driving().maxResults()));
        return out;
    }

    /** One row per vehicle plus fleet totals, and how driver score relates to efficiency. */
    @GetMapping("/fleet/fuel-summary")
    public Map<String, Object> fleetSummary(@RequestParam(defaultValue = "7d") String period) {
        Instant since = Periods.since(period);
        List<Map<String, Object>> rows = new ArrayList<>();
        double km = 0;
        double litres = 0;
        double idleLitres = 0;
        for (Vehicle vehicle : vehicles.findAll(Sort.by("id"))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicleId", vehicle.getId());
            row.put("registration", vehicle.getRegistration());
            row.putAll(summarise(repository.totals(vehicle.getId(), since)));
            row.put("anomalies", repository.fuelEvents(vehicle.getId(), since, props.driving().maxResults()).size());
            rows.add(row);
            km += (double) row.get("distanceKm");
            litres += (double) row.get("fuelLitres");
            idleLitres += (double) row.get("idleLitres");
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("distanceKm", round(km));
        totals.put("fuelLitres", round(litres));
        totals.put("kmPerLitre", litres > 0 ? round(km / litres) : null);
        totals.put("idleLitres", round(idleLitres));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("vehicles", rows);
        out.put("totals", totals);
        // Across vehicle-days: above 0 means better-scored days also went further per litre.
        out.put("scoreEfficiencyCorrelation", round(repository.scoreEfficiencyCorrelation(since, props.driving().minScoreKm())));
        out.put("idlingSummary", litres > 0
                ? String.format(Locale.ROOT, "Idling used %.0f l, %.1f%% of the %.0f l burned in this period.",
                        idleLitres, idleLitres / litres * 100, litres)
                : "No trips in this period.");
        return out;
    }

    /** Turns a totals row (distanceKm, fuelLitres, idleSeconds, penaltyPoints) into the figures the API reports. */
    private Map<String, Object> summarise(Map<String, Object> row) {
        double km = ((Number) row.get("distanceKm")).doubleValue();
        double litres = ((Number) row.get("fuelLitres")).doubleValue();
        double idleHours = ((Number) row.get("idleSeconds")).doubleValue() / 3600.0;
        double idleLitres = idleHours * props.fuel().idleLitresPerHour();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trips", row.get("trips"));
        out.put("distanceKm", round(km));
        out.put("fuelLitres", round(litres));
        out.put("kmPerLitre", litres > 0 ? round(km / litres) : null);
        out.put("litresPer100Km", km > 0 ? round(litres / km * 100) : null);
        out.put("idleHours", round(idleHours));
        out.put("idleLitres", round(idleLitres));
        out.put("idleShareOfFuel", litres > 0 ? round(idleLitres / litres) : null);
        out.put("driverScore", km > 0
                ? round(DrivingAnalyzer.score(((Number) row.get("penaltyPoints")).doubleValue(), km, props.driving().minScoreKm()))
                : null);
        return out;
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100) / 100.0;
    }
}
