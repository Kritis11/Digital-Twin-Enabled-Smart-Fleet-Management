-- Phase 2: wear indicators and usage counters reported by the vehicle, and what a maintenance record was for.
ALTER TABLE telemetry
    ADD COLUMN battery_health DOUBLE PRECISION,   -- % of new capacity
    ADD COLUMN tyre_tread     DOUBLE PRECISION,   -- mm
    ADD COLUMN engine_health  DOUBLE PRECISION,   -- 0-100 index
    ADD COLUMN odometer_km    DOUBLE PRECISION,
    ADD COLUMN engine_hours   DOUBLE PRECISION,
    -- Peak longitudinal acceleration since the previous reading, m/s^2 (accel_min is the hardest braking).
    ADD COLUMN accel_min      DOUBLE PRECISION,
    ADD COLUMN accel_max      DOUBLE PRECISION;

ALTER TABLE maintenance_records
    -- brakes, battery, tyres or engine: the start of a new wear lifecycle for that part. NULL for other work.
    ADD COLUMN component VARCHAR(50),
    -- FAILURE (part failed), PREVENTIVE (serviced before failing) or RECOMMENDATION (completed from the planner).
    ADD COLUMN cause     VARCHAR(20);
CREATE INDEX idx_maintenance_component ON maintenance_records (vehicle_id, component, performed_at DESC);
