package com.fleettwin.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleettwin.driving.DrivingService;
import com.fleettwin.twin.TwinService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TelemetryIngestService {

    private static final Pattern TOPIC = Pattern.compile("fleet/(\\d{1,18})/telemetry");

    private final TelemetryRepository repository;
    private final ObjectMapper objectMapper;
    private final TwinService twinService;
    private final DrivingService drivingService;
    // How long after the vehicle took a reading it was in the database, and in the twin. Both at
    // /actuator/prometheus as fleet_telemetry_*_delay_seconds (count, sum, max and quantiles).
    private final Timer storedDelay;
    private final Timer twinDelay;
    // only touched on the MQTT subscriber thread
    private long twinFailures;
    private long twinFailureLoggedAt = System.nanoTime() - Duration.ofMinutes(1).toNanos();

    public TelemetryIngestService(TelemetryRepository repository, ObjectMapper objectMapper, TwinService twinService,
                                  DrivingService drivingService, MeterRegistry metrics) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.twinService = twinService;
        this.drivingService = drivingService;
        this.storedDelay = delayTimer("fleet.telemetry.stored.delay", "Reading taken to row stored", metrics);
        this.twinDelay = delayTimer("fleet.telemetry.twin.delay", "Reading taken to twin updated", metrics);
    }

    private static Timer delayTimer(String name, String description, MeterRegistry metrics) {
        return Timer.builder(name).description(description).publishPercentiles(0.5, 0.95, 0.99).register(metrics);
    }

    private static void record(Timer timer, Telemetry telemetry) {
        Duration delay = Duration.between(telemetry.getTs(), Instant.now());
        timer.record(delay.isNegative() ? Duration.ZERO : delay);   // a vehicle clock slightly ahead of ours
    }

    /** Persists one MQTT message. A bad message is logged and dropped so it can't stall the subscriber. */
    public void ingest(String topic, String json) {
        Telemetry telemetry;
        try {
            telemetry = repository.save(parse(topic, json, objectMapper));
            record(storedDelay, telemetry);
        } catch (Exception e) {
            log.warn("Dropped telemetry on topic {}: {}", topic, e.toString());
            return;
        }
        // The row is stored; a twin failure (e.g. Redis down) must not look like lost telemetry.
        try {
            twinService.onTelemetry(telemetry);
            record(twinDelay, telemetry);
        } catch (Exception e) {
            twinFailed(telemetry, e);
        }
        try {
            drivingService.onTelemetry(telemetry);
        } catch (Exception e) {
            log.warn("Driving analysis failed for vehicle {}: {}", telemetry.getVehicleId(), e.toString());
        }
    }

    /** With Redis down every reading fails here, so this is logged at most every 10 seconds, with a count. */
    private void twinFailed(Telemetry telemetry, Exception e) {
        twinFailures++;
        long now = System.nanoTime();
        if (now - twinFailureLoggedAt > Duration.ofSeconds(10).toNanos()) {
            log.warn("Twin update failed for vehicle {} ({} failures since the last report; telemetry is still being stored): {}",
                    telemetry.getVehicleId(), twinFailures, e.toString());
            twinFailureLoggedAt = now;
            twinFailures = 0;
        }
    }

    static Telemetry parse(String topic, String json, ObjectMapper mapper) throws Exception {
        Matcher m = TOPIC.matcher(topic == null ? "" : topic);
        if (!m.matches()) {
            throw new IllegalArgumentException("unexpected topic");
        }
        return mapper.readValue(json, TelemetryPayload.class).toEntity(Long.parseLong(m.group(1)));
    }
}
