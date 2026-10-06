package com.fleettwin.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MlServiceTest {

    @Test
    void mapsModelReasonsToTwinComponents() {
        assertEquals("tyre_fl", MlService.componentOf("tyre_pressure_fl_min_30s=19.80"));
        assertEquals("tyre_rr", MlService.componentOf("tyre_pressure_rr_last=20.10"));
        assertEquals("battery", MlService.componentOf("battery_voltage_roc_120s=-0.02"));
        assertEquals("engine", MlService.componentOf("engine_temp_max_30s=118.20"));
        assertEquals("engine", MlService.componentOf("vibration_last=2.92"));
        assertEquals("engine", MlService.componentOf("rpm_mean_30s=900.00"));
        assertEquals("vehicle", MlService.componentOf(""));
    }
}
