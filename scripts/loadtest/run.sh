#!/bin/sh
# One load-test run against an isolated copy of the production stack (compose project "fleet-load",
# or $LOAD_PROJECT).
#
#   scripts/loadtest/run.sh up                              build and start the stack (needs .env.load, see below)
#   scripts/loadtest/run.sh run <vehicles> <interval s> <minutes> <out.csv>
#   scripts/loadtest/run.sh compose <any docker compose arguments>
#
# .env.load is a copy of .env.prod.example with DOMAIN=localhost, the change-me values replaced and
# LOGIN_RATE_PER_MINUTE raised. The stack has its own volumes, so nothing else is touched; remove it
# afterwards with `scripts/loadtest/run.sh compose down -v`.
#
# On a laptop, keep it awake for the duration (macOS: `caffeinate -dims &`): a machine that dozes off
# mid-run produces measurements that look like a slow system.
set -eu
cd "$(dirname "$0")/../.."
project=${LOAD_PROJECT:-fleet-load}
compose() { docker compose -p "$project" --env-file .env.load -f docker-compose.prod.yml --profile demo "$@"; }

case "${1:-}" in
up)
    compose up -d --build --wait backend ml-service nginx
    ;;
compose)
    shift
    compose "$@"
    ;;
run)
    vehicles=$2 interval=$3 minutes=$4 out=$5
    password=$(grep '^ADMIN_PASSWORD=' .env.load | cut -d= -f2)
    # the simulator's vehicle ids must exist in the vehicles table
    compose exec -T timescaledb sh -c "psql -q -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"
        INSERT INTO vehicles (id, registration, make, model, year)
        SELECT g, 'LOAD' || lpad(g::text, 4, '0'), 'Load', 'Test', 2024 FROM generate_series(6, $vehicles) g
        ON CONFLICT DO NOTHING\""
    docker rm -f "$project-sim" > /dev/null 2>&1 || true
    compose run -d --name "$project-sim" simulator --vehicles "$vehicles" --interval "$interval" --fresh > /dev/null
    echo "simulating $vehicles vehicles every $interval s for $minutes min"
    sleep 20   # let the first readings arrive before measuring
    python3 scripts/loadtest/collect.py "$project" "$password" "$out" $((minutes * 60)) &
    collector=$!
    # half-way through, open the dashboard in a browser and see how it copes (needs `npm ci` in e2e/)
    sleep $((minutes * 30))
    (cd e2e && node tools/dashboard-load.mjs "$password" 20) > "${out%.csv}.dashboard.json" || echo "dashboard probe failed"
    wait $collector
    docker rm -f "$project-sim" > /dev/null
    ;;
*)
    sed -n '2,10p' "$0"; exit 1
    ;;
esac
