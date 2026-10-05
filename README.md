# Fleet Twin

Foundation for a digital-twin based predictive fleet maintenance system. This is the skeleton
only: infrastructure, schema, and one working data path
(simulator → MQTT → backend → TimescaleDB). No business features yet.

```
fleet-twin/
  backend/      Spring Boot 3.5, Java 21, Maven
  frontend/     Angular 22
  ml-service/   Python 3.11, FastAPI (placeholder endpoints)
  simulator/    Python MQTT telemetry simulator
  infra/        docker-compose.yml, mosquitto config, db init scripts
  docs/         architecture notes
```

## Prerequisites

| Tool | Version used |
|---|---|
| Java (JDK) | 21 |
| Maven | 3.9 |
| Node.js / npm | 24 / 11 |
| Angular CLI | 22 |
| Python | 3.11 (`python3.11` must be on the PATH) |
| Docker Desktop | running, with Compose v2+ |

## Ports

| Port | Service |
|---|---|
| 5432 | PostgreSQL / TimescaleDB |
| 1883 | Mosquitto (MQTT) |
| 9000 / 9001 | MinIO API / console |
| 6379 | Redis |
| 8080 | Backend |
| 8000 | ML service |
| 4200 | Frontend |

The four infra ports can be changed in `.env` (`POSTGRES_PORT`, `MQTT_PORT`, `MINIO_API_PORT`,
`MINIO_CONSOLE_PORT`, `REDIS_PORT`); the backend and simulator read the same file.

## First-time setup

```bash
cp .env.example .env          # then change the passwords
(cd ml-service && python3.11 -m venv .venv && .venv/bin/pip install -r requirements.txt)
(cd simulator  && python3.11 -m venv .venv && .venv/bin/pip install -r requirements.txt)
(cd frontend   && npm ci)
```

`.env` is git-ignored. Every credential (Postgres, MQTT, MinIO, Redis) comes from it.

## Start and stop

Run each part in its own terminal, in this order.

### 1. Infrastructure

```bash
docker compose up -d          # from the repo root
docker compose ps             # every service should say "healthy"
docker compose down           # stop, keep data
docker compose down -v        # stop and delete all data volumes
```

### 2. Backend

```bash
cd backend
mvn spring-boot:run           # Ctrl+C to stop
```

- Health: http://localhost:8080/actuator/health
- Swagger UI: http://localhost:8080/swagger-ui.html
- Flyway creates the schema and seeds 5 vehicles (ids 1–5) on first start.
- Must be started from `backend/` so it finds `../.env`.

### 3. ML service

```bash
cd ml-service
.venv/bin/uvicorn app.main:app --port 8000     # Ctrl+C to stop
```

Docs at http://localhost:8000/docs. Or as a container:
`docker build -t fleet-twin-ml ml-service && docker run --rm -p 8000:8000 fleet-twin-ml`.

```bash
curl localhost:8000/health
curl -X POST localhost:8000/anomaly      -H 'Content-Type: application/json' -d '{"vehicle_id":1,"telemetry":{"engine_temp":118}}'
curl -X POST localhost:8000/health-score -H 'Content-Type: application/json' -d '{"vehicle_id":1}'
curl -X POST localhost:8000/rul          -H 'Content-Type: application/json' -d '{"vehicle_id":1,"component":"brake_pads"}'
```

### 4. Simulator

```bash
cd simulator
.venv/bin/python simulator.py                                   # Ctrl+C to stop
.venv/bin/python simulator.py --vehicles 5 --interval 2 --fault-rate 0.05
.venv/bin/python simulator.py --self-check                      # model assertions, no broker needed
```

Defaults come from `SIM_VEHICLES`, `SIM_INTERVAL` and `SIM_FAULT_RATE` in `.env`; CLI args win.
Only vehicle ids that exist in the `vehicles` table are stored, so keep `--vehicles` at 5 or
below until more vehicles are added.

Check that rows are arriving:

```bash
docker exec fleet-twin-timescaledb-1 psql -U fleettwin -d fleettwin -c "SELECT count(*) FROM telemetry;"
```

### 5. Frontend

```bash
cd frontend
npm start                     # http://localhost:4200, Ctrl+C to stop
```

The Fleet Overview page shows "Backend connected" or "Backend unreachable". The backend URL is in
`frontend/src/environments/environment.ts`.

## Tests

```bash
(cd backend && mvn test)                                   # telemetry payload parsing
(cd simulator && .venv/bin/python simulator.py --self-check)
```

## Troubleshooting

- **`Cannot connect to the Docker daemon`**: Docker Desktop isn't running. Start it
  (`open -a Docker` on macOS) and wait for it to finish starting.
- **`port is already allocated` / `Address already in use`**: find the owner with
  `lsof -nP -iTCP:<port> -sTCP:LISTEN`. For infra ports, change the matching `*_PORT` in `.env` and
  re-run `docker compose up -d`. For the others: `SERVER_PORT=8081 mvn spring-boot:run` (and update
  `environment.ts`), `uvicorn ... --port 8001`, or `npm start -- --port 4201` (and add the new
  origin to `fleet.cors.allowed-origins` in `application.yml`).
- **A container stays `unhealthy`**: `docker compose logs <service>`.
- **Backend fails with `password authentication failed`**: Postgres only reads
  `POSTGRES_PASSWORD` when the data volume is first created. After changing it, run
  `docker compose down -v` (this deletes the data) and start again.
- **Backend fails with `Could not resolve placeholder 'POSTGRES_DB'`**: `.env` is missing, or the
  backend wasn't started from `backend/`.
- **Backend logs `Not authorized to connect` (MQTT)**: `MQTT_USERNAME`/`MQTT_PASSWORD` changed
  after Mosquitto started. `docker compose up -d --force-recreate mosquitto`.
- **Flyway `checksum mismatch`**: an applied migration file was edited. Add a new `V3__...sql`
  instead, or reset the dev database with `docker compose down -v`.
- **Simulator runs but the row count doesn't grow**: check the backend is running and look for
  `Dropped telemetry` in its log (unknown vehicle id or malformed JSON).
- **Frontend shows "Backend unreachable"**: the backend is down, or the page is served from an
  origin other than `http://localhost:4200` (CORS).
- **MinIO image**: MinIO no longer publishes official images to Docker Hub or Quay, so compose
  uses the Chainguard build pinned by digest. To upgrade, pull `cgr.dev/chainguard/minio:latest`
  and replace the digest in `infra/docker-compose.yml`.
