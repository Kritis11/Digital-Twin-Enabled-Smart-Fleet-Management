package com.fleettwin.twin;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleettwin.alert.Alert;
import com.fleettwin.alert.AlertService;
import com.fleettwin.config.FleetProperties;
import com.fleettwin.maintenance.MaintenanceRepository;
import com.fleettwin.telemetry.Telemetry;
import com.fleettwin.vehicle.Vehicle;
import com.fleettwin.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owns the live twins in Redis. PostgreSQL stays the source of truth for identity and history;
 * a twin is rebuilt from the next telemetry message if Redis is wiped.
 */
// ponytail: twin updates are read-modify-write guarded by this JVM's lock; use a Redis Lua
// script or WATCH if the backend is ever run as more than one instance.
@Service
@RequiredArgsConstructor
public class TwinService {

    public static final String TOPIC = "/topic/twins";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final VehicleRepository vehicles;
    private final MaintenanceRepository maintenance;
    private final AlertService alerts;
    private final SimpMessagingTemplate messaging;
    private final FleetProperties props;
    private final Map<Long, Vehicle> identities = new ConcurrentHashMap<>();

    public List<VehicleTwin> all() {
        List<Vehicle> fleet = vehicles.findAll(Sort.by("id"));
        // one MGET instead of a GET per vehicle
        List<String> stored = redis.opsForValue().multiGet(fleet.stream().map(v -> key(v.getId())).toList());
        List<VehicleTwin> twins = new ArrayList<>(fleet.size());
        for (int i = 0; i < fleet.size(); i++) {
            twins.add(toTwin(fleet.get(i), stored.get(i)));
        }
        return twins;
    }

    /** The twin plus its maintenance summary. */
    public Optional<VehicleTwin> full(long vehicleId) {
        return vehicles.findById(vehicleId).map(vehicle -> {
            VehicleTwin twin = load(vehicle);
            twin.setRecentMaintenance(maintenance.findByVehicleIdOrderByPerformedAtDesc(
                    vehicleId, PageRequest.of(0, props.twin().maintenanceSummarySize())));
            twin.setMaintenanceCount(maintenance.countByVehicleId(vehicleId));
            return twin;
        });
    }

    /** Called for every stored telemetry row. */
    public synchronized void onTelemetry(Telemetry t) {
        VehicleTwin twin = load(t.getVehicleId());
        if (twin.getLastSeen() != null && t.getTs().isBefore(twin.getLastSeen())) {
            return; // late or re-delivered message; the twin already reflects something newer
        }
        if (t.getLat() != null && t.getLng() != null) {
            boolean moved = twin.getLat() != null && twin.getLng() != null
                    && (!t.getLat().equals(twin.getLat()) || !t.getLng().equals(twin.getLng()));
            if (moved) {
                twin.setHeading(bearing(twin.getLat(), twin.getLng(), t.getLat(), t.getLng()));
            }
            twin.setLat(t.getLat());
            twin.setLng(t.getLng());
        }
        twin.setLastSeen(t.getTs());
        twin.setState(t.getSpeed() != null && t.getSpeed() > props.twin().movingSpeedKmh()
                ? VehicleTwin.State.MOVING : VehicleTwin.State.IDLE);
        twin.setSensors(t.sensorValues());
        twin.setDtcCodes(t.getDtcCodes());

        Map<String, Status> statuses = new LinkedHashMap<>();
        Rule.evaluateAll(props.rules(), twin.getSensors()).forEach((component, finding) -> {
            Status previous = twin.getComponents().getOrDefault(component, Status.OK);
            if (finding.status() != previous && finding.status() != Status.OK) {
                alerts.raise(twin.getId(), component, finding.status(), finding.message(), Alert.Source.RULE);
            }
            statuses.put(component, finding.status());
        });
        twin.setComponents(statuses);
        save(twin);
    }

    /** Stores the ML service's latest verdict. The anomaly fields are null when no model is loaded. */
    public synchronized void applyMl(long vehicleId, double healthScore, Boolean anomaly, Double score, List<String> reasons) {
        VehicleTwin twin = load(vehicleId);
        twin.setHealthScore(healthScore);
        twin.setAnomaly(anomaly);
        twin.setAnomalyScore(score);
        twin.setAnomalyReasons(reasons);
        twin.setMlUpdatedAt(Instant.now());
        save(twin);
    }

    public synchronized void applyDriving(long vehicleId, Double driverScore, Double kmPerLitre) {
        VehicleTwin twin = load(vehicleId);
        if (!Objects.equals(twin.getDriverScore(), driverScore)
                || !Objects.equals(twin.getFuelEfficiencyKmPerLitre(), kmPerLitre)) {
            twin.setDriverScore(driverScore);
            twin.setFuelEfficiencyKmPerLitre(kmPerLitre);
            save(twin);
        }
    }

    public synchronized void refreshOpenRecommendations(long vehicleId, long open) {
        VehicleTwin twin = load(vehicleId);
        if (twin.getOpenRecommendations() == null || twin.getOpenRecommendations() != open) {
            twin.setOpenRecommendations(open);
            save(twin);
        }
    }

    public synchronized void applyRul(long vehicleId, Map<String, VehicleTwin.Rul> rul) {
        VehicleTwin twin = load(vehicleId);
        twin.getRul().putAll(rul);
        save(twin);
    }

    /**
     * A part was replaced or serviced: set its wear reading to new and drop its stale RUL, without
     * waiting for the vehicle's next message to confirm it.
     */
    public synchronized void resetWear(long vehicleId, String component, String sensor, Double newValue) {
        VehicleTwin twin = load(vehicleId);
        if (sensor != null && newValue != null) {
            twin.getSensors().put(sensor, newValue);
        }
        twin.getRul().remove(component);
        Map<String, Status> statuses = new LinkedHashMap<>();
        Rule.evaluateAll(props.rules(), twin.getSensors()).forEach((name, finding) -> statuses.put(name, finding.status()));
        twin.setComponents(statuses);
        save(twin);
    }

    @Scheduled(fixedDelayString = "${fleet.twin.sweep-interval-ms}")
    public synchronized void markOffline() {
        Instant cutoff = Instant.now().minus(props.twin().offlineAfter());
        for (VehicleTwin twin : all()) {
            if (twin.getState() != VehicleTwin.State.OFFLINE
                    && twin.getLastSeen() != null && twin.getLastSeen().isBefore(cutoff)) {
                twin.setState(VehicleTwin.State.OFFLINE);
                save(twin);
            }
        }
    }

    /** Initial compass bearing in degrees (0 = north, 90 = east) from the first point to the second. */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /**
     * The twin of a known vehicle. Called for every telemetry reading, so the vehicle's identity comes
     * from memory rather than the database; it changes only with a migration, i.e. with a restart.
     */
    private VehicleTwin load(long vehicleId) {
        Vehicle vehicle = identities.computeIfAbsent(vehicleId, id -> vehicles.findById(id).orElseThrow());
        return load(vehicle);
    }

    private VehicleTwin load(Vehicle vehicle) {
        return toTwin(vehicle, redis.opsForValue().get(key(vehicle.getId())));
    }

    private VehicleTwin toTwin(Vehicle vehicle, String json) {
        VehicleTwin twin;
        try {
            twin = json == null ? new VehicleTwin() : mapper.readValue(json, VehicleTwin.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        twin.setId(vehicle.getId());
        twin.setRegistration(vehicle.getRegistration());
        twin.setMake(vehicle.getMake());
        twin.setModel(vehicle.getModel());
        twin.setYear(vehicle.getYear());
        return twin;
    }

    private void save(VehicleTwin twin) {
        try {
            redis.opsForValue().set(key(twin.getId()), mapper.writeValueAsString(twin));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        messaging.convertAndSend(TOPIC, twin);
    }

    private static String key(long vehicleId) {
        return "twin:" + vehicleId;
    }
}
