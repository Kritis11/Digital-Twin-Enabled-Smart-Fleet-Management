package com.fleettwin.alert;

import java.time.Instant;

import com.fleettwin.twin.Status;
import jakarta.persistence.Column;
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
@Table(name = "alerts")
@Getter
@Setter
@NoArgsConstructor
public class Alert {

    public enum Source { RULE, ML }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long vehicleId;
    private String component;

    @Enumerated(EnumType.STRING)
    private Status severity;
    private String message;

    @Enumerated(EnumType.STRING)
    private Source source;

    @Column(updatable = false)
    private Instant createdAt;
    private boolean acknowledged;
}
