package com.fleettwin.telemetry;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** JSON body published on fleet/{vehicleId}/telemetry. The vehicle id comes from the topic, not the body. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryPayload(
        Instant ts,
        Double lat,
        Double lng,
        Double speed,
        Double engineTemp,
        Double rpm,
        Double batteryVoltage,
        Double fuelLevel,
        Double vibration,
        Double tyrePressureFl,
        Double tyrePressureFr,
        Double tyrePressureRl,
        Double tyrePressureRr,
        Double brakePadWear,
        List<String> dtcCodes,
        String injectedFault) {

    public Telemetry toEntity(long vehicleId) {
        Telemetry t = new Telemetry();
        t.setVehicleId(vehicleId);
        t.setTs(ts != null ? ts : Instant.now());
        t.setLat(lat);
        t.setLng(lng);
        t.setSpeed(speed);
        t.setEngineTemp(engineTemp);
        t.setRpm(rpm);
        t.setBatteryVoltage(batteryVoltage);
        t.setFuelLevel(fuelLevel);
        t.setVibration(vibration);
        t.setTyrePressureFl(tyrePressureFl);
        t.setTyrePressureFr(tyrePressureFr);
        t.setTyrePressureRl(tyrePressureRl);
        t.setTyrePressureRr(tyrePressureRr);
        t.setBrakePadWear(brakePadWear);
        t.setDtcCodes(dtcCodes != null ? dtcCodes : List.of());
        t.setInjectedFault(injectedFault);
        return t;
    }
}
