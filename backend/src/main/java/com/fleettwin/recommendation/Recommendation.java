package com.fleettwin.recommendation;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "maintenance_recommendations")
@Getter
@Setter
@NoArgsConstructor
public class Recommendation {

    /** Declared least to most pressing, so priorities compare by ordinal. */
    public enum Priority { LOW, MEDIUM, HIGH, URGENT }

    public enum State { OPEN, SCHEDULED, DONE, DISMISSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long vehicleId;
    private String component;
    private String action;

    @Enumerated(EnumType.STRING)
    private Priority priority;
    private LocalDate recommendedBy;
    private String reason;

    @Enumerated(EnumType.STRING)
    private State status = State.OPEN;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private Long maintenanceRecordId;
}
