#!/bin/sh
# One-command development setup for macOS and Linux. Safe to run again: it skips what is already done.
#
#   ./setup.sh             check prerequisites, create .env, install dependencies, start the
#                          infrastructure, create the schema and load 90 days of simulated history
#   ./setup.sh --no-data   everything except the simulated history
#
# Afterwards, start the four programs as the README describes ("Start and stop").
set -eu
cd "$(dirname "$0")"
DATA=1
[ "${1:-}" = "--no-data" ] && DATA=0

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\033[31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

say "1/6 Checking prerequisites"
missing=""
need() { command -v "$1" > /dev/null 2>&1 || missing="$missing\n  - $2"; }
need docker       "Docker Desktop or Docker Engine (https://docs.docker.com/get-docker/)"
need java         "Java 21 (JDK)"
need mvn          "Maven 3.9"
need node         "Node.js 22 or later, with npm"
need npm          "npm (comes with Node.js)"
need python3.11   "Python 3.11, as python3.11 on the PATH"
need curl         "curl"
[ -z "$missing" ] || fail "missing:$(printf "$missing")"
docker info > /dev/null 2>&1 || fail "Docker is installed but not running. Start Docker Desktop (macOS: open -a Docker) and run this again."
docker compose version > /dev/null 2>&1 || fail "the Docker Compose plugin (v2) is missing"
java_major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
[ "${java_major:-0}" -ge 21 ] || fail "Java 21 or later is needed; found version ${java_major:-unknown}"
node_major=$(node -p 'process.versions.node.split(".")[0]')
[ "$node_major" -ge 22 ] || fail "Node.js 22 or later is needed; found $node_major"
if [ "$(uname)" = Darwin ] && [ ! -e /opt/homebrew/opt/libomp/lib/libomp.dylib ] && [ ! -e /usr/local/opt/libomp/lib/libomp.dylib ]; then
    fail "XGBoost needs the OpenMP runtime on macOS: brew install libomp"
fi
echo "ok: docker, java $java_major, maven, node $node_major, python3.11"

say "2/6 Creating .env"
if [ -f .env ]; then
    echo ".env already exists, leaving it alone"
else
    # every change-me value in the example becomes its own random secret
    python3.11 - <<'PY'
import pathlib, re, secrets
text = pathlib.Path(".env.example").read_text()
pathlib.Path(".env").write_text(re.sub(r"change-me[\w-]*", lambda _: secrets.token_urlsafe(32), text))
PY
    chmod 600 .env
    echo "created .env with generated passwords; the dashboard login is ADMIN_USERNAME / ADMIN_PASSWORD in it"
fi

say "3/6 Installing dependencies (a few minutes the first time)"
[ -d ml-service/.venv ] || python3.11 -m venv ml-service/.venv
ml-service/.venv/bin/pip install -q -r ml-service/requirements-dev.txt
[ -d simulator/.venv ] || python3.11 -m venv simulator/.venv
simulator/.venv/bin/pip install -q -r simulator/requirements.txt
(cd frontend && npm ci --silent)
(cd backend && mvn -q -DskipTests package)
echo "ok"

say "4/6 Starting the infrastructure (TimescaleDB, Mosquitto, MinIO, Redis, OSRM)"
docker compose up -d --wait timescaledb mosquitto minio redis
docker compose up -d osrm    # prepares its map in the background; route planning uses straight lines until it is ready
echo "ok"

say "5/6 Creating the database schema and the admin user"
port=$(sed -n 's/^SERVER_PORT=//p' .env); port=${port:-8080}
if curl -sf "http://localhost:$port/actuator/health" > /dev/null; then
    echo "a backend is already running on port $port, using it"
    backend_pid=""
else
    (cd backend && exec java -jar target/backend-*.jar > ../setup-backend.log 2>&1) &
    backend_pid=$!
    trap '[ -n "$backend_pid" ] && kill "$backend_pid" 2> /dev/null || true' EXIT
    for i in $(seq 1 60); do
        curl -sf "http://localhost:$port/actuator/health" > /dev/null && break
        kill -0 "$backend_pid" 2> /dev/null || fail "the backend stopped; see setup-backend.log"
        sleep 2
    done
    curl -sf "http://localhost:$port/actuator/health" > /dev/null || fail "the backend did not start; see setup-backend.log"
fi
echo "ok"

if [ "$DATA" = 1 ]; then
    say "6/6 Loading 90 days of simulated history and training the remaining-life models"
    env_value() { sed -n "s/^$1=//p" .env; }
    rows=$(docker compose exec -T timescaledb sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from telemetry"')
    if [ "$rows" -gt 0 ]; then
        echo "the database already has $rows telemetry rows, not adding history (scripts/demo-reset.sh starts over)"
    else
        simulator/.venv/bin/python simulator/simulator.py --fast-forward 90 --seed 42
        token=$(curl -sf -X POST "http://localhost:$port/api/auth/login" -H 'Content-Type: application/json' \
            -d "{\"username\":\"$(env_value ADMIN_USERNAME)\",\"password\":\"$(env_value ADMIN_PASSWORD)\"}" \
            | python3.11 -c 'import sys, json; print(json.load(sys.stdin)["accessToken"])')
        curl -sf -X POST -H "Authorization: Bearer $token" "http://localhost:$port/api/admin/reanalyse" > /dev/null
        (cd ml-service && .venv/bin/python -m training.train_rul --no-report | tail -6)
    fi
else
    say "6/6 Skipping simulated history (--no-data)"
fi

say "Done. Start each of these in its own terminal:"
cat <<'TXT'
  cd backend    && mvn spring-boot:run
  cd ml-service && .venv/bin/uvicorn app.main:app --port 8000
  cd simulator  && .venv/bin/python simulator.py
  cd frontend   && npm start          # then open http://localhost:4200

Sign in with ADMIN_USERNAME / ADMIN_PASSWORD from .env.
The anomaly model needs about 45 minutes of live simulator data before it can be trained:
see "Retraining the models" in the README. Everything else works straight away.
TXT
