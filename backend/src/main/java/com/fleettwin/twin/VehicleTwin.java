package com.fleettwin.twin;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fleettwin.maintenance.MaintenanceRecord;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Live state of one vehicle. Stored as JSON in Redis (key twin:{id}) and pushed to /topic/twins. */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VehicleTwin {

    public enum State { MOVING, IDLE, OFFLINE }

    /** Predicted remaining useful life in days, with an 80% interval. */
    public record Rul(double days, double lower, double upper, double confidence, Instant updatedAt) {
    }

    // identity
    private Long id;
    private String registration;
    private String make;
    private String model;
    private Integer year;

    // location
    private Double lat;
    private Double lng;
    private Double heading;
    private Instant lastSeen;

    private State state = State.OFFLINE;

    /** Latest value of every numeric telemetry field, keyed by camelCase field name. */
    private Map<String, Double> sensors = new LinkedHashMap<>();
    private List<String> dtcCodes = List.of();

    /** engine, battery, brakes, tyre_fl, tyre_fr, tyre_rl, tyre_rr, fuel. */
    private Map<String, Status> components = new LinkedHashMap<>();

    // predicted health, from the ML service; null until its first successful reply
    private Double healthScore;
    private Boolean anomaly;
    private Double anomalyScore;
    private List<String> anomalyReasons;
    private Instant mlUpdatedAt;

    /** brakes, battery, tyres, engine; a part is absent until the ML service has predicted it. */
    private Map<String, Rul> rul = new LinkedHashMap<>();

    /** Recommendations with status OPEN, as of the last recommendation run or status change. */
    private Long openRecommendations;

    // maintenance summary; only filled in by GET /api/vehicles/{id}/twin, never stored in Redis
    private List<MaintenanceRecord> recentMaintenance;
    private Long maintenanceCount;
}
