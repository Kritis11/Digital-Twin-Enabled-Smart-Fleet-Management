#!/bin/sh
# Puts the DEVELOPMENT stack into a clean, repeatable demo state:
#   5 vehicles with three driver profiles, 90 days of history with failures and repairs, freshly trained
#   remaining-life models, live simulated vehicles, a mix of healthy and failing parts, open alerts and
#   recommendations, and at least one vehicle fit for route planning.
#
# THIS DELETES all telemetry, trips, events, alerts, recommendations, maintenance records, reports
# and the audit log. Users and trained anomaly models are kept. It asks before doing anything.
#
#   scripts/demo-reset.sh          asks for confirmation
#   scripts/demo-reset.sh --yes    no questions (for scripts)
#
# Needs: the infrastructure, the backend and the ML service running (README, "Start and stop").
set -eu
cd "$(dirname "$0")/.."
env_value() { sed -n "s/^$1=//p" .env; }
port=$(env_value SERVER_PORT); port=${port:-8080}
api="http://localhost:$port"
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

curl -sf "$api/actuator/health" > /dev/null || { echo "The backend is not running on port $port." >&2; exit 1; }
curl -sf "http://localhost:8000/health" > /dev/null || { echo "The ML service is not running on port 8000." >&2; exit 1; }

if [ "${1:-}" != "--yes" ]; then
    printf 'This deletes ALL fleet data in the development database (users are kept). Type RESET to go on: '
    read -r answer
    [ "$answer" = RESET ] || { echo "Nothing changed."; exit 1; }
fi

say "1/6 Stopping the simulator and emptying the data tables"
pkill -f 'simulator.py' 2> /dev/null && sleep 2 || true
docker compose exec -T timescaledb sh -c 'psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
    TRUNCATE telemetry, trips, driving_events, alerts, maintenance_recommendations, maintenance_records, reports, audit_log RESTART IDENTITY"'
docker compose exec -T redis sh -c 'redis-cli --scan --pattern "twin:*" | xargs -r redis-cli del > /dev/null'
rm -f simulator/state.json

say "2/6 Writing 90 days of history"
simulator/.venv/bin/python simulator/simulator.py --fast-forward 90 --seed 42

say "3/6 Rebuilding trips, driving events and scores"
token=$(curl -sf -X POST "$api/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$(env_value ADMIN_USERNAME)\",\"password\":\"$(env_value ADMIN_PASSWORD)\"}" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["accessToken"])')
auth="Authorization: Bearer $token"
curl -sf -X POST -H "$auth" "$api/api/admin/reanalyse"; echo

say "4/6 Training the remaining-life models on that history"
(cd ml-service && .venv/bin/python -m training.train_rul --no-report | grep selected)
curl -sf -X POST http://localhost:8000/model/reload > /dev/null

say "5/6 Starting the live simulator (no random faults: trigger them with scripts/demo-fault.sh)"
# detached from this script's terminal and pipes, so the script can end while it runs on
nohup simulator/.venv/bin/python -u simulator/simulator.py --fault-rate 0 > simulator/simulator.log 2>&1 < /dev/null &
# every vehicle has to report once (this raises the alerts for worn parts) and get its predictions
for i in $(seq 1 60); do
    ready=$(curl -sf -H "$auth" "$api/api/vehicles" | python3 -c 'import sys, json; print(sum(1 for t in json.load(sys.stdin) if t.get("lastSeen") and t.get("rul")))')
    [ "$ready" -ge 5 ] && break
    sleep 3
done

say "6/6 Running the recommendation rules"
curl -sf -X POST -H "$auth" "$api/api/recommendations/recompute"; echo

say "Demo state"
curl -sf -H "$auth" "$api/api/vehicles" | python3 -c '
import sys, json
for t in json.load(sys.stdin):
    bad = ", ".join(f"{k} {v}" for k, v in t["components"].items() if v != "OK") or "all parts OK"
    rul = min(t["rul"].items(), key=lambda kv: kv[1]["days"]) if t.get("rul") else None
    print(f"  {t[\"registration\"]}: driver score {t.get(\"driverScore\")}, {bad}" + (f", lowest remaining life: {rul[0]} {rul[1][\"days\"]:.0f} d" if rul else ""))'
curl -sf -H "$auth" "$api/api/alerts" | python3 -c 'import sys, json; a = json.load(sys.stdin); print(f"  {len(a)} open alerts, {sum(1 for x in a if x[\"severity\"] == \"CRITICAL\")} critical")'
curl -sf -H "$auth" "$api/api/recommendations?status=OPEN" | python3 -c '
import sys, json, collections
r = json.load(sys.stdin); c = collections.Counter(x["priority"] for x in r)
print(f"  {len(r)} open recommendations: " + ", ".join(f"{c[p]} {p}" for p in ("URGENT", "HIGH", "MEDIUM", "LOW") if c[p]))'
curl -sf -X POST -H "$auth" -H 'Content-Type: application/json' "$api/api/routes/optimise" \
    -d '{"depot":{"lat":12.9716,"lng":77.5946},"stops":[{"lat":12.9698,"lng":77.75},{"lat":12.8452,"lng":77.6602}]}' | python3 -c '
import sys, json
r = json.load(sys.stdin)
print(f"  route planning: {5 - len(r[\"excluded\"])} of 5 vehicles are fit, {len(r[\"excluded\"])} left out with reasons")'
echo "The simulator is running in the background (log: simulator/simulator.log; stop it with: pkill -f simulator.py)."
