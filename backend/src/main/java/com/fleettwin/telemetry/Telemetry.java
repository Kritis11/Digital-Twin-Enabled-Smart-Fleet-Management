package com.fleettwin.telemetry;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "telemetry")
@IdClass(TelemetryId.class)
@Getter
@Setter
@NoArgsConstructor
public class Telemetry {

    @Id
    private Long vehicleId;
    @Id
    private Instant ts;

    private Double lat;
    private Double lng;
    private Double speed;
    private Double engineTemp;
    private Double rpm;
    private Double batteryVoltage;
    private Double fuelLevel;
    private Double vibration;
    private Double tyrePressureFl;
    private Double tyrePressureFr;
    private Double tyrePressureRl;
    private Double tyrePressureRr;
    private Double brakePadWear;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> dtcCodes = List.of();

    private String injectedFault;
}
