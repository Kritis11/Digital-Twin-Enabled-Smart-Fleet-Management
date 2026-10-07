# Architecture notes

## Data path

```
simulator ──MQTT──▶ Mosquitto ──fleet/+/telemetry──▶ backend ──▶ TimescaleDB (telemetry hypertable)
                                                        │
                                                        ├─ per message: update twin in Redis, apply threshold
                                                        │  rules, raise RULE alerts, push to /topic/twins
                                                        │
                                                        └─ every 10 s per online vehicle: last 2 min of telemetry
                                                           ──▶ ml-service /anomaly, /health-score ──▶ twin, ML alerts

                                                        ├─ per message: driving analyzer ──▶ driving_events, trips
                                                        │  (driver score and fuel use are computed from these in SQL)
                                                        │
                                                        ├─ every 60 s: ml-service /rul per component ──▶ twin
                                                        │  every 60 s: driver score and km/l (SQL) ──▶ twin
                                                        │
                                                        └─ every 15 min: recommendation rules over RUL, statuses,
                                                           alerts, anomaly history, last service
                                                           ──▶ maintenance_recommendations, open count on twin

recommendation marked DONE ──▶ maintenance_record + wear reset in twin ──MQTT fleet/{id}/maintenance──▶ simulator
simulator --fast-forward ──▶ TimescaleDB directly (telemetry + maintenance_records), then POST /api/admin/reanalyse

ml-service ◀── newest models ── MinIO ◀── training/train.py, train_rul.py ◀── TimescaleDB
ml-service /rul ── reads the vehicle's daily wear and usage ── TimescaleDB
frontend ── REST (initial load, history) + STOMP (/topic/twins, /topic/alerts) ── backend
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
- **The telemetry row is stored before the twin is touched.** A twin failure (Redis down, a bug in
  the rules) is logged separately and never loses telemetry.
- **Twins live in Redis as one JSON value per vehicle (`twin:{id}`)** and are disposable: identity is
  re-read from PostgreSQL and the rest is rebuilt by the next telemetry message. Updates are
  read-modify-write under a JVM lock, which is only correct for a single backend instance.
- **Heading is derived** from consecutive positions, because the simulator doesn't publish one.
- **Alert de-duplication is enforced by the database**: a partial unique index on
  (vehicle, component, severity, source) for unacknowledged rows. An alert stays open until someone
  acknowledges it, even after the component recovers, so a repeat of the same problem raises
  nothing new until then.
- **Chart markers are alerts**, not individual anomalous readings. No per-reading anomaly scores
  are stored.
- **ML calls run on the scheduler thread**, never on the MQTT thread, with short timeouts. The ML
  service being slow or down cannot delay ingestion.
- **Training and inference share `app/features.py`**, and the backend sends a 2-minute window, so
  the features the model sees in production are computed exactly as in training.
- **Model versions are UTC timestamps** under `models/anomaly/` in MinIO; the newest wins. Nothing
  is ever overwritten, so rolling back means deleting the newest version and reloading.
- **Driving events, trips and fuel figures are derived data.** They are computed from telemetry by
  one analyzer, live and in `POST /api/admin/reanalyse`, so they can always be rebuilt. Thresholds
  and score weights are config; the formula is in [driver_score.md](driver_score.md).
- **Fuel that vanishes while parked is not counted as trip fuel**, so a theft or leak shows up as a
  `FUEL_DROP` event and does not also drag down that vehicle's efficiency.
- **`/rul` reads history itself.** RUL features cover the part's whole time in service, far more
  than the backend could reasonably send, so the ML service queries TimescaleDB (cached briefly).
- **RUL bounds are conformalised quantiles**: XGBoost's 10% and 90% quantiles, widened by a
  per-component margin so that 80% of held-out values fall inside.
- **Recommendations are rules, not a model.** `RecommendationEngine` is a pure function of its
  inputs; every rule that fires adds a sentence to the reason and the highest priority wins. A part
  is recommended for replacement only when its wear prediction fired, otherwise for inspection.
  There is at most one active recommendation per vehicle and component, updated in place.
- **Driver score, fuel efficiency and recommendations do not need the ML service**; with it down,
  recommendations simply lack RUL evidence.
- **Credentials live only in the root `.env`**. Compose, the backend, the ML service and the
  simulator all read it. The Mosquitto password file is generated from it at container start.
- **Security permits everything** for now; CORS and the WebSocket allow only `http://localhost:4200`.

## Not built yet

- The `components` table is still unused.
- Named drivers: scores are per vehicle, which assumes one driver per vehicle.
- Real speed limits and road data: speeding uses one fleet-wide limit, cornering comes from
  heading change between readings.
- Authentication and per-vehicle MQTT ACLs.
- TimescaleDB compression and retention policies.
- Running more than one backend instance (see the twin locking note above).
