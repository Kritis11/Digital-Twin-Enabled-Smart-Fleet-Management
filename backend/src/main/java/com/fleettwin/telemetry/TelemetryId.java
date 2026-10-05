package com.fleettwin.telemetry;

import java.io.Serializable;
import java.time.Instant;

public record TelemetryId(Long vehicleId, Instant ts) implements Serializable {
}
