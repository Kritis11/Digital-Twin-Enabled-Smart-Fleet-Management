package com.fleettwin.alert;

import java.time.Instant;
import java.util.Optional;

import com.fleettwin.twin.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AlertService {

    public static final String TOPIC = "/topic/alerts";

    private final AlertRepository repository;
    private final SimpMessagingTemplate messaging;

    /** Creates and pushes an alert unless the same one is already open. */
    public Optional<Alert> raise(long vehicleId, String component, Status severity, String message, Alert.Source source) {
        if (repository.existsByVehicleIdAndComponentAndSeverityAndSourceAndAcknowledgedFalse(
                vehicleId, component, severity, source)) {
            return Optional.empty();
        }
        Alert alert = new Alert();
        alert.setVehicleId(vehicleId);
        alert.setComponent(component);
        alert.setSeverity(severity);
        alert.setMessage(message);
        alert.setSource(source);
        alert.setCreatedAt(Instant.now());
        try {
            alert = repository.save(alert);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another thread; uq_alerts_open kept the duplicate out.
            return Optional.empty();
        }
        messaging.convertAndSend(TOPIC, alert);
        return Optional.of(alert);
    }

    public Alert acknowledge(long id) {
        Alert alert = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!alert.isAcknowledged()) {
            alert.setAcknowledged(true);
            alert = repository.save(alert);
            messaging.convertAndSend(TOPIC, alert);
        }
        return alert;
    }
}
