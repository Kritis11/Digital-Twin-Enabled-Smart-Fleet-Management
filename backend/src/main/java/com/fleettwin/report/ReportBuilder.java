package com.fleettwin.report;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fleettwin.config.FleetProperties;
import com.fleettwin.driving.DrivingAnalyzer;
import com.fleettwin.report.ReportData.Section;
import com.fleettwin.twin.TwinService;
import com.fleettwin.twin.VehicleTwin;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Gathers the figures for each report. Dates are UTC days; `to` is inclusive. */
@Component
@RequiredArgsConstructor
public class ReportBuilder {

    public enum Type {
        FLEET_HEALTH("Fleet health summary"), MAINTENANCE("Maintenance report"),
        DRIVER_BEHAVIOUR("Driver behaviour report"), FUEL("Fuel report");

        public final String title;

        Type(String title) {
            this.title = title;
        }
    }

    private final JdbcTemplate jdbc;
    private final TwinService twins;
    private final FleetProperties props;

    public ReportData build(Type type, LocalDate from, LocalDate to) {
        Timestamp start = Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        List<Section> sections = switch (type) {
            case FLEET_HEALTH -> fleetHealth(start, end);
            case MAINTENANCE -> maintenance(start, end);
            case DRIVER_BEHAVIOUR -> driverBehaviour(start, end);
            case FUEL -> fuel(start, end);
        };
        return new ReportData(type.title, String.format("%s to %s (UTC). Generated %s.", from, to,
                Instant.now().truncatedTo(ChronoUnit.SECONDS)), sections);
    }

    private List<Section> fleetHealth(Timestamp start, Timestamp end) {
        Map<Long, List<Object>> alerts = new HashMap<>();
        jdbc.query("""
                SELECT vehicle_id, count(*) FILTER (WHERE severity = 'CRITICAL'), count(*) FILTER (WHERE severity = 'WARNING'),
                       count(*) FILTER (WHERE source = 'ML')
                FROM alerts WHERE created_at >= ? AND created_at < ? GROUP BY vehicle_id""",
                rs -> {
                    alerts.put(rs.getLong(1), List.of(rs.getLong(2), rs.getLong(3), rs.getLong(4)));
                }, start, end);
        Map<Long, Long> open = new HashMap<>();
        jdbc.query("SELECT vehicle_id, count(*) FROM alerts WHERE NOT acknowledged GROUP BY vehicle_id",
                rs -> {
                    open.put(rs.getLong(1), rs.getLong(2));
                });

        List<VehicleTwin> fleet = twins.all();
        List<List<Object>> rows = new ArrayList<>();
        long critical = 0;
        long warning = 0;
        long anomalies = 0;
        for (VehicleTwin twin : fleet) {
            List<Object> counts = alerts.getOrDefault(twin.getId(), List.of(0L, 0L, 0L));
            critical += (long) counts.get(0);
            warning += (long) counts.get(1);
            anomalies += (long) counts.get(2);
            var lowest = twin.getRul().entrySet().stream().min(Comparator.comparingDouble(e -> e.getValue().days()));
            rows.add(Arrays.asList(twin.getRegistration(), twin.getState().name(), twin.getHealthScore(),
                    counts.get(0), counts.get(1), counts.get(2), open.getOrDefault(twin.getId(), 0L),
                    lowest.map(e -> e.getKey()).orElse(null), lowest.map(e -> round1(e.getValue().days())).orElse(null)));
        }
        double averageHealth = fleet.stream().filter(t -> t.getHealthScore() != null)
                .mapToDouble(VehicleTwin::getHealthScore).average().orElse(Double.NaN);
        List<String> notes = List.of(
                String.format(Locale.ROOT, "%d vehicles. Average health score now: %s.", fleet.size(),
                        Double.isNaN(averageHealth) ? "not available" : String.format(Locale.ROOT, "%.0f", averageHealth)),
                String.format(Locale.ROOT, "Alerts raised in the period: %d critical, %d warning; %d of them ML anomalies.",
                        critical, warning, anomalies),
                "Health score, state, open alerts and remaining life are as of now; the alert counts are for the period.");

        List<List<Object>> byComponent = jdbc.query("""
                SELECT component, count(*) FILTER (WHERE severity = 'CRITICAL'), count(*) FILTER (WHERE severity = 'WARNING'),
                       count(*) FILTER (WHERE source = 'ML')
                FROM alerts WHERE created_at >= ? AND created_at < ? GROUP BY component ORDER BY 2 DESC, 3 DESC""",
                (rs, i) -> List.<Object>of(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), start, end);
        return List.of(
                new Section("Vehicles", notes, List.of("Vehicle", "State", "Health score", "Critical alerts", "Warning alerts",
                        "ML anomalies", "Open alerts now", "Lowest RUL part", "RUL days"), rows),
                new Section("Alerts by component", List.of(), List.of("Component", "Critical", "Warning", "ML anomalies"), byComponent));
    }

    private List<Section> maintenance(Timestamp start, Timestamp end) {
        List<List<Object>> completed = jdbc.query("""
                SELECT m.performed_at::date::text, v.registration, m.type, m.component, m.cause, m.cost, m.description
                FROM maintenance_records m JOIN vehicles v ON v.id = m.vehicle_id
                WHERE m.performed_at >= ? AND m.performed_at < ? ORDER BY m.performed_at""",
                (rs, i) -> Arrays.<Object>asList(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getString(7)), start, end);
        List<List<Object>> costs = jdbc.query("""
                SELECT v.registration, count(m.id), coalesce(sum(m.cost), 0),
                       count(m.id) FILTER (WHERE m.cause = 'FAILURE'), count(m.id) FILTER (WHERE m.cause <> 'FAILURE')
                FROM vehicles v LEFT JOIN maintenance_records m
                     ON m.vehicle_id = v.id AND m.performed_at >= ? AND m.performed_at < ?
                GROUP BY v.id ORDER BY 3 DESC""",
                (rs, i) -> List.<Object>of(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3), rs.getLong(4), rs.getLong(5)), start, end);
        double total = costs.stream().mapToDouble(r -> ((Number) r.get(2)).doubleValue()).sum();

        // Upcoming and overdue are about today, whatever period was asked for.
        String outstanding = """
                SELECT r.recommended_by::text, v.registration, r.action, r.priority, r.status, r.reason
                FROM maintenance_recommendations r JOIN vehicles v ON v.id = r.vehicle_id
                WHERE r.status IN ('OPEN', 'SCHEDULED') AND r.recommended_by %s current_date ORDER BY r.recommended_by""";
        List<String> headers = List.of("Recommended by", "Vehicle", "Action", "Priority", "Status", "Why");
        List<List<Object>> upcoming = jdbc.query(outstanding.formatted(">="), (rs, i) -> List.<Object>of(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
        List<List<Object>> overdue = jdbc.query(outstanding.formatted("<"), (rs, i) -> List.<Object>of(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
        return List.of(
                new Section("Completed", List.of(String.format(Locale.ROOT, "%d jobs completed in the period, costing %,.2f in total.",
                        completed.size(), total)),
                        List.of("Date", "Vehicle", "Work", "Part", "Cause", "Cost", "Description"), completed),
                new Section("Costs by vehicle", List.of(), List.of("Vehicle", "Jobs", "Cost", "After a failure", "Planned"), costs),
                new Section("Upcoming", List.of(upcoming.size() + " recommendations are open or scheduled and not yet due."), headers, upcoming),
                new Section("Overdue", List.of(overdue.size() + " recommendations are past their recommended-by date."), headers, overdue));
    }

    private List<Section> driverBehaviour(Timestamp start, Timestamp end) {
        record Driver(String registration, long trips, double km, double penalty, long events) {
        }
        List<Driver> drivers = jdbc.query("""
                SELECT v.registration, count(t.id), coalesce(sum(t.distance_km), 0), coalesce(sum(t.penalty_points), 0),
                       coalesce(sum(t.events_count), 0)
                FROM vehicles v LEFT JOIN trips t ON t.vehicle_id = v.id AND t.started_at >= ? AND t.started_at < ?
                GROUP BY v.id""",
                (rs, i) -> new Driver(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4), rs.getLong(5)), start, end);
        double minKm = props.driving().minScoreKm();
        // Best score first; vehicles that did not drive have no score and go last.
        List<Driver> ranked = drivers.stream().sorted(Comparator.comparingDouble(
                (Driver d) -> d.trips() == 0 ? -1 : DrivingAnalyzer.score(d.penalty(), d.km(), minKm)).reversed()).toList();
        List<List<Object>> rows = new ArrayList<>();
        for (Driver d : ranked) {
            boolean drove = d.trips() > 0;
            rows.add(Arrays.asList(drove ? rows.size() + 1 : null, d.registration(),
                    drove ? round1(DrivingAnalyzer.score(d.penalty(), d.km(), minKm)) : null, round1(d.km()), d.trips(),
                    d.events(), d.km() > 0 ? round1(d.events() * 100 / d.km()) : null));
        }
        List<List<Object>> events = jdbc.query("""
                SELECT v.registration, e.type, count(*), count(*) FILTER (WHERE e.severity = 'HIGH')
                FROM driving_events e JOIN vehicles v ON v.id = e.vehicle_id
                WHERE e.ts >= ? AND e.ts < ? AND e.type NOT IN ('FUEL_DROP', 'LOW_EFFICIENCY')
                GROUP BY v.registration, e.type ORDER BY 1, 2""",
                (rs, i) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)), start, end);
        return List.of(
                new Section("Ranking", List.of("Driver score: 100 minus penalty points per 100 km (see docs/driver_score.md). Best first."),
                        List.of("Rank", "Vehicle", "Driver score", "Distance km", "Trips", "Events", "Events per 100 km"), rows),
                new Section("Events by type", List.of(), List.of("Vehicle", "Event", "Count", "Of which severe"), events));
    }

    private List<Section> fuel(Timestamp start, Timestamp end) {
        double idleRate = props.fuel().idleLitresPerHour();
        List<List<Object>> rows = jdbc.query("""
                SELECT v.registration, coalesce(sum(t.distance_km), 0), coalesce(sum(t.fuel_used_l), 0),
                       coalesce(sum(t.idle_s), 0) / 3600.0,
                       (SELECT count(*) FROM driving_events e WHERE e.vehicle_id = v.id AND e.ts >= ? AND e.ts < ?
                               AND e.type IN ('FUEL_DROP', 'LOW_EFFICIENCY'))
                FROM vehicles v LEFT JOIN trips t ON t.vehicle_id = v.id AND t.started_at >= ? AND t.started_at < ?
                GROUP BY v.id ORDER BY v.registration""",
                (rs, i) -> {
                    double km = rs.getDouble(2);
                    double litres = rs.getDouble(3);
                    double idleHours = rs.getDouble(4);
                    return Arrays.<Object>asList(rs.getString(1), round1(km), round1(litres),
                            litres > 0 ? round2(km / litres) : null, km > 0 ? round1(litres * 100 / km) : null,
                            round1(idleHours), round1(idleHours * idleRate), rs.getLong(5));
                }, start, end, start, end);
        double km = rows.stream().mapToDouble(r -> (double) r.get(1)).sum();
        double litres = rows.stream().mapToDouble(r -> (double) r.get(2)).sum();
        double idleLitres = rows.stream().mapToDouble(r -> (double) r.get(6)).sum();
        List<String> notes = List.of(
                String.format(Locale.ROOT, "The fleet drove %,.0f km on %,.0f litres%s.", km, litres,
                        litres > 0 ? String.format(Locale.ROOT, " (%.2f km/l)", km / litres) : ""),
                String.format(Locale.ROOT, "Idling used about %,.0f litres%s, at %.1f litres per hour of standing with the engine on.",
                        idleLitres, litres > 0 ? String.format(Locale.ROOT, " (%.0f%% of all fuel)", idleLitres * 100 / litres) : "", idleRate));
        List<List<Object>> anomalies = jdbc.query("""
                SELECT to_char(e.ts AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'), v.registration, e.type, e.severity, e.detail
                FROM driving_events e JOIN vehicles v ON v.id = e.vehicle_id
                WHERE e.ts >= ? AND e.ts < ? AND e.type IN ('FUEL_DROP', 'LOW_EFFICIENCY') ORDER BY e.ts""",
                (rs, i) -> List.<Object>of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), start, end);
        return List.of(
                new Section("Efficiency", notes, List.of("Vehicle", "Distance km", "Fuel l", "km per litre", "l per 100 km",
                        "Idle hours", "Idling fuel l", "Fuel anomalies"), rows),
                new Section("Fuel anomalies", List.of(anomalies.size() + " fuel anomalies in the period."),
                        List.of("When (UTC)", "Vehicle", "Type", "Severity", "Detail"), anomalies));
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
