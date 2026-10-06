package com.fleettwin.recommendation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fleettwin.alert.AlertService;
import com.fleettwin.config.FleetProperties;
import com.fleettwin.config.FleetProperties.Recommendations;
import com.fleettwin.maintenance.MaintenanceRecord;
import com.fleettwin.maintenance.MaintenanceRepository;
import com.fleettwin.recommendation.Recommendation.State;
import com.fleettwin.recommendation.RecommendationEngine.Evidence;
import com.fleettwin.recommendation.RecommendationEngine.Proposal;
import com.fleettwin.twin.Status;
import com.fleettwin.twin.TwinService;
import com.fleettwin.twin.VehicleTwin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gathers the evidence for each vehicle and component, runs it through {@link RecommendationEngine}
 * and keeps the maintenance_recommendations table in step. Uses only the twin and PostgreSQL, so it
 * keeps working when the ML service is down (it just has no RUL evidence to go on).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationService {

    private static final List<State> ACTIVE = List.of(State.OPEN, State.SCHEDULED);

    private final FleetProperties props;
    private final RecommendationRepository repository;
    private final MaintenanceRepository maintenance;
    private final TwinService twins;
    private final AlertService alerts;
    private final JdbcTemplate jdbc;
    private final MqttPahoMessageHandler mqttOutbound;

    @Scheduled(fixedDelayString = "${fleet.recommendations.interval-ms}",
            initialDelayString = "${fleet.recommendations.initial-delay-ms}")
    public void scheduledRecompute() {
        try {
            recompute();
        } catch (Exception e) {
            log.warn("Recommendation run failed: {}", e.toString());
        }
    }

    /** Returns how many recommendations were created, updated and withdrawn. */
    public synchronized Map<String, Integer> recompute() {
        Recommendations cfg = props.recommendations();
        Map<String, Integer> counts = new LinkedHashMap<>(Map.of("created", 0, "updated", 0, "withdrawn", 0));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (VehicleTwin twin : twins.all()) {
            Map<String, int[]> openAlerts = openAlerts(twin.getId());
            Map<String, Integer> anomalies = recentAnomalies(twin.getId(), cfg.anomalyWindow());
            cfg.components().forEach((component, spec) -> {
                Evidence evidence = new Evidence(
                        status(twin, component), twin.getRul().get(component),
                        openAlerts.getOrDefault(component, new int[2])[0], openAlerts.getOrDefault(component, new int[2])[1],
                        anomalies.getOrDefault(component, 0),
                        daysSinceService(twin.getId(), component), kmSinceService(twin.getId(), component));
                Optional<Proposal> proposal = RecommendationEngine.evaluate(evidence, spec, cfg, today);
                counts.merge(apply(twin.getId(), component, spec, proposal, cfg), 1, Integer::sum);
            });
        }
        counts.remove("unchanged");
        twins.all().forEach(twin -> twins.refreshOpenRecommendations(twin.getId(), openCount(twin.getId())));
        log.info("Recommendations recomputed: {}", counts);
        return counts;
    }

    private String apply(long vehicleId, String component, Recommendations.Component spec, Optional<Proposal> proposal,
                         Recommendations cfg) {
        Optional<Recommendation> active = repository.findByVehicleIdAndComponentAndStatusIn(vehicleId, component, ACTIVE);
        if (proposal.isEmpty()) {
            // The evidence is gone. Withdraw it unless someone already scheduled the work.
            if (active.isPresent() && active.get().getStatus() == State.OPEN) {
                repository.delete(active.get());
                return "withdrawn";
            }
            return "unchanged";
        }
        Proposal p = proposal.get();
        String action = p.replace() && spec.action() != null ? spec.action() : spec.inspectAction();
        Instant now = Instant.now();
        if (active.isPresent()) {
            Recommendation r = active.get();
            if (r.getPriority() == p.priority() && r.getReason().equals(p.reason()) && r.getAction().equals(action)) {
                return "unchanged";
            }
            r.setAction(action);
            r.setPriority(p.priority());
            r.setReason(p.reason());
            r.setRecommendedBy(p.recommendedBy());
            r.setUpdatedAt(now);
            repository.save(r);
            return "updated";
        }
        // Respect a recent dismissal, unless things have got more urgent since.
        Optional<Recommendation> dismissed = repository
                .findFirstByVehicleIdAndComponentAndStatusOrderByUpdatedAtDesc(vehicleId, component, State.DISMISSED);
        if (dismissed.isPresent() && dismissed.get().getUpdatedAt().isAfter(now.minus(cfg.dismissSnooze()))
                && p.priority().ordinal() <= dismissed.get().getPriority().ordinal()) {
            return "unchanged";
        }
        Recommendation r = new Recommendation();
        r.setVehicleId(vehicleId);
        r.setComponent(component);
        r.setAction(action);
        r.setPriority(p.priority());
        r.setReason(p.reason());
        r.setRecommendedBy(p.recommendedBy());
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        repository.save(r);
        return "created";
    }

    /**
     * Moves a recommendation to a new status. DONE records the maintenance and acknowledges the alerts the
     * work resolves; if the part was renewed it also resets its wear in the twin and tells the vehicle.
     */
    @Transactional
    public synchronized Recommendation transition(long id, State target) {
        Recommendation r = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!ACTIVE.contains(r.getStatus()) || r.getStatus() == target) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a " + r.getStatus() + " recommendation cannot be changed to " + target);
        }
        Instant now = Instant.now();
        if (target == State.DONE) {
            Recommendations.Component spec = props.recommendations().components().get(r.getComponent());
            // Only renewing the part starts a new wear lifecycle; an inspection changes nothing physical.
            boolean renewed = r.getAction().equals(spec.action());
            MaintenanceRecord record = new MaintenanceRecord();
            record.setVehicleId(r.getVehicleId());
            record.setComponent(renewed ? r.getComponent() : null);
            record.setCause("RECOMMENDATION");
            record.setType(r.getAction());
            record.setDescription(r.getReason());
            record.setPerformedAt(now);
            r.setMaintenanceRecordId(maintenance.save(record).getId());
            r.setCompletedAt(now);

            if (renewed) {
                twins.resetWear(r.getVehicleId(), r.getComponent(), spec.sensor(), spec.newValue());
                notifyVehicle(r.getVehicleId(), r.getComponent());
            }
            jdbc.queryForList("SELECT id FROM alerts WHERE vehicle_id = ? AND NOT acknowledged AND " + COMPONENT_GROUP + " = ?",
                    Long.class, r.getVehicleId(), r.getComponent()).forEach(alerts::acknowledge);
        }
        r.setStatus(target);
        r.setUpdatedAt(now);
        Recommendation saved = repository.saveAndFlush(r);
        twins.refreshOpenRecommendations(r.getVehicleId(), openCount(r.getVehicleId()));
        return saved;
    }

    public long openCount(long vehicleId) {
        return repository.countByVehicleIdAndStatus(vehicleId, State.OPEN);
    }

    /** fleet/{id}/maintenance tells the vehicle (the simulator) the part is new. Best effort. */
    private void notifyVehicle(long vehicleId, String component) {
        try {
            mqttOutbound.handleMessage(MessageBuilder.withPayload("{\"component\":\"" + component + "\"}")
                    .setHeader(MqttHeaders.TOPIC, "fleet/" + vehicleId + "/maintenance").build());
        } catch (Exception e) {
            log.warn("Could not notify vehicle {} of {} maintenance: {}", vehicleId, component, e.toString());
        }
    }

    // ---- evidence ----

    /** tyre_fl .. tyre_rr are one part ("tyres") as far as maintenance goes. */
    private static final String COMPONENT_GROUP = "CASE WHEN component LIKE 'tyre\\_%' THEN 'tyres' ELSE component END";

    private static Status status(VehicleTwin twin, String component) {
        Status worst = null;
        for (Map.Entry<String, Status> e : twin.getComponents().entrySet()) {
            String group = e.getKey().startsWith("tyre_") ? "tyres" : e.getKey();
            if (group.equals(component)) {
                worst = worst == null ? e.getValue() : worst.worst(e.getValue());
            }
        }
        return worst;
    }

    /** component -> {open CRITICAL alerts, open WARNING alerts} */
    private Map<String, int[]> openAlerts(long vehicleId) {
        Map<String, int[]> out = new HashMap<>();
        jdbc.query("SELECT " + COMPONENT_GROUP + ", severity, count(*) FROM alerts WHERE vehicle_id = ? AND NOT acknowledged GROUP BY 1, 2",
                rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new int[2])["CRITICAL".equals(rs.getString(2)) ? 0 : 1] += rs.getInt(3);
                }, vehicleId);
        return out;
    }

    private Map<String, Integer> recentAnomalies(long vehicleId, Duration window) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query("SELECT " + COMPONENT_GROUP + ", count(*) FROM alerts WHERE vehicle_id = ? AND source = 'ML' AND created_at >= ? GROUP BY 1",
                rs -> {
                    out.put(rs.getString(1), rs.getInt(2));
                }, vehicleId, Timestamp.from(Instant.now().minus(window)));
        return out;
    }

    private Timestamp lastService(long vehicleId, String component) {
        return jdbc.queryForObject("SELECT max(performed_at) FROM maintenance_records WHERE vehicle_id = ? AND component = ?",
                Timestamp.class, vehicleId, component);
    }

    private Long daysSinceService(long vehicleId, String component) {
        Timestamp last = lastService(vehicleId, component);
        return last == null ? null : Duration.between(last.toInstant(), Instant.now()).toDays();
    }

    private Double kmSinceService(long vehicleId, String component) {
        Timestamp last = lastService(vehicleId, component);
        return last == null ? null : jdbc.queryForObject(
                "SELECT coalesce(sum(distance_km), 0) FROM trips WHERE vehicle_id = ? AND started_at >= ?", Double.class, vehicleId, last);
    }
}
