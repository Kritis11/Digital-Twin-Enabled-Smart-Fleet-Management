package com.fleettwin.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fleettwin.config.FleetProperties.Utilisation;
import com.fleettwin.twin.VehicleTwin;
import com.fleettwin.twin.VehicleTwin.Rul;
import org.junit.jupiter.api.Test;

class RouteRulesTest {

    private static VehicleTwin twin(Double lat, double brakeRulDays) {
        VehicleTwin twin = new VehicleTwin();
        twin.setLat(lat);
        twin.setLng(77.6);
        twin.getRul().put("brakes", new Rul(brakeRulDays, 0, 0, 0.8, Instant.EPOCH));
        return twin;
    }

    @Test
    void healthyVehicleIsEligible() {
        assertEquals(List.of(), RouteEligibility.exclusions(twin(12.9, 20), List.of(), 0, true, 3));
    }

    @Test
    void everyReasonForLeavingAVehicleOutIsListed() {
        assertEquals(
                List.of("Urgent recommendation outstanding: Replace battery", "2 open critical alerts",
                        "Brakes predicted to fail in 1.5 days (minimum for a route is 3)"),
                RouteEligibility.exclusions(twin(12.9, 1.5), List.of("Replace battery"), 2, true, 3));
    }

    @Test
    void positionOnlyMattersWithoutADepot() {
        assertEquals(List.of("Position unknown (no telemetry yet)"),
                RouteEligibility.exclusions(twin(null, 20), List.of(), 0, true, 3));
        assertEquals(List.of(), RouteEligibility.exclusions(twin(null, 20), List.of(), 0, false, 3));
    }

    @Test
    void thirstierAndRougherVehiclesCostMorePerKm() {
        assertEquals(1.0, RouteEligibility.costFactor(2.5, 2.5, 100, 0.5, 0.5), 1e-9);
        assertTrue(RouteEligibility.costFactor(2.0, 2.5, 100, 0.5, 0.5) > 1, "worse km/l");
        assertEquals(1.25, RouteEligibility.costFactor(2.5, 2.5, 50, 0.5, 0.5), 1e-9, "driver score 50");
    }

    @Test
    void utilisationFlagsUnderAndOverUsedVehicles() {
        // 7 days x 10 working hours = 70 available hours per vehicle
        List<Map<String, Object>> rows = List.of(
                Map.of("vehicleId", 1L, "registration", "BUSY", "trips", 30L, "activeSeconds", 65 * 3600L, "idleSeconds", 6 * 3600L, "distanceKm", 2000.0),
                Map.of("vehicleId", 2L, "registration", "QUIET", "trips", 2L, "activeSeconds", 7 * 3600L, "idleSeconds", 0L, "distanceKm", 150.0),
                Map.of("vehicleId", 3L, "registration", "PARKED", "trips", 0L, "activeSeconds", 0L, "idleSeconds", 0L, "distanceKm", 0.0));
        Map<String, Object> out = RouteController.summarise(rows, 7, new Utilisation(10, 0.3, 0.85));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vehicles = (List<Map<String, Object>>) out.get("vehicles");
        assertEquals(List.of("OVER_USED", "UNDER_USED", "UNDER_USED"), vehicles.stream().map(v -> v.get("usage")).toList());
        assertEquals(0.93, vehicles.get(0).get("utilisation"));
        assertEquals(null, vehicles.get(2).get("idleShare"), "no driving, so no idle share");
        @SuppressWarnings("unchecked")
        Map<String, Object> fleet = (Map<String, Object>) out.get("fleet");
        assertEquals(72.0, fleet.get("activeHours"));
        assertEquals(0.34, fleet.get("utilisation"));
        assertEquals(List.of("QUIET", "PARKED"), fleet.get("underUsed"));
    }
}
