package com.fleettwin.twin;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private static final Pattern INTERVAL = Pattern.compile("(\\d{1,6})([smhd]?)");
    private static final Map<String, Long> UNIT_SECONDS = Map.of("", 1L, "s", 1L, "m", 60L, "h", 3600L, "d", 86400L);

    // telemetry column -> JSON key (same keys as VehicleTwin.sensors)
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("speed", "speed"), Map.entry("engine_temp", "engineTemp"), Map.entry("rpm", "rpm"),
            Map.entry("battery_voltage", "batteryVoltage"), Map.entry("fuel_level", "fuelLevel"),
            Map.entry("vibration", "vibration"), Map.entry("brake_pad_wear", "brakePadWear"),
            Map.entry("tyre_pressure_fl", "tyrePressureFl"), Map.entry("tyre_pressure_fr", "tyrePressureFr"),
            Map.entry("tyre_pressure_rl", "tyrePressureRl"), Map.entry("tyre_pressure_rr", "tyrePressureRr"));
    private static final String HISTORY_SQL = "SELECT time_bucket(make_interval(secs => ?), ts) AS \"ts\", "
            + COLUMNS.entrySet().stream()
                    .map(e -> "avg(" + e.getKey() + ") AS \"" + e.getValue() + "\"")
                    .collect(Collectors.joining(", "))
            + " FROM telemetry WHERE vehicle_id = ? AND ts >= ? AND ts < ? GROUP BY 1 ORDER BY 1";

    private final TwinService twins;
    private final JdbcTemplate jdbc;

    @Value("${fleet.telemetry.default-buckets}")
    private int defaultBuckets;
    @Value("${fleet.telemetry.max-buckets}")
    private int maxBuckets;

    @GetMapping
    public List<VehicleTwin> list() {
        return twins.all();
    }

    @GetMapping("/{id}/twin")
    public VehicleTwin twin(@PathVariable long id) {
        return twins.full(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Bucket averages, oldest first. Defaults: to = now, from = 15 minutes before to, and an interval
     * that gives about fleet.telemetry.default-buckets points. interval is e.g. 10s, 5m, 1h or plain seconds.
     */
    @GetMapping("/{id}/telemetry")
    public List<Map<String, Object>> telemetry(
            @PathVariable long id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String interval) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofMinutes(15));
        long range = Duration.between(start, end).toSeconds();
        if (range <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        long seconds = interval == null ? Math.max(1, range / defaultBuckets) : parseInterval(interval);
        if (range / seconds > maxBuckets) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interval too small for this range");
        }
        return jdbc.queryForList(HISTORY_SQL, (double) seconds, id, Timestamp.from(start), Timestamp.from(end));
    }

    static long parseInterval(String interval) {
        Matcher m = INTERVAL.matcher(interval);
        if (!m.matches() || Long.parseLong(m.group(1)) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interval must look like 10s, 5m, 1h or 1d");
        }
        return Long.parseLong(m.group(1)) * UNIT_SECONDS.get(m.group(2));
    }
}
