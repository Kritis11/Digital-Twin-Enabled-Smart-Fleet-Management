package com.fleettwin.routing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fleettwin.config.FleetProperties;
import com.fleettwin.recommendation.Recommendation;
import com.fleettwin.recommendation.RecommendationRepository;
import com.fleettwin.twin.TwinService;
import com.fleettwin.twin.VehicleTwin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Picks the vehicles that are fit to drive, asks the ML service (OR-Tools, with OSRM road distances)
 * to assign and order the stops, and adds times and fuel estimates to the answer.
 */
@Service
public class RouteService {

    public record Point(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {
    }

    /** windowStart / windowEnd: optional earliest and latest arrival. */
    public record Stop(String name, @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                       @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
                       Instant windowStart, Instant windowEnd, @Min(0) Integer serviceMinutes) {
    }

    /**
     * vehicleIds: the vehicles on offer (default: all). depot: where every route starts; without it each
     * vehicle starts from its current position. departAt defaults to now, returnToStart to true.
     */
    public record OptimiseRequest(List<Long> vehicleIds, @NotEmpty List<@Valid @NotNull Stop> stops, @Valid Point depot,
                                  Instant departAt, Boolean returnToStart, @Min(1) Integer maxStopsPerVehicle) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Visit(int stop, String name, double lat, double lng, Instant arrival) {
    }

    public record VehicleRoute(long vehicleId, String registration, List<Visit> stops, double distanceKm,
                               long durationMinutes, double fuelLitres, double kmPerLitre, Double driverScore,
                               List<double[]> geometry) {
    }

    public record Excluded(long vehicleId, String registration, List<String> reasons) {
    }

    /** unassigned: indexes into the request's stops that no vehicle could reach inside their time window. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptimiseResponse(Instant departAt, List<VehicleRoute> routes, List<Excluded> excluded,
                                   List<Integer> unassigned, double totalDistanceKm, double totalFuelLitres,
                                   String distanceSource, String note) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlVisit(int id, long arrivalS) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlRoute(long vehicleId, List<MlVisit> stops, long distanceM, long durationS, List<double[]> geometry) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlResponse(List<MlRoute> routes, List<Integer> unassigned, String distanceSource, String note) {
    }

    private final FleetProperties.Routes cfg;
    private final RestClient client;
    private final TwinService twins;
    private final RecommendationRepository recommendations;
    private final JdbcTemplate jdbc;

    public RouteService(FleetProperties fleet, RestClient.Builder builder, TwinService twins,
                        RecommendationRepository recommendations, JdbcTemplate jdbc) {
        this.cfg = fleet.routes();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(fleet.ml().timeout());
        factory.setReadTimeout(cfg.timeout());
        this.client = builder.baseUrl(fleet.ml().url()).requestFactory(factory).build();
        this.twins = twins;
        this.recommendations = recommendations;
        this.jdbc = jdbc;
    }

    public OptimiseResponse optimise(OptimiseRequest req) {
        if (req.stops().size() > cfg.maxStops()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at most " + cfg.maxStops() + " stops");
        }
        Instant departAt = req.departAt() != null ? req.departAt() : Instant.now();

        Map<Long, List<String>> urgent = recommendations
                .findByStatusIn(List.of(Recommendation.State.OPEN, Recommendation.State.SCHEDULED)).stream()
                .filter(r -> r.getPriority() == Recommendation.Priority.URGENT)
                .collect(Collectors.groupingBy(Recommendation::getVehicleId,
                        Collectors.mapping(Recommendation::getAction, Collectors.toList())));
        Map<Long, Integer> criticalAlerts = new HashMap<>();
        jdbc.query("SELECT vehicle_id, count(*) FROM alerts WHERE NOT acknowledged AND severity = 'CRITICAL' GROUP BY vehicle_id",
                rs -> {
                    criticalAlerts.put(rs.getLong(1), rs.getInt(2));
                });

        List<VehicleTwin> eligible = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (VehicleTwin twin : twins.all()) {
            if (req.vehicleIds() != null && !req.vehicleIds().contains(twin.getId())) {
                continue;
            }
            List<String> reasons = RouteEligibility.exclusions(twin, urgent.getOrDefault(twin.getId(), List.of()),
                    criticalAlerts.getOrDefault(twin.getId(), 0), req.depot() == null, cfg.minRulDays());
            if (reasons.isEmpty()) {
                eligible.add(twin);
            } else {
                excluded.add(new Excluded(twin.getId(), twin.getRegistration(), reasons));
            }
        }
        List<Integer> everyStop = java.util.stream.IntStream.range(0, req.stops().size()).boxed().toList();
        if (eligible.isEmpty()) {
            return new OptimiseResponse(departAt, List.of(), excluded, everyStop, 0, 0, null,
                    "No vehicle is fit to be assigned.");
        }

        double bestKmPerLitre = eligible.stream().mapToDouble(this::kmPerLitre).max().orElseThrow();
        double typicalScore = eligible.stream().filter(t -> t.getDriverScore() != null)
                .mapToDouble(VehicleTwin::getDriverScore).average().orElse(100);
        Map<Long, VehicleTwin> byId = new LinkedHashMap<>();
        List<Map<String, Object>> vehicles = new ArrayList<>();
        for (VehicleTwin twin : eligible) {
            byId.put(twin.getId(), twin);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", twin.getId());
            v.put("lat", req.depot() != null ? req.depot().lat() : twin.getLat());
            v.put("lng", req.depot() != null ? req.depot().lng() : twin.getLng());
            v.put("cost_factor", RouteEligibility.costFactor(kmPerLitre(twin), bestKmPerLitre,
                    twin.getDriverScore() != null ? twin.getDriverScore() : typicalScore,
                    cfg.fuelWeight(), cfg.driverScoreWeight()));
            vehicles.add(v);
        }
        List<Map<String, Object>> stops = new ArrayList<>();
        for (int i = 0; i < req.stops().size(); i++) {
            Stop s = req.stops().get(i);
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("id", i);
            stop.put("lat", s.lat());
            stop.put("lng", s.lng());
            stop.put("service_s", 60 * (s.serviceMinutes() != null ? s.serviceMinutes() : cfg.defaultServiceMinutes()));
            if (s.windowStart() != null) {
                stop.put("window_start_s", Math.max(0, s.windowStart().getEpochSecond() - departAt.getEpochSecond()));
            }
            if (s.windowEnd() != null) {
                // a window that closed before departure becomes 0-0, which nothing can meet unless it is at the start
                stop.put("window_end_s", Math.max(0, s.windowEnd().getEpochSecond() - departAt.getEpochSecond()));
            }
            stops.add(stop);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicles", vehicles);
        body.put("stops", stops);
        body.put("return_to_start", !Boolean.FALSE.equals(req.returnToStart()));
        body.put("balance", cfg.balance());
        body.put("time_limit_s", cfg.solverSeconds());
        if (req.maxStopsPerVehicle() != null) {
            body.put("max_stops_per_vehicle", req.maxStopsPerVehicle());
        }

        MlResponse ml;
        try {
            ml = client.post().uri("/optimise-routes").body(body).retrieve().body(MlResponse.class);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Route optimisation needs the ML service, which did not answer: " + e.getMessage());
        }

        List<VehicleRoute> routes = new ArrayList<>();
        for (MlRoute r : ml.routes()) {
            VehicleTwin twin = byId.get(r.vehicleId());
            double km = r.distanceM() / 1000.0;
            routes.add(new VehicleRoute(twin.getId(), twin.getRegistration(),
                    r.stops().stream().map(v -> {
                        Stop s = req.stops().get(v.id());
                        return new Visit(v.id(), s.name(), s.lat(), s.lng(), departAt.plusSeconds(v.arrivalS()));
                    }).toList(),
                    round1(km), Math.round(r.durationS() / 60.0), round1(km / kmPerLitre(twin)), kmPerLitre(twin),
                    twin.getDriverScore(), r.geometry()));
        }
        return new OptimiseResponse(departAt, routes, excluded, ml.unassigned(),
                round1(routes.stream().mapToDouble(VehicleRoute::distanceKm).sum()),
                round1(routes.stream().mapToDouble(VehicleRoute::fuelLitres).sum()), ml.distanceSource(), ml.note());
    }

    private double kmPerLitre(VehicleTwin twin) {
        Double own = twin.getFuelEfficiencyKmPerLitre();
        return own != null && own > 0 ? own : cfg.defaultKmPerLitre();
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
