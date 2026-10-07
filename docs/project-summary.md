# Project summary: Digital-Twin-Enabled Smart Fleet Management

Fleet Twin keeps a live digital twin of every vehicle in a fleet, predicts which parts are going
to fail and when, and uses that knowledge to plan maintenance and delivery routes. It was built in
three phases, each ending in a working, tested system.

## Objectives

| | Objective | Delivered in |
|---|---|---|
| **O1** | Build a real-time digital twin of each vehicle from its telemetry, and monitor the fleet's condition live | Phase 1 |
| **O2** | Predict failures before they happen and turn the predictions into maintenance decisions | Phases 1 and 2 |
| **O3** | Optimise how the fleet is operated (routes, fuel, drivers, utilisation) and make the system usable by an organisation: secure, reportable, deployable | Phases 2 and 3 |

## What was built

### Phase 1: digital twin and anomaly detection

- Simulated vehicles publish telemetry over MQTT every 2 seconds; the backend stores it in a
  TimescaleDB hypertable.
- A twin per vehicle in Redis: position, heading, state, latest sensor values, and a status per
  component from configurable threshold rules.
- Alerts from rules and from the anomaly model, de-duplicated by the database, pushed to the
  dashboard over WebSocket.
- An anomaly detection model (XGBoost over rolling-window features) trained on simulator data,
  versioned in MinIO, served with the readings that explain each anomaly.
- A health score per vehicle and a dashboard with a fleet map, a vehicle twin view and alerts.

### Phase 2: prediction and analysis

- Simulator extended with gradual wear, driver profiles, and a fast-forward mode that writes
  90 days of history including failures and repairs.
- Remaining useful life per component (brake pads, battery, tyres, engine): XGBoost quantile
  regression compared with a linear baseline, with prediction intervals and a confidence.
- Driver behaviour: five event types detected from telemetry, trip detection, and a transparent
  0–100 driver score per trip and day.
- Fuel analysis: efficiency per trip and day, idling cost, and detection of fuel drops while
  parked and of unusually poor efficiency.
- Maintenance recommendations from explainable, configurable rules over RUL, status, alerts,
  anomaly history and service intervals; completing one records the work and resets the part.
- Dashboard: RUL bars, driving and fuel views, Maintenance Planner, Drivers & Fuel.

### Phase 3: optimisation, access control, reporting, deployment

- Route optimisation with Google OR-Tools over road distances from a self-hosted OSRM, with a
  straight-line fallback. Assignment is health-aware: unfit vehicles are excluded with reasons,
  and efficient, well-driven vehicles are preferred.
- Fleet utilisation and per-vehicle route history.
- JWT authentication with four roles, a secured WebSocket, user management and an audit log.
- Fleet health, maintenance, driver behaviour and fuel reports as PDF and Excel, stored in MinIO.
- Production deployment: Docker images, one compose file behind nginx with Let's Encrypt,
  scheduled backups with a tested restore into a new database, CI, and Prometheus metrics.

## Objectives mapped to features

### O1: real-time digital twin and monitoring

| Feature | Where |
|---|---|
| Telemetry ingest over MQTT into a time-series database | `TelemetryIngestService`, Flyway V1 |
| Live twin per vehicle: location, state, sensors, component statuses | `TwinService`, `VehicleTwin`, Redis |
| Threshold rules per component, configurable | `fleet.rules` in `application.yml`, `Rule` |
| Alerts, de-duplicated, with acknowledgement | `AlertService`, Flyway V3 |
| Live updates to the browser | STOMP `/topic/twins`, `/topic/alerts` |
| Fleet map, vehicle twin view, alert feed | Fleet Overview, Vehicle detail, Alerts pages |
| Twin enriched with RUL, driver score, fuel efficiency, open recommendations | `VehicleTwin` (Phase 2) |

### O2: predictive maintenance

| Feature | Where |
|---|---|
| Anomaly detection with explanations | `ml-service/app/model.py`, `POST /anomaly` |
| Health score from component statuses and anomaly score | `POST /health-score` |
| Remaining useful life with bounds and confidence | `ml-service/app/rul.py`, `POST /rul` |
| Versioned models, retraining pipeline, model report | `ml-service/training/`, MinIO, [model_report.md](model_report.md) |
| Maintenance recommendations with plain-English evidence | `RecommendationEngine`, Maintenance Planner |
| Closing the loop: completing a recommendation records maintenance and resets wear | `RecommendationService.transition` |
| Maintenance report: completed, upcoming, overdue, costs | Reports page |

### O3: fleet optimisation and an operable system

| Feature | Where |
|---|---|
| Driver behaviour events and driver score | `DrivingAnalyzer`, [driver_score.md](driver_score.md) |
| Fuel efficiency, idling cost, fuel anomalies | `FuelController`, Drivers & Fuel page |
| Route optimisation with time windows | `ml-service/app/routing.py`, `POST /api/routes/optimise` |
| Health-aware vehicle assignment with explained exclusions | `RouteEligibility`, `RouteService` |
| Fleet utilisation, under- and over-used vehicles | `GET /api/fleet/utilisation` |
| Authentication, four roles, audit log | `SecurityConfig`, `auth` package, User Management page |
| PDF and Excel reports | `report` package, Reports page |
| Single-server deployment with HTTPS, backups, monitoring, CI | `docker-compose.prod.yml`, [deployment.md](deployment.md), `.github/workflows/ci.yml` |

## Results

| Measure | Result |
|---|---|
| Anomaly detection (XGBoost, time-based test split) | Precision 0.99, recall 0.97, F1 0.98; the unsupervised Isolation Forest baseline reached F1 0.25 |
| Remaining useful life, mean absolute error (leave-one-vehicle-out) | Brakes 2.1 days, battery 1.8, tyres 3.4, engine 2.3; 83–95% of predictions within ±7 days |
| Driver score | Separates the simulator's driver profiles: calm 97, normal 86–87, aggressive 45–46 |
| Route optimisation, 10 stops, 5 vehicles on offer | 3 vehicles excluded with reasons; 2 routes of 5 stops, 142 km by road in total |
| Automated tests | 50 backend (35 unit, 15 integration against real services), 32 ML service, 14 end-to-end in a browser, simulator self-check; 93% and 94% line coverage |
| Load | 100 vehicles a second: every reading stored within about 33 ms; ingestion held to 800 a second ([performance.md](performance.md)) |

Figures are from simulated data; see the caveats in [model_report.md](model_report.md).

## Technology

| Layer | Choice |
|---|---|
| Vehicles | Python simulator publishing MQTT (Mosquitto) |
| Backend | Java 21, Spring Boot 3.5, Flyway, Spring Security (JWT) |
| Storage | TimescaleDB (PostgreSQL 16), Redis for live twins, MinIO for models, reports and backups |
| Machine learning | Python 3.11, FastAPI, XGBoost, scikit-learn, Google OR-Tools |
| Routing | OSRM on an OpenStreetMap extract |
| Dashboard | Angular 22, Leaflet, ECharts, STOMP over WebSocket |
| Operations | Docker Compose, nginx, Let's Encrypt, GitHub Actions, Prometheus and Grafana (optional) |

## Limitations and further work

- All data is simulated. Wear is deliberately fast so that 90 days contain several failures per
  part; the models learn the simulator's wear curves, not real components, and on real vehicles
  wear indicators such as pad thickness are measured far less often.
- The fleet is five vehicles, which makes the model evaluation sensitive to the simulator run.
- Scores are per vehicle, assuming one driver per vehicle.
- Route optimisation has no vehicle capacities and no live traffic.
- Thresholds are configurable but changing them needs a restart.
- All devices share one MQTT credential; the backend runs as a single instance.
- Backups are kept on the same server unless mirrored elsewhere.

Natural next steps: a pilot with real telemetry from a small number of vehicles, per-vehicle
device credentials, capacities and traffic in routing, and editing thresholds from the dashboard.
