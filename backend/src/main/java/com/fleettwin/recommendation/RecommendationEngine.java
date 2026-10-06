package com.fleettwin.recommendation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fleettwin.config.FleetProperties.Recommendations;
import com.fleettwin.recommendation.Recommendation.Priority;
import com.fleettwin.twin.Status;
import com.fleettwin.twin.VehicleTwin;

/**
 * The recommendation rules. Pure: evidence in, at most one proposal out, no I/O. Every threshold comes
 * from {@code fleet.recommendations} in application.yml, and every rule that fires adds a sentence to
 * the reason, so a recommendation always says exactly why it exists.
 */
public final class RecommendationEngine {

    /** Everything known about one component of one vehicle. Null means "not known", not zero. */
    public record Evidence(Status status, VehicleTwin.Rul rul, int openCriticalAlerts, int openWarningAlerts,
                           int recentAnomalies, Long daysSinceService, Double kmSinceService) {
    }

    /** replace: the wear prediction fired, so the part itself needs renewing; otherwise an inspection is enough. */
    public record Proposal(Priority priority, LocalDate recommendedBy, String reason, boolean replace) {
    }

    private RecommendationEngine() {
    }

    public static Optional<Proposal> evaluate(Evidence e, Recommendations.Component component, Recommendations cfg,
                                              LocalDate today) {
        Recommendations.Rules rules = cfg.rules();
        List<String> reasons = new ArrayList<>();
        Priority priority = null;
        Integer rulDueDays = null;

        // 1. Remaining useful life
        if (e.rul() != null && e.rul().days() <= rules.mediumRulDays()) {
            double days = e.rul().days();
            priority = max(priority, days <= rules.urgentRulDays() ? Priority.URGENT
                    : days <= rules.highRulDays() ? Priority.HIGH : Priority.MEDIUM);
            reasons.add(String.format(Locale.ROOT, "Predicted to fail in %s (likely range %.0f–%.0f days, confidence %.0f%%)",
                    days < 1 ? "under a day" : Math.round(days) == 1 ? "about 1 day" : String.format(Locale.ROOT, "about %.0f days", days),
                    e.rul().lower(), e.rul().upper(), e.rul().confidence() * 100));
            rulDueDays = (int) Math.floor(e.rul().lower());
        }
        // 2. Current status
        if (e.status() == Status.CRITICAL) {
            priority = max(priority, Priority.URGENT);
            reasons.add("Current status is CRITICAL");
        } else if (e.status() == Status.WARNING) {
            priority = max(priority, Priority.MEDIUM);
            reasons.add("Current status is WARNING");
        }
        // 3. Open alerts
        if (e.openCriticalAlerts() >= rules.highOpenCriticalAlerts()) {
            priority = max(priority, Priority.HIGH);
            reasons.add(plural(e.openCriticalAlerts(), "open critical alert"));
        } else if (e.openWarningAlerts() > 0) {
            priority = max(priority, Priority.MEDIUM);
            reasons.add(plural(e.openWarningAlerts(), "open warning alert"));
        }
        // 4. Anomaly history
        if (e.recentAnomalies() >= rules.mediumAnomalies()) {
            priority = max(priority, Priority.MEDIUM);
            reasons.add(String.format(Locale.ROOT, "%d ML anomalies in the last %d days",
                    e.recentAnomalies(), cfg.anomalyWindow().toDays()));
        }
        // 5. Time and distance since the last service
        if (component.serviceIntervalDays() != null && e.daysSinceService() != null
                && e.daysSinceService() >= component.serviceIntervalDays()) {
            priority = max(priority, Priority.LOW);
            reasons.add(String.format(Locale.ROOT, "Last serviced %d days ago (interval %d days)",
                    e.daysSinceService(), component.serviceIntervalDays()));
        }
        if (component.serviceIntervalKm() != null && e.kmSinceService() != null
                && e.kmSinceService() >= component.serviceIntervalKm()) {
            priority = max(priority, Priority.LOW);
            reasons.add(String.format(Locale.ROOT, "%,.0f km since the last service (interval %,.0f km)",
                    e.kmSinceService(), component.serviceIntervalKm()));
        }

        if (priority == null) {
            return Optional.empty();
        }
        int dueDays = switch (priority) {
            case URGENT -> cfg.dueDays().urgent();
            case HIGH -> cfg.dueDays().high();
            case MEDIUM -> cfg.dueDays().medium();
            case LOW -> cfg.dueDays().low();
        };
        // Never recommend a date later than the early end of the failure prediction.
        if (rulDueDays != null) {
            dueDays = Math.max(0, Math.min(dueDays, rulDueDays));
        }
        return Optional.of(new Proposal(priority, today.plusDays(dueDays), String.join("; ", reasons) + ".", rulDueDays != null));
    }

    private static Priority max(Priority a, Priority b) {
        return a == null || b.ordinal() > a.ordinal() ? b : a;
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
