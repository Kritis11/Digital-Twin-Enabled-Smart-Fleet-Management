package com.fleettwin.driving;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fleettwin.config.FleetProperties;
import com.fleettwin.driving.DrivingRepository.EventRow;
import com.fleettwin.driving.DrivingRepository.TripRow;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DrivingController {

    private final DrivingRepository repository;
    private final DrivingService service;
    private final FleetProperties props;

    /**
     * Driver score for the period and for each day in it. score is null when the vehicle did not drive.
     * period is today, or a number of hours or days such as 24h, 7d, 30d.
     */
    @GetMapping("/vehicles/{id}/driver-score")
    public Map<String, Object> driverScore(@PathVariable long id, @RequestParam(defaultValue = "7d") String period) {
        Instant since = Periods.since(period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vehicleId", id);
        out.put("period", period);
        Map<String, Object> totals = repository.totals(id, since);
        out.put("score", score(totals));
        out.putAll(totals);
        out.put("eventCounts", repository.eventCounts(id, since));
        List<Map<String, Object>> daily = repository.daily(id, since);
        daily.forEach(day -> day.put("score", score(day)));
        out.put("daily", daily);
        return out;
    }

    @GetMapping("/vehicles/{id}/trips")
    public List<TripRow> trips(@PathVariable long id, @RequestParam(defaultValue = "7d") String period) {
        return repository.trips(id, Periods.since(period), props.driving().maxResults());
    }

    /** Newest first. type is optional, e.g. HARSH_BRAKING. */
    @GetMapping("/vehicles/{id}/events")
    public List<EventRow> events(@PathVariable long id, @RequestParam(defaultValue = "24h") String period,
                                 @RequestParam(required = false) String type) {
        return repository.events(id, Periods.since(period), type, props.driving().maxResults());
    }

    /** Rebuilds trips and events from stored telemetry, e.g. after the simulator's fast-forward. */
    @PostMapping("/admin/reanalyse")
    public Map<String, Object> reanalyse(@RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to) {
        return service.reanalyse(from != null ? from : Instant.EPOCH, to != null ? to : Instant.now());
    }

    /** The driver score for a row carrying penaltyPoints and distanceKm; null if nothing was driven. */
    Double score(Map<String, Object> row) {
        double km = ((Number) row.get("distanceKm")).doubleValue();
        if (km <= 0) {
            return null;
        }
        double score = DrivingAnalyzer.score(((Number) row.get("penaltyPoints")).doubleValue(), km, props.driving().minScoreKm());
        return Math.round(score * 10) / 10.0;
    }
}
