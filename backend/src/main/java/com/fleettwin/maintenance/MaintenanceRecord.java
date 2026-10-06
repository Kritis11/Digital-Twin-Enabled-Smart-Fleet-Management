package com.fleettwin.maintenance;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "maintenance_records")
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    private Long vehicleId;
    private Long componentId;

    /** brakes, battery, tyres or engine when the work renews that part (starts a new wear lifecycle). */
    @Size(max = 50)
    private String component;
    /** FAILURE, PREVENTIVE or RECOMMENDATION. */
    @Size(max = 20)
    private String cause;

    @NotBlank
    @Size(max = 50)
    private String type;
    private String description;

    @NotNull
    private Instant performedAt;

    @PositiveOrZero
    private BigDecimal cost;
}
