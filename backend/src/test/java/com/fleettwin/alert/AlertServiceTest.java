package com.fleettwin.alert;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fleettwin.twin.Status;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class AlertServiceTest {

    private final AlertRepository repository = mock(AlertRepository.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final AlertService service = new AlertService(repository, messaging);

    @Test
    void raisesAndPushesANewAlert() {
        when(repository.save(any(Alert.class))).thenAnswer(inv -> inv.getArgument(0));

        var alert = service.raise(1, "engine", Status.CRITICAL, "hot", Alert.Source.RULE);

        assertTrue(alert.isPresent());
        verify(messaging).convertAndSend(AlertService.TOPIC, alert.get());
    }

    @Test
    void doesNotRepeatAnOpenAlert() {
        when(repository.existsByVehicleIdAndComponentAndSeverityAndSourceAndAcknowledgedFalse(
                1L, "engine", Status.CRITICAL, Alert.Source.RULE)).thenReturn(true);

        assertTrue(service.raise(1, "engine", Status.CRITICAL, "hot", Alert.Source.RULE).isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void uniqueIndexViolationIsTreatedAsDuplicate() {
        when(repository.save(any(Alert.class))).thenThrow(new DataIntegrityViolationException("uq_alerts_open"));

        assertTrue(service.raise(1, "engine", Status.CRITICAL, "hot", Alert.Source.RULE).isEmpty());
        verify(messaging, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
