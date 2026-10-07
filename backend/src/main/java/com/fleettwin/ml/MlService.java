package com.fleettwin.ml;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fleettwin.alert.Alert;
import com.fleettwin.alert.AlertService;
import com.fleettwin.config.FleetProperties;
import com.fleettwin.telemetry.Telemetry;
import com.fleettwin.telemetry.TelemetryRepository;
import com.fleettwin.twin.Status;
import com.fleettwin.twin.TwinService;
import com.fleettwin.twin.VehicleTwin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Periodically sends each online vehicle's recent telemetry to the ML service and writes the result
 * into its twin. Runs on the scheduler thread, never on the MQTT thread, and treats every ML failure
 * as "skip this cycle": rule-based statuses and telemetry ingest carry on without it.
 */
@Service
@Slf4j
public class MlService {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnomalyResult(boolean isAnomaly, double score, List<String> reasons) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HealthResult(double healthScore) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RulResult(double rulDays, double lowerBound, double upperBound, double confidence) {
    }

    private final FleetProperties.Ml props;
    private final RestClient client;
    private final TwinService twins;
    private final TelemetryRepository telemetry;
    private final AlertService alerts;
    private boolean noModelWarned;

    public MlService(FleetProperties fleet, RestClient.Builder builder, TwinService twins,
                     TelemetryRepository telemetry, AlertService alerts) {
        this.props = fleet.ml();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeout());
        factory.setReadTimeout(props.timeout());
        this.client = builder.baseUrl(props.url()).requestFactory(factory).build();
        this.twins = twins;
        this.telemetry = telemetry;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${fleet.ml.interval-ms}")
    public void scoreFleet() {
        if (!props.enabled()) {
            return;
        }
        try {
            for (VehicleTwin twin : twins.all()) {
                if (twin.getState() != VehicleTwin.State.OFFLINE) {
                    score(twin);
                }
            }
        } catch (Exception e) {
            // One warning per cycle, not per vehicle: if the service is down it is down for all of them.
            log.warn("Anomaly scoring skipped this cycle, rule-based statuses carry on: {}", e.toString());
        }
    }

    /**
     * Refreshes remaining useful life for every vehicle, online or not: wear does not stop mattering
     * when a vehicle is parked. The ML service reads the history it needs from TimescaleDB itself.
     */
    @Scheduled(fixedDelayString = "${fleet.ml.rul-interval-ms}")
    public void predictRul() {
        if (!props.enabled()) {
            return;
        }
        try {
            for (VehicleTwin twin : twins.all()) {
                Map<String, VehicleTwin.Rul> rul = new LinkedHashMap<>();
                for (String component : props.rulComponents()) {
                    try {
                        RulResult r = client.post().uri("/rul")
                                .body(Map.of("vehicle_id", twin.getId(), "component", component))
                                .retrieve().body(RulResult.class);
                        rul.put(component, new VehicleTwin.Rul(r.rulDays(), r.lowerBound(), r.upperBound(), r.confidence(), Instant.now()));
                    } catch (HttpClientErrorException.NotFound | HttpServerErrorException.ServiceUnavailable e) {
                        // no wear history for this vehicle yet, or no model trained for this part: nothing to show
                    }
                }
                if (!rul.isEmpty()) {
                    twins.applyRul(twin.getId(), rul);
                }
            }
        } catch (Exception e) {
            log.warn("Remaining useful life not refreshed this cycle: {}", e.toString());
        }
    }

    private void score(VehicleTwin twin) {
        List<Telemetry> recent = telemetry.findByVehicleIdAndTsAfterOrderByTs(
                twin.getId(), Instant.now().minus(props.window()));
        if (recent.isEmpty()) {
            return;
        }
        AnomalyResult anomaly = anomaly(twin.getId(), recent);
        Map<String, Object> healthRequest = new LinkedHashMap<>();
        healthRequest.put("vehicle_id", twin.getId());
        healthRequest.put("components", twin.getComponents());
        healthRequest.put("anomaly_score", anomaly == null ? null : anomaly.score());
        HealthResult health = client.post().uri("/health-score").body(healthRequest).retrieve().body(HealthResult.class);

        twins.applyMl(twin.getId(), health.healthScore(), anomaly == null ? null : anomaly.isAnomaly(),
                anomaly == null ? null : anomaly.score(), anomaly == null ? null : anomaly.reasons());

        if (anomaly != null && anomaly.isAnomaly()) {
            String top = anomaly.reasons().isEmpty() ? "" : anomaly.reasons().get(0);
            alerts.raise(twin.getId(), componentOf(top),
                    anomaly.score() >= props.anomalyCriticalScore() ? Status.CRITICAL : Status.WARNING,
                    String.format(Locale.ROOT, "ML anomaly, score %.2f: %s", anomaly.score(),
                            String.join(", ", anomaly.reasons())),
                    Alert.Source.ML);
        }
    }

    /** null when the ML service is up but has no trained model yet (503); the health score still works. */
    private AnomalyResult anomaly(long vehicleId, List<Telemetry> recent) {
        List<Map<String, Object>> readings = recent.stream().map(t -> {
            Map<String, Object> reading = new LinkedHashMap<>();
            reading.put("ts", t.getTs().toString());
            t.sensorValues().forEach((name, value) -> reading.put(snakeCase(name), value));
            return reading;
        }).toList();
        try {
            AnomalyResult result = client.post().uri("/anomaly")
                    .body(Map.of("vehicle_id", vehicleId, "readings", readings))
                    .retrieve().body(AnomalyResult.class);
            noModelWarned = false;
            return result;
        } catch (HttpServerErrorException.ServiceUnavailable e) {
            if (!noModelWarned) {
                log.warn("ML service has no anomaly model loaded; health scores use component statuses only");
                noModelWarned = true;
            }
            return null;
        }
    }

    /** Twin component for a model reason such as "tyre_pressure_fl_min_30s=19.80". */
    static String componentOf(String reason) {
        if (reason.startsWith("tyre_pressure_") && reason.length() >= 16) {
            return "tyre_" + reason.substring(14, 16);
        }
        if (reason.startsWith("battery_voltage")) {
            return "battery";
        }
        if (reason.startsWith("engine_temp") || reason.startsWith("vibration") || reason.startsWith("rpm")) {
            return "engine";
        }
        return "vehicle";
    }

    private static String snakeCase(String camelCase) {
        return camelCase.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }
}
