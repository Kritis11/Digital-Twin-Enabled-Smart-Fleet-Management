package com.fleettwin.telemetry;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetryRepository extends JpaRepository<Telemetry, TelemetryId> {

    List<Telemetry> findByVehicleIdAndTsAfterOrderByTs(Long vehicleId, Instant after);
}
