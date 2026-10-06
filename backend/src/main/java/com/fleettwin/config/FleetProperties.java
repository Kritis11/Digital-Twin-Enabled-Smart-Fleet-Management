package com.fleettwin.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fleettwin.twin.Rule;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code fleet.*} blocks of application.yml that are bound as a whole. */
@ConfigurationProperties(prefix = "fleet")
public record FleetProperties(Twin twin, List<Rule> rules, Ml ml, Driving driving, Fuel fuel) {

    public record Twin(Duration offlineAfter, double movingSpeedKmh, int maintenanceSummarySize) {
    }

    public record Ml(boolean enabled, String url, Duration timeout, Duration window, double anomalyCriticalScore) {
    }

    public record Driving(double speedLimitKmh, double harshBrakeMs2, double rapidAccelMs2, double corneringDegrees,
                          double corneringSpeedKmh, Duration idleAfter, Duration tripGap, double minScoreKm,
                          Map<String, Double> weights, SeverityMultipliers severityMultipliers, int maxResults) {

        public record SeverityMultipliers(double medium, double high) {
        }
    }

    public record Fuel(double tankLitres, double idleLitresPerHour, double dropPercent, double lowEfficiencyFraction,
                       double minTripKm, int baselineTrips, int minBaselineTrips) {
    }
}
