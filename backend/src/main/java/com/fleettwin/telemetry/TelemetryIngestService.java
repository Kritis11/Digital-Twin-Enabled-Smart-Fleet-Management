package com.fleettwin.telemetry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryIngestService {

    private static final Pattern TOPIC = Pattern.compile("fleet/(\\d{1,18})/telemetry");

    private final TelemetryRepository repository;
    private final ObjectMapper objectMapper;

    /** Persists one MQTT message. A bad message is logged and dropped so it can't stall the subscriber. */
    public void ingest(String topic, String json) {
        try {
            repository.save(parse(topic, json, objectMapper));
        } catch (Exception e) {
            log.warn("Dropped telemetry on topic {}: {}", topic, e.getMessage());
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
