package com.fleettwin.recommendation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fleettwin.recommendation.Recommendation.Priority;
import com.fleettwin.recommendation.Recommendation.State;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    public record StatusChange(@NotNull State status) {
    }

    private final RecommendationRepository repository;
    private final RecommendationService service;

    /** Most urgent first, then by recommended-by date. Every filter is optional. */
    @GetMapping
    public List<Recommendation> list(@RequestParam(required = false) State status,
                                     @RequestParam(required = false) Priority priority,
                                     @RequestParam(required = false) Long vehicleId) {
        return repository.findAll().stream()
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> priority == null || r.getPriority() == priority)
                .filter(r -> vehicleId == null || r.getVehicleId().equals(vehicleId))
                .sorted(Comparator.comparing(Recommendation::getPriority).reversed()
                        .thenComparing(Recommendation::getRecommendedBy))
                .toList();
    }

    /** {"status": "SCHEDULED" | "DONE" | "DISMISSED" | "OPEN"}. DONE also records the maintenance and resets the part. */
    @PatchMapping("/{id}")
    public Recommendation update(@PathVariable long id, @jakarta.validation.Valid @RequestBody StatusChange change) {
        return service.transition(id, change.status());
    }

    /** Runs the rules now instead of waiting for the schedule. */
    @PostMapping("/recompute")
    public Map<String, Integer> recompute() {
        return service.recompute();
    }
}
