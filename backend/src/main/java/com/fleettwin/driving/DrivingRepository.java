package com.fleettwin.driving;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fleettwin.driving.DrivingAnalyzer.Event;
import com.fleettwin.driving.DrivingAnalyzer.Reading;
import com.fleettwin.driving.DrivingAnalyzer.Trip;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All SQL for driving events and trips. */
@Repository
@RequiredArgsConstructor
public class DrivingRepository {

    public record EventRow(long id, long vehicleId, String type, String severity, OffsetDateTime ts,
                           Double lat, Double lng, double value, String detail) {
    }

    public record TripRow(long id, long vehicleId, OffsetDateTime startedAt, OffsetDateTime endedAt,
                          Double startLat, Double startLng, Double endLat, Double endLng, double distanceKm,
                          long durationS, double avgSpeedKmh, double maxSpeedKmh, long idleS, double fuelUsedL,
                          int eventsCount, double penaltyPoints, double driverScore) {
    }

    private final JdbcTemplate jdbc;

    public void insertEvents(long vehicleId, List<Event> events) {
        jdbc.batchUpdate(
                "INSERT INTO driving_events (vehicle_id, type, severity, ts, lat, lng, value, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                events, 500, (ps, e) -> {
                    ps.setLong(1, vehicleId);
                    ps.setString(2, e.type());
                    ps.setString(3, e.severity());
                    ps.setTimestamp(4, Timestamp.from(e.ts()));
                    ps.setObject(5, e.lat());
                    ps.setObject(6, e.lng());
                    ps.setDouble(7, e.value());
                    ps.setString(8, e.detail());
                });
    }

    public void insertTrip(long vehicleId, Trip t) {
        jdbc.update("""
                INSERT INTO trips (vehicle_id, started_at, ended_at, start_lat, start_lng, end_lat, end_lng, distance_km,
                                   duration_s, avg_speed_kmh, max_speed_kmh, idle_s, fuel_used_l, events_count,
                                   penalty_points, driver_score)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                vehicleId, Timestamp.from(t.startedAt()), Timestamp.from(t.endedAt()), t.startLat(), t.startLng(),
                t.endLat(), t.endLng(), t.distanceKm(), t.durationS(), t.avgSpeedKmh(), t.maxSpeedKmh(), t.idleS(),
                t.fuelUsedL(), t.eventsCount(), t.penaltyPoints(), t.driverScore());
    }

    public void deleteBetween(Instant from, Instant to) {
        jdbc.update("DELETE FROM driving_events WHERE ts >= ? AND ts < ?", Timestamp.from(from), Timestamp.from(to));
        jdbc.update("DELETE FROM trips WHERE started_at >= ? AND started_at < ?", Timestamp.from(from), Timestamp.from(to));
    }

    /** Feeds one vehicle's telemetry to the consumer in time order and returns the number of rows. */
    public int replayTelemetry(long vehicleId, Instant from, Instant to, Consumer<Reading> consumer) {
        int[] rows = {0};
        jdbc.query("""
                SELECT ts, lat, lng, speed, fuel_level, accel_min, accel_max, odometer_km
                FROM telemetry WHERE vehicle_id = ? AND ts >= ? AND ts < ? ORDER BY ts""",
                rs -> {
                    rows[0]++;
                    consumer.accept(new Reading(rs.getTimestamp(1).toInstant(), rs.getObject(2, Double.class),
                            rs.getObject(3, Double.class), rs.getObject(4, Double.class), rs.getObject(5, Double.class),
                            rs.getObject(6, Double.class), rs.getObject(7, Double.class), rs.getObject(8, Double.class)));
                }, vehicleId, Timestamp.from(from), Timestamp.from(to));
        return rows[0];
    }

    /**
     * km per litre over the vehicle's previous `trips` trips of at least minKm, or null if there are fewer
     * than `minTrips` of them.
     */
    public Double baselineKmPerLitre(long vehicleId, Instant before, double minKm, int trips, int minTrips) {
        return jdbc.queryForObject("""
                SELECT CASE WHEN count(*) >= ? THEN sum(distance_km) / sum(fuel_used_l) END FROM (
                    SELECT distance_km, fuel_used_l FROM trips
                    WHERE vehicle_id = ? AND started_at < ? AND distance_km >= ? AND fuel_used_l > 0
                    ORDER BY started_at DESC LIMIT ?) t""",
                Double.class, minTrips, vehicleId, Timestamp.from(before), minKm, trips);
    }

    /** Correlation between driver score and km per litre across vehicle-days; null with too little data. */
    public Double scoreEfficiencyCorrelation(Instant since, double minScoreKm) {
        return jdbc.queryForObject("""
                SELECT corr(score, efficiency) FROM (
                    SELECT greatest(0, 100 - sum(penalty_points) * 100 / greatest(sum(distance_km), ?)) AS score,
                           sum(distance_km) / nullif(sum(fuel_used_l), 0) AS efficiency
                    FROM trips WHERE started_at >= ?
                    GROUP BY vehicle_id, date_trunc('day', started_at AT TIME ZONE 'UTC')) d""",
                Double.class, minScoreKm, Timestamp.from(since));
    }

    public List<EventRow> fuelEvents(long vehicleId, Instant since, int limit) {
        return jdbc.query("""
                SELECT * FROM driving_events WHERE vehicle_id = ? AND ts >= ? AND type IN ('FUEL_DROP', 'LOW_EFFICIENCY')
                ORDER BY ts DESC LIMIT ?""",
                new DataClassRowMapper<>(EventRow.class), vehicleId, Timestamp.from(since), limit);
    }

    public List<EventRow> events(long vehicleId, Instant since, String type, int limit) {
        return jdbc.query("""
                SELECT * FROM driving_events WHERE vehicle_id = ? AND ts >= ? AND (?::text IS NULL OR type = ?)
                ORDER BY ts DESC LIMIT ?""",
                new DataClassRowMapper<>(EventRow.class), vehicleId, Timestamp.from(since), type, type, limit);
    }

    public List<TripRow> trips(long vehicleId, Instant since, int limit) {
        return jdbc.query("SELECT * FROM trips WHERE vehicle_id = ? AND started_at >= ? ORDER BY started_at DESC LIMIT ?",
                new DataClassRowMapper<>(TripRow.class), vehicleId, Timestamp.from(since), limit);
    }

    /** trips, distanceKm, penaltyPoints, fuelLitres, idleSeconds for one vehicle since a point in time. */
    public Map<String, Object> totals(long vehicleId, Instant since) {
        return jdbc.queryForMap("""
                SELECT count(*) AS "trips", coalesce(sum(distance_km), 0) AS "distanceKm",
                       coalesce(sum(penalty_points), 0) AS "penaltyPoints", coalesce(sum(fuel_used_l), 0) AS "fuelLitres",
                       coalesce(sum(idle_s), 0) AS "idleSeconds"
                FROM trips WHERE vehicle_id = ? AND started_at >= ?""", vehicleId, Timestamp.from(since));
    }

    /** The same totals per UTC day (by trip start), oldest first, plus the number of events. */
    public List<Map<String, Object>> daily(long vehicleId, Instant since) {
        return jdbc.queryForList("""
                SELECT date_trunc('day', started_at AT TIME ZONE 'UTC')::date AS "day", count(*) AS "trips",
                       sum(distance_km) AS "distanceKm", sum(penalty_points) AS "penaltyPoints",
                       sum(fuel_used_l) AS "fuelLitres", sum(idle_s) AS "idleSeconds", sum(events_count) AS "events"
                FROM trips WHERE vehicle_id = ? AND started_at >= ? GROUP BY 1 ORDER BY 1""",
                vehicleId, Timestamp.from(since));
    }

    /** One row per vehicle per UTC day that it drove: where the day started and ended and what happened between. */
    public List<Map<String, Object>> routes(long vehicleId, Instant since) {
        return jdbc.queryForList("""
                SELECT date_trunc('day', started_at AT TIME ZONE 'UTC')::date::text AS "day",
                       min(started_at) AS "startedAt", max(ended_at) AS "endedAt",
                       (array_agg(start_lat ORDER BY started_at))[1] AS "startLat",
                       (array_agg(start_lng ORDER BY started_at))[1] AS "startLng",
                       (array_agg(end_lat ORDER BY started_at DESC))[1] AS "endLat",
                       (array_agg(end_lng ORDER BY started_at DESC))[1] AS "endLng",
                       count(*) AS "trips", count(*) - 1 AS "stops",
                       round(sum(distance_km)::numeric, 1) AS "distanceKm", sum(duration_s) AS "drivingSeconds",
                       sum(events_count) AS "events", round(sum(fuel_used_l)::numeric, 1) AS "fuelLitres"
                FROM trips WHERE vehicle_id = ? AND started_at >= ? GROUP BY 1 ORDER BY 1 DESC""",
                vehicleId, Timestamp.from(since));
    }

    /** vehicleId, registration, trips, activeSeconds, idleSeconds, distanceKm for every vehicle, driven or not. */
    public List<Map<String, Object>> utilisation(Instant since) {
        return jdbc.queryForList("""
                SELECT v.id AS "vehicleId", v.registration AS "registration", count(t.id) AS "trips",
                       coalesce(sum(t.duration_s), 0) AS "activeSeconds", coalesce(sum(t.idle_s), 0) AS "idleSeconds",
                       coalesce(sum(t.distance_km), 0) AS "distanceKm"
                FROM vehicles v LEFT JOIN trips t ON t.vehicle_id = v.id AND t.started_at >= ?
                GROUP BY v.id ORDER BY v.id""", Timestamp.from(since));
    }

    public Map<String, Long> eventCounts(long vehicleId, Instant since) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT type, count(*) FROM driving_events WHERE vehicle_id = ? AND ts >= ? GROUP BY type ORDER BY type",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                }, vehicleId, Timestamp.from(since));
        return counts;
    }
}
