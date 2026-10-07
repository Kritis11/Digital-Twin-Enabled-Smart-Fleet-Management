package com.fleettwin.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fleettwin.config.FleetProperties;
import com.fleettwin.driving.DrivingRepository;
import com.fleettwin.driving.Periods;
import com.fleettwin.routing.RouteService.OptimiseRequest;
import com.fleettwin.routing.RouteService.OptimiseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Route optimisation, route history and fleet utilisation. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService service;
    private final DrivingRepository repository;
    private final FleetProperties props;

    @PostMapping("/routes/optimise")
    public OptimiseResponse optimise(@Valid @RequestBody OptimiseRequest request) {
        return service.optimise(request);
    }

    /** The vehicle's route on each day it drove, newest first: start and end points, distance, stops, events, fuel. */
    @GetMapping("/vehicles/{id}/routes")
    public List<Map<String, Object>> routes(@PathVariable long id, @RequestParam(defaultValue = "7d") String period) {
        return repository.routes(id, Periods.since(period));
    }

    /** Hours on trips, idle time and distance per vehicle and for the fleet, and which vehicles are under- or over-used. */
    @GetMapping("/fleet/utilisation")
    public Map<String, Object> utilisation(@RequestParam(defaultValue = "7d") String period) {
        Instant since = Periods.since(period);
        return summarise(repository.utilisation(since), Duration.between(since, Instant.now()).toSeconds() / 86400.0,
                props.utilisation());
    }

    static Map<String, Object> summarise(List<Map<String, Object>> rows, double days, FleetProperties.Utilisation cfg) {
        double availableHours = days * cfg.workingHoursPerDay();
        List<Map<String, Object>> vehicles = new ArrayList<>();
        double activeHours = 0;
        double idleHours = 0;
        double km = 0;
        for (Map<String, Object> row : rows) {
            double active = ((Number) row.get("activeSeconds")).doubleValue() / 3600;
            double idle = ((Number) row.get("idleSeconds")).doubleValue() / 3600;
            double distance = ((Number) row.get("distanceKm")).doubleValue();
            double utilisation = active / availableHours;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("vehicleId", row.get("vehicleId"));
            v.put("registration", row.get("registration"));
            v.put("trips", row.get("trips"));
            v.put("activeHours", round1(active));
            v.put("idleHours", round1(idle));
            v.put("idleShare", active > 0 ? round2(idle / active) : null);
            v.put("distanceKm", round1(distance));
            v.put("utilisation", round2(utilisation));
            v.put("usage", utilisation < cfg.underUsedBelow() ? "UNDER_USED"
                    : utilisation > cfg.overUsedAbove() ? "OVER_USED" : "NORMAL");
            vehicles.add(v);
            activeHours += active;
            idleHours += idle;
            km += distance;
        }
        Map<String, Object> fleet = new LinkedHashMap<>();
        fleet.put("activeHours", round1(activeHours));
        fleet.put("idleHours", round1(idleHours));
        fleet.put("distanceKm", round1(km));
        fleet.put("utilisation", rows.isEmpty() ? null : round2(activeHours / (availableHours * rows.size())));
        fleet.put("underUsed", vehicles.stream().filter(v -> "UNDER_USED".equals(v.get("usage"))).map(v -> v.get("registration")).toList());
        fleet.put("overUsed", vehicles.stream().filter(v -> "OVER_USED".equals(v.get("usage"))).map(v -> v.get("registration")).toList());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("availableHoursPerVehicle", round1(availableHours));
        out.put("vehicles", vehicles);
        out.put("fleet", fleet);
        return out;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
