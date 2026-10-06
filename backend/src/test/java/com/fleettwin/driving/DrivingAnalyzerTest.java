package com.fleettwin.driving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fleettwin.config.FleetProperties.Driving;
import com.fleettwin.config.FleetProperties.Fuel;
import com.fleettwin.driving.DrivingAnalyzer.Event;
import com.fleettwin.driving.DrivingAnalyzer.Output;
import com.fleettwin.driving.DrivingAnalyzer.Reading;
import com.fleettwin.driving.DrivingAnalyzer.Trip;
import org.junit.jupiter.api.Test;

class DrivingAnalyzerTest {

    private static final Instant T0 = Instant.parse("2026-10-06T08:00:00Z");

    private final Driving cfg = new Driving(80, 3.0, 2.5, 25, 30, Duration.ofMinutes(2), Duration.ofMinutes(5), 10,
            Map.of("harsh-braking", 1.5, "rapid-acceleration", 1.0, "speeding", 1.5, "sharp-cornering", 1.0,
                    "excessive-idling", 0.5),
            new Driving.SeverityMultipliers(1.5, 2.0), 500);
    private final DrivingAnalyzer analyzer = new DrivingAnalyzer(cfg, new Fuel(300, 3.2, 3, 0.25, 5, 20, 5), 2);
    private final List<Event> events = new ArrayList<>();
    private final List<Trip> trips = new ArrayList<>();
    private double odometer = 1000;
    private double lat = 12.0;

    /** Feeds a reading `seconds` after the start, heading north unless lng is given. */
    private void at(int seconds, double speed, Double accelMin, double lng, double fuel) {
        odometer += speed / 3600.0 * 10;
        lat += speed > 0 ? 0.001 : 0;
        collect(analyzer.accept(new Reading(T0.plusSeconds(seconds), lat, lng, speed, fuel, accelMin, 0.0, odometer)));
    }

    private void at(int seconds, double speed) {
        at(seconds, speed, 0.0, 77.0, 80.0);
    }

    private void collect(Output output) {
        events.addAll(output.events());
        trips.addAll(output.trips());
    }

    @Test
    void harshBrakingSeverityFollowsHowFarOverTheLimit() {
        at(0, 60);
        at(10, 50, -3.1, 77.0, 80);
        at(20, 30, -5.0, 77.0, 80);
        at(30, 30, -2.9, 77.0, 80);

        assertEquals(List.of("HARSH_BRAKING", "HARSH_BRAKING"), events.stream().map(Event::type).toList());
        assertEquals(List.of("LOW", "HIGH"), events.stream().map(Event::severity).toList());
        assertEquals(5.0, events.get(1).value());
    }

    @Test
    void aSpeedingRunIsOneEventWithItsPeak() {
        at(0, 70);
        at(10, 85);
        at(20, 97);
        at(30, 90);
        at(40, 75);

        assertEquals(1, events.size());
        Event e = events.get(0);
        assertEquals("SPEEDING", e.type());
        assertEquals(T0.plusSeconds(10), e.ts());
        assertEquals(97, e.value());
        assertEquals("HIGH", e.severity()); // 97 is more than 20% over 80
    }

    @Test
    void corneringCountsOnlyWhenTakenFastAndOnlyOnce() {
        at(0, 50);
        at(10, 50);
        // turn east: latitude stops changing, longitude starts. Slow driver first.
        lat = 12.5;
        at(20, 15, 0.0, 77.0, 80);
        collect(analyzer.accept(new Reading(T0.plusSeconds(30), lat, 77.001, 15.0, 80.0, 0.0, 0.0, odometer += 0.05)));
        assertTrue(events.isEmpty(), "a turn at 15 km/h is not sharp");

        // back north, then the same turn at 45 km/h; the reading after it must not count again
        at(40, 45);
        at(50, 45);
        collect(analyzer.accept(new Reading(T0.plusSeconds(60), lat, 77.002, 45.0, 80.0, 0.0, 0.0, odometer += 0.1)));
        collect(analyzer.accept(new Reading(T0.plusSeconds(70), lat - 0.001, 77.002, 45.0, 80.0, 0.0, 0.0, odometer += 0.1)));

        assertEquals(List.of("SHARP_CORNERING"), events.stream().map(Event::type).toList());
        assertEquals("HIGH", events.get(0).severity()); // 45 is 1.5x the 30 km/h limit
    }

    @Test
    void idlingIsReportedWhenTheVehicleMovesOffAgain() {
        at(0, 40);
        for (int s = 10; s <= 150; s += 10) {
            at(s, 0);
        }
        assertTrue(events.isEmpty(), "still idling: nothing yet");
        at(160, 30);

        assertEquals(List.of("EXCESSIVE_IDLING"), events.stream().map(Event::type).toList());
        assertEquals(140, events.get(0).value()); // stopped at 10 s, last seen stopped at 150 s
        assertEquals(T0.plusSeconds(10), events.get(0).ts());
    }

    @Test
    void aGapInTelemetryEndsTheTripAndTheScoreUsesPenaltyPer100Km() {
        at(0, 60);
        at(10, 60, -3.2, 77.0, 80);   // harsh braking, LOW: 1.5 points
        at(20, 60, 0.0, 77.0, 79.0);  // 1% of a 300 l tank
        assertTrue(trips.isEmpty());
        at(20 + 600, 60);             // ten minutes later: a new trip

        assertEquals(1, trips.size());
        Trip trip = trips.get(0);
        assertEquals(T0, trip.startedAt());
        assertEquals(T0.plusSeconds(20), trip.endedAt());
        assertEquals(20, trip.durationS());
        assertEquals(2 * 60 / 3600.0 * 10, trip.distanceKm(), 1e-9);
        assertEquals(3.0, trip.fuelUsedL(), 1e-9);
        assertEquals(1, trip.eventsCount());
        assertEquals(1.5, trip.penaltyPoints());
        // under min-score-km (10), so scored as a 10 km trip: 100 - 1.5 * 100 / 10
        assertEquals(85.0, trip.driverScore(), 1e-9);

        collect(analyzer.flush());
        assertEquals(1, trips.size(), "the second trip has no distance yet, so it is dropped");
    }

    @Test
    void standingStillForTheTripGapEndsTheTripWhereItStopped() {
        at(0, 60);
        at(10, 60);
        for (int s = 20; s <= 330; s += 10) {
            at(s, 0);
        }
        assertEquals(1, trips.size());
        assertEquals(T0.plusSeconds(20), trips.get(0).endedAt());
        assertEquals(0, trips.get(0).idleS(), "the parked time is not part of the trip");
    }

    @Test
    void fuelLostWhileParkedIsFlaggedButFuelBurnedWhileDrivingIsNot() {
        at(0, 60, 0.0, 77.0, 80);
        at(10, 60, 0.0, 77.0, 70);    // a 10% fall while moving: bad data perhaps, but not a parked drop
        at(20, 0, 0.0, 77.0, 70);
        at(30, 0, 0.0, 77.0, 58);     // 12% of 300 l gone while standing
        at(40, 60, 0.0, 77.0, 58);
        assertEquals(List.of("FUEL_DROP"), events.stream().map(Event::type).toList());
        assertEquals(36.0, events.get(0).value(), 1e-9);
        assertEquals("HIGH", events.get(0).severity()); // four times the 3% threshold

        at(40 + 3600, 60, 0.0, 77.0, 52);   // overnight gap with 6% missing
        assertEquals(2, events.size());
        assertEquals("MEDIUM", events.get(1).severity());
        // the trip that just ended burned the 10%, not the stolen 12%
        assertEquals(30.0, trips.get(0).fuelUsedL(), 1e-9);
        assertEquals(0, trips.get(0).eventsCount(), "fuel drops are not driving events");
    }

    @Test
    void scoreFormula() {
        assertEquals(100.0, DrivingAnalyzer.score(0, 50, 10));
        assertEquals(70.0, DrivingAnalyzer.score(30, 100, 10));
        assertEquals(0.0, DrivingAnalyzer.score(500, 100, 10));
    }
}
