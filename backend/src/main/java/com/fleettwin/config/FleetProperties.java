package com.fleettwin.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fleettwin.twin.Rule;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code fleet.*} blocks of application.yml that are bound as a whole. */
@ConfigurationProperties(prefix = "fleet")
public record FleetProperties(Twin twin, List<Rule> rules, Ml ml, Driving driving, Fuel fuel,
                              Recommendations recommendations, Routes routes, Utilisation utilisation) {

    /**
     * Route optimisation. A vehicle is left out when a part's remaining useful life is under minRulDays
     * (or it has an urgent recommendation or an open critical alert). Among the rest, each km costs
     * 1 + fuelWeight x (best km/l / own km/l - 1) + driverScoreWeight x (100 - driver score) / 100.
     */
    public record Routes(double minRulDays, double fuelWeight, double driverScoreWeight, double defaultKmPerLitre,
                         int balance, double solverSeconds, Duration timeout, int defaultServiceMinutes, int maxStops) {
    }

    /** Utilisation = hours on trips / (days x workingHoursPerDay). */
    public record Utilisation(double workingHoursPerDay, double underUsedBelow, double overUsedAbove) {
    }

    public record Twin(Duration offlineAfter, double movingSpeedKmh, int maintenanceSummarySize) {
    }

    public record Ml(boolean enabled, String url, Duration timeout, Duration window, double anomalyCriticalScore,
                     List<String> rulComponents) {
    }

    public record Driving(double speedLimitKmh, double harshBrakeMs2, double rapidAccelMs2, double corneringDegrees,
                          double corneringSpeedKmh, Duration idleAfter, Duration tripGap, double minScoreKm,
                          Map<String, Double> weights, SeverityMultipliers severityMultipliers, int maxResults,
                          Duration twinPeriod) {

        public record SeverityMultipliers(double medium, double high) {
        }
    }

    public record Recommendations(Duration anomalyWindow, Duration dismissSnooze, Map<String, Component> components,
                                  Rules rules, DueDays dueDays) {

        /**
         * action is recommended when the part is predicted to wear out, inspectAction when the evidence is
         * only alerts, anomalies, status or a service interval. sensor / newValue: the wear reading to reset
         * in the twin when the replacement is done (parts without wear tracking leave them out).
         */
        public record Component(String action, String inspectAction, String sensor, Double newValue,
                                Integer serviceIntervalDays, Double serviceIntervalKm) {
        }

        public record Rules(double urgentRulDays, double highRulDays, double mediumRulDays,
                            int highOpenCriticalAlerts, int mediumAnomalies) {
        }

        public record DueDays(int urgent, int high, int medium, int low) {
        }
    }

    public record Fuel(double tankLitres, double idleLitresPerHour, double dropPercent, double lowEfficiencyFraction,
                       double minTripKm, int baselineTrips, int minBaselineTrips) {
    }
}
