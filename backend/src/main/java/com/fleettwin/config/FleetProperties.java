package com.fleettwin.config;

import java.time.Duration;
import java.util.List;

import com.fleettwin.twin.Rule;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The {@code fleet.twin}, {@code fleet.rules} and {@code fleet.ml} blocks of application.yml. */
@ConfigurationProperties(prefix = "fleet")
public record FleetProperties(Twin twin, List<Rule> rules, Ml ml) {

    public record Twin(Duration offlineAfter, double movingSpeedKmh, int maintenanceSummarySize) {
    }

    public record Ml(boolean enabled, String url, Duration timeout, Duration window, double anomalyCriticalScore) {
    }
}
