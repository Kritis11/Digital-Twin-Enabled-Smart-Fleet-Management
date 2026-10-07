# Configuration reference

Every setting, where it is read, and its default. There are two layers:

1. **Environment variables**: credentials, addresses and the few things that differ between
   machines. In development they come from `.env` in the repository root (copy `.env.example`, or
   run `setup.sh`); in production from `.env.prod` (copy `.env.prod.example`).
2. **`backend/src/main/resources/application.yml`**, under `fleet:`: every threshold, weight,
   interval and schedule of the application logic, each with a comment. Any of them can also be
   overridden without editing the file, by an environment variable named after its path:
   `fleet.routes.min-rul-days` becomes `FLEET_ROUTES_MIN_RUL_DAYS`.

Changes take effect when the service is restarted.

## Required: no default

These are secrets. The services refuse to start without them, and the backend also refuses the
example values of the first two.

| Variable | Used by | Notes |
|---|---|---|
| `JWT_SECRET` | backend | Signs login tokens. At least 32 characters: `openssl rand -base64 48`. Changing it signs everyone out |
| `ADMIN_PASSWORD` | backend | Password of the first admin, at least 10 characters. Read only while there are no users |
| `POSTGRES_PASSWORD` | database, backend, ML service, simulator (fast-forward), backup | Fixed when the database volume is first created |
| `MQTT_PASSWORD` | broker, backend, simulator, devices | |
| `MINIO_ROOT_PASSWORD` | MinIO, backend, ML service, backup | At least 8 characters |
| `REDIS_PASSWORD` | Redis, backend | |
| `DOMAIN` | nginx, certbot, backend (production only) | The public host name |

## Optional

| Variable | Default | Used by | Meaning |
|---|---|---|---|
| `ADMIN_USERNAME` | (none: no admin is created) | backend | Username of the first admin |
| `POSTGRES_DB`, `POSTGRES_USER` | `fleettwin` | database, backend, ML service, simulator | Database name and user |
| `POSTGRES_HOST`, `POSTGRES_PORT` | `localhost`, `5432` | backend, ML service, simulator | Where the database is |
| `MQTT_USERNAME` | `fleettwin` | broker, backend, simulator | |
| `MQTT_HOST`, `MQTT_PORT` | `localhost`, `1883` | backend, simulator | Where the broker is |
| `MINIO_ROOT_USER` | `fleettwin` | MinIO, backend, ML service, backup | |
| `MINIO_ENDPOINT` | `http://localhost:9000` (backend), `localhost:9000` (ML service) | backend, ML service | Where MinIO is |
| `MINIO_API_PORT`, `MINIO_CONSOLE_PORT` | `9000`, `9001` | development compose | Host ports |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | backend | Where Redis is |
| `REDIS_TIMEOUT` | `500ms` | backend | Longest a twin read or write may take before it counts as failed |
| `SERVER_PORT` | `8080` | backend | API port |
| `MANAGEMENT_PORT` | same as `SERVER_PORT` | backend | Actuator port. Production sets 8081 and does not publish it |
| `ML_SERVICE_URL` | `http://localhost:8000` | backend | Where the ML service is |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | backend | The one origin allowed to call the API and open the WebSocket. Production: `https://$DOMAIN` |
| `OSRM_PBF_URL` | Karnataka extract | OSRM | OpenStreetMap extract to route on |
| `OSRM_PORT` | `5001` | development compose | Host port of OSRM |
| `OSRM_URL` | `http://localhost:5001` | ML service | Where OSRM is |
| `OSRM_TIMEOUT_S`, `OSRM_MAX_SNAP_M` | `5`, `2000` | ML service | Timeout per OSRM call; a point further than this from a road counts as outside the map |
| `ROUTE_FALLBACK_SPEED_KMH`, `ROUTE_FALLBACK_DETOUR` | `40`, `1.3` | ML service | Speed and road-versus-straight-line factor used without OSRM |
| `ROUTE_MAX_SECONDS` | `86400` | ML service | Longest route the solver will plan |
| `MODEL_BUCKET`, `MINIO_SECURE` | `models`, `false` | ML service | Bucket for models; whether to use TLS to MinIO |
| `HEALTH_WARNING_PENALTY`, `HEALTH_CRITICAL_PENALTY`, `HEALTH_ANOMALY_WEIGHT` | `10`, `25`, `30` | ML service | Health score weights |
| `RUL_HISTORY_DAYS`, `RUL_CACHE_SECONDS` | `120`, `30` | ML service | History read for a remaining-life prediction, and how long it is cached |
| `RUL_HARSH_BRAKE_MS2`, `RUL_RAPID_ACCEL_MS2`, `RUL_SPEED_LIMIT_KMH` | `3.0`, `2.5`, `80` | ML service | Thresholds behind the driver-aggressiveness feature; keep in step with `fleet.driving` |
| `REPORTS_BUCKET` | `reports` | backend | Bucket for generated reports |
| `REPORTS_EMAIL_ENABLED` | `false` | backend | Weekly reports by email |
| `REPORTS_EMAIL_TO`, `REPORTS_EMAIL_FROM`, `REPORTS_EMAIL_CRON` | empty, `fleet-twin@localhost`, `0 0 6 * * MON` | backend | Recipients (comma-separated), sender, schedule |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` | empty, `587`, empty, empty, `true`, `true` | backend | Mail server for the weekly reports |
| `SIM_VEHICLES`, `SIM_INTERVAL`, `SIM_FAULT_RATE` | `5`, `2`, `0.02` | simulator | Fleet size, seconds between readings, chance of a fault per vehicle per reading |
| `SIM_STATE_FILE` | `simulator/state.json` | simulator | Where wear state is kept between runs |

Production only (`.env.prod`):

| Variable | Default | Meaning |
|---|---|---|
| `LETSENCRYPT_EMAIL` | empty: stay on a self-signed certificate | Address for certificate expiry warnings |
| `LETSENCRYPT_STAGING` | empty | Set to 1 to use Let's Encrypt's staging service while testing |
| `LOGIN_RATE_PER_MINUTE` | `10` | Login attempts per client address before nginx answers 429 |
| `MQTT_TLS_PORT` | `8883` | Port for MQTT over TLS, with `docker-compose.mqtt-tls.yml` |
| `BACKUP_INTERVAL_HOURS`, `BACKUP_RETENTION_DAYS` | `24`, `14` | Database dumps to MinIO |
| `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | `admin`, (none) | With `--profile monitoring` |

## Application logic (`application.yml`, under `fleet:`)

| Block | What it controls |
|---|---|
| `twin` | When a vehicle counts as offline or moving; how often that is checked |
| `rules` | The threshold rules that give each component its status |
| `alerts` | Cap on alerts returned by one request |
| `driving` | Driving event thresholds, trip detection, driver score weights and severity multipliers ([driver_score.md](driver_score.md)) |
| `fuel` | Tank size, idling consumption, fuel-drop and low-efficiency thresholds |
| `ml` | Scoring and remaining-life intervals, timeout, the telemetry window sent for scoring, the score at which an anomaly is critical |
| `recommendations` | Schedule, priority rules, due dates, and each component's actions and service intervals |
| `routes` | Minimum remaining life for a route, how much fuel efficiency and driver score count, balancing, solver time, limits |
| `utilisation` | Working hours per day and the under- and over-used bands |
| `reports` | Limits and the weekly email |
| `security` | Token lifetimes and minimum password length |
| `telemetry` | Bucket counts for telemetry history |

Dashboard settings (refresh intervals, colour bands, map tiles, route colours) are in
`frontend/src/environments/settings.ts` and are fixed when the dashboard is built.
