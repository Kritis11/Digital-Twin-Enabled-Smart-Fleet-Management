package com.fleettwin.maintenance;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceRepository extends JpaRepository<MaintenanceRecord, Long> {

    List<MaintenanceRecord> findByVehicleIdOrderByPerformedAtDesc(Long vehicleId, Pageable pageable);

    long countByVehicleId(Long vehicleId);
}
