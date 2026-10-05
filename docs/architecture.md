# Architecture notes

## Data path (what works today)

```
simulator ──MQTT──▶ Mosquitto ──fleet/+/telemetry──▶ backend ──JPA──▶ TimescaleDB (telemetry hypertable)
                                                        ▲
frontend ──GET /actuator/health─────────────────────────┘

ml-service: standalone, placeholder responses, not yet called by anything
Redis, MinIO: running and reachable, not yet used by application code
```

## Decisions

- **Vehicle id comes from the MQTT topic**, not the JSON body. A device can't write another
  vehicle's telemetry by lying in the payload once per-vehicle broker ACLs are added.
- **`telemetry` primary key is `(vehicle_id, ts)`**. TimescaleDB requires the partition column
  (`ts`) in every unique key. A re-delivered message (QoS 1) overwrites the same row.
- **`telemetry.vehicle_id` has a foreign key to `vehicles`**, so telemetry for unknown vehicles is
  rejected and logged rather than stored.
- **`injected_fault` column** holds the simulator's ground-truth fault label for ML training. It
  is NULL for healthy readings and would be NULL for real vehicles. Engine temperature stays high
  for a few readings after an overheating fault ends, so labels mark when the fault was active,
  not every abnormal reading.
- **Bad MQTT messages are logged and dropped**, so one malformed payload can't stall ingestion.
- **Credentials live only in the root `.env`**. Compose, the backend and the simulator all read
  it. The Mosquitto password file is generated from it at container start.
- **Security permits everything** for now; CORS allows only `http://localhost:4200`.

## Not built yet

- JPA entities and REST APIs for vehicles, components and maintenance records (tables exist).
- Digital twin state in Redis, and WebSocket push to the frontend (dependencies are in place).
- Real models behind `/anomaly`, `/health-score`, `/rul`; model artifacts in MinIO.
- Backend → ML service calls.
- Authentication and per-vehicle MQTT ACLs.
- TimescaleDB compression and retention policies.
- Maps (Leaflet) and charts (ngx-echarts) on the frontend (installed and wired, unused).
