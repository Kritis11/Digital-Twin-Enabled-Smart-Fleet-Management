package com.fleettwin.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.fleettwin.config.FleetProperties.Recommendations;
import com.fleettwin.recommendation.Recommendation.Priority;
import com.fleettwin.recommendation.RecommendationEngine.Evidence;
import com.fleettwin.recommendation.RecommendationEngine.Proposal;
import com.fleettwin.twin.Status;
import com.fleettwin.twin.VehicleTwin.Rul;
import org.junit.jupiter.api.Test;

class RecommendationEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private final Recommendations.Component brakes = new Recommendations.Component("Replace brake pads", "Inspect brakes", "brakePadWear", 0.0, 60, 15000.0);
    private final Recommendations cfg = new Recommendations(Duration.ofDays(7), Duration.ofDays(7), Map.of("brakes", brakes),
            new Recommendations.Rules(3, 10, 21, 1, 3), new Recommendations.DueDays(1, 7, 21, 45));

    private Proposal evaluate(Evidence e) {
        return RecommendationEngine.evaluate(e, brakes, cfg, TODAY).orElse(null);
    }

    private static Rul rul(double days, double lower, double upper) {
        return new Rul(days, lower, upper, 0.8, Instant.EPOCH);
    }

    @Test
    void nothingToSayAboutAHealthyPart() {
        assertEquals(null, evaluate(new Evidence(Status.OK, rul(40, 35, 45), 0, 0, 2, 10L, 3000.0)));
        assertEquals(null, evaluate(new Evidence(null, null, 0, 0, 0, null, null)), "unknown is not evidence");
    }

    @Test
    void rulSetsPriorityAndCapsTheDueDateAtItsLowerBound() {
        Proposal p = evaluate(new Evidence(Status.OK, rul(9, 6.4, 12), 0, 0, 0, 10L, 3000.0));
        assertEquals(Priority.HIGH, p.priority());
        assertEquals(TODAY.plusDays(6), p.recommendedBy(), "HIGH allows 7 days, but it may fail in 6");
        assertEquals("Predicted to fail in about 9 days (likely range 6–12 days, confidence 80%).", p.reason());
        assertTrue(p.replace(), "a wear prediction means the part needs renewing");
        assertTrue(evaluate(new Evidence(Status.OK, rul(1.2, 0, 3), 0, 0, 0, null, null)).reason().startsWith("Predicted to fail in about 1 day ("));

        assertEquals(Priority.URGENT, evaluate(new Evidence(Status.OK, rul(0.4, 0, 2), 0, 0, 0, null, null)).priority());
        assertEquals(TODAY, evaluate(new Evidence(Status.OK, rul(0.4, 0, 2), 0, 0, 0, null, null)).recommendedBy());
        assertEquals(Priority.MEDIUM, evaluate(new Evidence(Status.OK, rul(20, 17, 23), 0, 0, 0, null, null)).priority());
    }

    @Test
    void theMostPressingRuleWinsAndEveryRuleThatFiredIsInTheReason() {
        Proposal p = evaluate(new Evidence(Status.WARNING, rul(15, 12, 18), 2, 1, 4, 75L, 16000.0));
        assertEquals(Priority.HIGH, p.priority(), "open critical alerts outrank the MEDIUM rules");
        assertEquals("Predicted to fail in about 15 days (likely range 12–18 days, confidence 80%); "
                + "Current status is WARNING; 2 open critical alerts; 4 ML anomalies in the last 7 days; "
                + "Last serviced 75 days ago (interval 60 days); 16,000 km since the last service (interval 15,000 km).",
                p.reason());
        assertEquals(TODAY.plusDays(7), p.recommendedBy());
    }

    @Test
    void criticalStatusIsUrgentAndAnOverdueServiceAloneIsLow() {
        Proposal critical = evaluate(new Evidence(Status.CRITICAL, null, 0, 0, 0, null, null));
        assertEquals(Priority.URGENT, critical.priority());
        assertTrue(!critical.replace(), "without a wear prediction an inspection is recommended");
        assertEquals(TODAY.plusDays(1), critical.recommendedBy());

        Proposal overdue = evaluate(new Evidence(Status.OK, null, 0, 0, 0, 61L, 100.0));
        assertEquals(Priority.LOW, overdue.priority());
        assertEquals(TODAY.plusDays(45), overdue.recommendedBy());
        assertTrue(overdue.reason().startsWith("Last serviced 61 days ago"));

        assertEquals(Priority.MEDIUM, evaluate(new Evidence(Status.OK, null, 0, 1, 0, null, null)).priority());
    }
}
