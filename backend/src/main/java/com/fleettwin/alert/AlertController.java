package com.fleettwin.alert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fleettwin.twin.Status;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertRepository repository;
    private final AlertService service;

    @Value("${fleet.alerts.max-results}")
    private int maxResults;

    /** Newest first. status is open (default), acknowledged or all; every other filter is optional. */
    @GetMapping
    public List<Alert> list(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) Status severity,
            @RequestParam(required = false) Alert.Source source,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        Boolean acknowledged = switch (status) {
            case "open" -> false;
            case "acknowledged" -> true;
            case "all" -> null;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be open, acknowledged or all");
        };
        Specification<Alert> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (acknowledged != null) p.add(cb.equal(root.get("acknowledged"), acknowledged));
            if (vehicleId != null) p.add(cb.equal(root.get("vehicleId"), vehicleId));
            if (severity != null) p.add(cb.equal(root.get("severity"), severity));
            if (source != null) p.add(cb.equal(root.get("source"), source));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) p.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, PageRequest.of(0, maxResults, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
    }

    @PostMapping("/{id}/acknowledge")
    public Alert acknowledge(@PathVariable long id) {
        return service.acknowledge(id);
    }
}
