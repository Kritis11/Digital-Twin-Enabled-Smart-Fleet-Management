package com.fleettwin.alert;

import com.fleettwin.twin.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    boolean existsByVehicleIdAndComponentAndSeverityAndSourceAndAcknowledgedFalse(
            Long vehicleId, String component, Status severity, Alert.Source source);
}
