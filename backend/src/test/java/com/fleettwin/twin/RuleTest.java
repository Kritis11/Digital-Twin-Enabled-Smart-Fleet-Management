package com.fleettwin.twin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RuleTest {

    private final List<Rule> rules = List.of(
            new Rule("engine", "engineTemp", 105.0, 115.0, null, null),
            new Rule("engine", "vibration", 1.5, 2.5, null, null),
            new Rule("tyre_fl", "tyrePressureFl", null, null, 28.0, 24.0));

    @Test
    void appliesAboveAndBelowLimits() {
        var healthy = Rule.evaluateAll(rules, Map.of("engineTemp", 105.0, "vibration", 0.4, "tyrePressureFl", 28.0));
        assertEquals(Status.OK, healthy.get("engine").status());
        assertEquals(Status.OK, healthy.get("tyre_fl").status());

        var faulty = Rule.evaluateAll(rules, Map.of("engineTemp", 105.1, "vibration", 0.4, "tyrePressureFl", 23.9));
        assertEquals(Status.WARNING, faulty.get("engine").status());
        assertEquals(Status.CRITICAL, faulty.get("tyre_fl").status());
        assertEquals("tyre_fl CRITICAL: tyrePressureFl = 23.9", faulty.get("tyre_fl").message());
    }

    @Test
    void componentTakesWorstRuleAndIgnoresMissingMetrics() {
        var findings = Rule.evaluateAll(rules, Map.of("engineTemp", 110.0, "vibration", 3.0));
        assertEquals(Status.CRITICAL, findings.get("engine").status());
        assertEquals("engine CRITICAL: vibration = 3.0", findings.get("engine").message());
        assertEquals(Status.OK, findings.get("tyre_fl").status());
    }

    @Test
    void bearingPointsTheRightWay() {
        assertEquals(0, TwinService.bearing(12, 77, 13, 77), 0.01);
        assertEquals(90, TwinService.bearing(0, 77, 0, 78), 0.01);
        assertEquals(180, TwinService.bearing(13, 77, 12, 77), 0.01);
    }
}
