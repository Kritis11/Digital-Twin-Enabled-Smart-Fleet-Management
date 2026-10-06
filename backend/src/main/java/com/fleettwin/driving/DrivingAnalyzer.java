package com.fleettwin.driving;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fleettwin.config.FleetProperties;
import com.fleettwin.twin.TwinService;

/**
 * Turns one vehicle's telemetry, fed in time order, into driving events and trips. Holds only that
 * vehicle's running state and touches nothing else, so the same code analyses the live stream and
 * replays history.
 *
 * <p>A trip starts when the vehicle moves and ends when telemetry stops, or the vehicle stands still,
 * for longer than {@code fleet.driving.trip-gap}.
 */
public class DrivingAnalyzer {

    public record Reading(Instant ts, Double lat, Double lng, Double speed, Double fuelLevel,
                          Double accelMin, Double accelMax, Double odometerKm) {
    }

    public record Event(String type, String severity, Instant ts, Double lat, Double lng, double value, String detail) {
    }

    public record Trip(Instant startedAt, Instant endedAt, Double startLat, Double startLng, Double endLat, Double endLng,
                       double distanceKm, long durationS, double avgSpeedKmh, double maxSpeedKmh, long idleS,
                       double fuelUsedL, int eventsCount, double penaltyPoints, double driverScore) {
    }

    public record Output(List<Event> events, List<Trip> trips) {
    }

    private final FleetProperties.Driving cfg;
    private final FleetProperties.Fuel fuel;
    private final double movingSpeedKmh;

    private Reading prev;
    private Double prevHeading;
    private Instant lastCornering;
    private Reading speedingStart;
    private double speedingMax;
    private Reading idleStart;

    // open trip
    private Reading tripStart;
    private double distanceKm;
    private double maxSpeed;
    private long idleS;
    private double fuelUsedPercent;
    private int eventsCount;
    private double penalty;

    public DrivingAnalyzer(FleetProperties.Driving cfg, FleetProperties.Fuel fuel, double movingSpeedKmh) {
        this.cfg = cfg;
        this.fuel = fuel;
        this.movingSpeedKmh = movingSpeedKmh;
    }

    /** 100 minus penalty points per 100 km, floored at 0. Short distances are scored as if minScoreKm long. */
    public static double score(double penaltyPoints, double distanceKm, double minScoreKm) {
        return Math.max(0.0, 100.0 - penaltyPoints * 100.0 / Math.max(distanceKm, minScoreKm));
    }

    public Instant lastSeen() {
        return prev == null ? null : prev.ts();
    }

    public Output accept(Reading r) {
        Output out = new Output(new ArrayList<>(), new ArrayList<>());
        if (r.speed() == null) {
            return out;
        }
        if (prev != null) {
            double dt = Duration.between(prev.ts(), r.ts()).toMillis() / 1000.0;
            if (dt <= 0) {
                return out; // late or duplicate reading
            }
            boolean gap = dt > cfg.tripGap().toSeconds();
            boolean fuelDropped = fuelDrop(r, gap, out);
            if (gap) {
                closeTrip(prev, out);
                prevHeading = null;
            } else if (tripStart != null) {
                drive(r, dt, fuelDropped, out);
            }
        }
        if (tripStart == null && moving(r)) {
            startTrip(r);
        }
        prev = r;
        return out;
    }

    /** Ends the open trip, if any: telemetry has stopped. */
    public Output flush() {
        Output out = new Output(new ArrayList<>(), new ArrayList<>());
        if (prev != null) {
            closeTrip(prev, out);
        }
        return out;
    }

    /**
     * Fuel that disappears while the vehicle is parked (standing still on both readings, or across a gap
     * in telemetry) was not burned by driving: possible theft or a leak. Not a driver event, so it does
     * not count towards the trip's events or score.
     */
    private boolean fuelDrop(Reading r, boolean gap, Output out) {
        if (r.fuelLevel() == null || prev.fuelLevel() == null || !(gap || (!moving(prev) && !moving(r)))) {
            return false;
        }
        double drop = prev.fuelLevel() - r.fuelLevel();
        if (drop < fuel.dropPercent()) {
            return false;
        }
        double litres = drop / 100.0 * fuel.tankLitres();
        out.events().add(new Event("FUEL_DROP", ratioSeverity(drop / fuel.dropPercent(), 2, 4), r.ts(), r.lat(), r.lng(), litres,
                String.format(Locale.ROOT, "Fuel fell by %.0f l (%.1f%% of the tank) while parked: possible theft or leak", litres, drop)));
        return true;
    }

    private void drive(Reading r, double dt, boolean fuelDropped, Output out) {
        Double odo = r.odometerKm() != null && prev.odometerKm() != null ? r.odometerKm() - prev.odometerKm() : null;
        double km = odo != null && odo >= 0 ? odo : haversineKm(prev, r);
        distanceKm += km;
        maxSpeed = Math.max(maxSpeed, r.speed());
        if (!fuelDropped && r.fuelLevel() != null && prev.fuelLevel() != null && prev.fuelLevel() > r.fuelLevel()) {
            fuelUsedPercent += prev.fuelLevel() - r.fuelLevel(); // a rise is a refuel, not negative use
        }

        // harsh braking / rapid acceleration: reported peak if present, else derived from closely spaced speeds
        Double derived = dt <= 10 ? (r.speed() - prev.speed()) / 3.6 / dt : null;
        Double braking = r.accelMin() != null ? r.accelMin() : derived;
        Double accelerating = r.accelMax() != null ? r.accelMax() : derived;
        if (braking != null && -braking >= cfg.harshBrakeMs2()) {
            event(out, "HARSH_BRAKING", ratioSeverity(-braking / cfg.harshBrakeMs2(), 1.3, 1.6), r, -braking,
                    String.format(Locale.ROOT, "Braked at %.1f m/s² (limit %.1f)", -braking, cfg.harshBrakeMs2()));
        }
        if (accelerating != null && accelerating >= cfg.rapidAccelMs2()) {
            event(out, "RAPID_ACCELERATION", ratioSeverity(accelerating / cfg.rapidAccelMs2(), 1.3, 1.6), r, accelerating,
                    String.format(Locale.ROOT, "Accelerated at %.1f m/s² (limit %.1f)", accelerating, cfg.rapidAccelMs2()));
        }

        // sharp cornering: a large heading change taken without slowing down
        if (km >= 0.005 && r.lat() != null && prev.lat() != null) {
            double heading = TwinService.bearing(prev.lat(), prev.lng(), r.lat(), r.lng());
            if (prevHeading != null && dt <= 60) {
                double turn = Math.abs(((heading - prevHeading + 540) % 360) - 180);
                // The turn can straddle two readings; the slower of them is the speed it was taken at.
                double speed = Math.min(prev.speed(), r.speed());
                boolean cooledDown = lastCornering == null
                        || Duration.between(lastCornering, r.ts()).toSeconds() > 60;
                if (turn >= cfg.corneringDegrees() && speed >= cfg.corneringSpeedKmh() && cooledDown) {
                    lastCornering = r.ts();
                    event(out, "SHARP_CORNERING", ratioSeverity(speed / cfg.corneringSpeedKmh(), 1.25, 1.5), r, speed,
                            String.format(Locale.ROOT, "Turned %.0f° at %.0f km/h (limit %.0f)", turn, speed, cfg.corneringSpeedKmh()));
                }
            }
            prevHeading = heading;
        }

        // speeding: one event per continuous run above the limit, raised when the run ends
        if (r.speed() > cfg.speedLimitKmh()) {
            if (speedingStart == null) {
                speedingStart = r;
                speedingMax = r.speed();
            }
            speedingMax = Math.max(speedingMax, r.speed());
        } else {
            endSpeeding(prev, out);
        }

        // idling: engine on, not moving
        if (moving(r)) {
            endIdle(prev, out);
        } else {
            if (idleStart == null) {
                idleStart = r;
            }
            if (Duration.between(idleStart.ts(), r.ts()).compareTo(cfg.tripGap()) >= 0) {
                // Standing this long means parked: the trip ended when the vehicle stopped.
                Reading stopped = idleStart;
                idleStart = null;
                closeTrip(stopped, out);
            }
        }
    }

    private void endSpeeding(Reading last, Output out) {
        if (speedingStart == null) {
            return;
        }
        long seconds = Duration.between(speedingStart.ts(), last.ts()).toSeconds();
        event(out, "SPEEDING", ratioSeverity(speedingMax / cfg.speedLimitKmh(), 1.1, 1.2), speedingStart, speedingMax,
                String.format(Locale.ROOT, "Reached %.0f km/h in a %.0f km/h limit for %d s", speedingMax, cfg.speedLimitKmh(), seconds));
        speedingStart = null;
    }

    private void endIdle(Reading last, Output out) {
        if (idleStart == null) {
            return;
        }
        long seconds = Duration.between(idleStart.ts(), last.ts()).toSeconds();
        idleS += seconds;
        long limit = cfg.idleAfter().toSeconds();
        if (seconds >= limit) {
            event(out, "EXCESSIVE_IDLING", ratioSeverity((double) seconds / limit, 2, 3), idleStart, seconds,
                    String.format(Locale.ROOT, "Idled for %d s (limit %d s)", seconds, limit));
        }
        idleStart = null;
    }

    private void startTrip(Reading r) {
        tripStart = r;
        distanceKm = 0;
        maxSpeed = r.speed();
        idleS = 0;
        fuelUsedPercent = 0;
        eventsCount = 0;
        penalty = 0;
    }

    private void closeTrip(Reading end, Output out) {
        if (tripStart == null) {
            return;
        }
        endSpeeding(end, out);
        endIdle(end, out);
        long duration = Duration.between(tripStart.ts(), end.ts()).toSeconds();
        if (distanceKm >= 0.1 && duration > 0) { // ignore a vehicle that merely twitched
            out.trips().add(new Trip(tripStart.ts(), end.ts(), tripStart.lat(), tripStart.lng(), end.lat(), end.lng(),
                    distanceKm, duration, distanceKm / (duration / 3600.0), maxSpeed, idleS,
                    fuelUsedPercent / 100.0 * fuel.tankLitres(), eventsCount, penalty,
                    score(penalty, distanceKm, cfg.minScoreKm())));
        }
        tripStart = null;
    }

    private void event(Output out, String type, String severity, Reading at, double value, String detail) {
        out.events().add(new Event(type, severity, at.ts(), at.lat(), at.lng(), value, detail));
        eventsCount++;
        // weights are keyed harsh-braking, rapid-acceleration, ... in application.yml
        double weight = cfg.weights().getOrDefault(type.toLowerCase(Locale.ROOT).replace('_', '-'), 0.0);
        penalty += weight * switch (severity) {
            case "HIGH" -> cfg.severityMultipliers().high();
            case "MEDIUM" -> cfg.severityMultipliers().medium();
            default -> 1.0;
        };
    }

    private boolean moving(Reading r) {
        return r.speed() > movingSpeedKmh;
    }

    private static String ratioSeverity(double ratio, double medium, double high) {
        return ratio >= high ? "HIGH" : ratio >= medium ? "MEDIUM" : "LOW";
    }

    private static double haversineKm(Reading a, Reading b) {
        if (a.lat() == null || b.lat() == null) {
            return 0;
        }
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6371.0 * Math.asin(Math.sqrt(h));
    }
}
