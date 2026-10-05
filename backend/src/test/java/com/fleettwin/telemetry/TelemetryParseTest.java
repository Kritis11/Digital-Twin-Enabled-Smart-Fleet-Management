package com.fleettwin.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class TelemetryParseTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mapsSnakeCasePayloadAndTakesVehicleIdFromTopic() throws Exception {
        String json = """
                {"vehicle_id": 99, "ts": "2026-10-05T10:00:00Z", "lat": 12.97, "engine_temp": 118.5,
                 "tyre_pressure_fl": 22.1, "dtc_codes": ["P0217"], "injected_fault": "overheating",
                 "some_future_field": 1}
                """;

        Telemetry t = TelemetryIngestService.parse("fleet/3/telemetry", json, mapper);

        assertEquals(3L, t.getVehicleId());
        assertEquals(Instant.parse("2026-10-05T10:00:00Z"), t.getTs());
        assertEquals(118.5, t.getEngineTemp());
        assertEquals(22.1, t.getTyrePressureFl());
        assertEquals(List.of("P0217"), t.getDtcCodes());
        assertEquals("overheating", t.getInjectedFault());
    }

    @Test
    void rejectsUnexpectedTopic() {
        assertThrows(IllegalArgumentException.class,
                () -> TelemetryIngestService.parse("fleet/abc/telemetry", "{}", mapper));
    }
}
