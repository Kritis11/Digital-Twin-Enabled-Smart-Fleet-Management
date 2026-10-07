package com.fleettwin.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fleettwin.twin.VehicleTwin;

/** Decides whether a vehicle is fit to be sent on a route. Pure, so every exclusion can be explained and tested. */
public final class RouteEligibility {

    private RouteEligibility() {
    }

    /**
     * The reasons this vehicle must not be assigned; empty if it may be.
     *
     * @param urgentActions      actions of its open or scheduled URGENT recommendations
     * @param openCriticalAlerts unacknowledged CRITICAL alerts
     * @param needsPosition      true when routes start from where the vehicle is rather than from a depot
     */
    public static List<String> exclusions(VehicleTwin twin, List<String> urgentActions, int openCriticalAlerts,
                                          boolean needsPosition, double minRulDays) {
        List<String> reasons = new ArrayList<>();
        if (needsPosition && (twin.getLat() == null || twin.getLng() == null)) {
            reasons.add("Position unknown (no telemetry yet)");
        }
        for (String action : urgentActions) {
            reasons.add("Urgent recommendation outstanding: " + action);
        }
        if (openCriticalAlerts > 0) {
            reasons.add(openCriticalAlerts + " open critical alert" + (openCriticalAlerts == 1 ? "" : "s"));
        }
        twin.getRul().forEach((component, rul) -> {
            if (rul.days() < minRulDays) {
                reasons.add(String.format(Locale.ROOT, "%s predicted to fail in %.1f days (minimum for a route is %s)",
                        component.substring(0, 1).toUpperCase(Locale.ROOT) + component.substring(1), rul.days(),
                        minRulDays == Math.rint(minRulDays) ? String.valueOf((long) minRulDays) : String.valueOf(minRulDays)));
            }
        });
        return reasons;
    }

    /** Cost of each km on this vehicle relative to the best one: 1 for the most efficient, best-scoring vehicle. */
    public static double costFactor(double kmPerLitre, double bestKmPerLitre, double driverScore,
                                    double fuelWeight, double driverScoreWeight) {
        return 1 + fuelWeight * (bestKmPerLitre / kmPerLitre - 1) + driverScoreWeight * (100 - driverScore) / 100;
    }
}
