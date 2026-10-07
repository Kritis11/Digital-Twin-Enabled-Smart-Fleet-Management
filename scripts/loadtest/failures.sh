#!/bin/sh
# Failure test on the load-test stack: with 100 simulated vehicles reporting every second, stops the ML
# service, then Redis, then the MQTT broker for a minute each and records what the rest of the system
# does (same measurements as a load run, every 5 seconds).
#
#   scripts/loadtest/failures.sh <out.csv>
#
# Timeline (seconds): 60-120 ML service down, 180-240 Redis down, 300-360 broker down, ends at 450.
set -eu
cd "$(dirname "$0")/../.."
out=$1
project=${LOAD_PROJECT:-fleet-load}
compose() { docker compose -p "$project" --env-file .env.load -f docker-compose.prod.yml --profile demo "$@"; }
password=$(grep '^ADMIN_PASSWORD=' .env.load | cut -d= -f2)

docker rm -f "$project-sim" > /dev/null 2>&1 || true
compose run -d --name "$project-sim" simulator --vehicles 100 --interval 1 --fresh > /dev/null
sleep 20
python3 scripts/loadtest/collect.py "$project" "$password" "$out" 450 --every 5 &
collector=$!
started=$(date +%s)
at() { sleep $(( $1 - ($(date +%s) - started) )); echo "t=$1 $2"; }
for service in ml-service redis mosquitto; do
    case $service in ml-service) down=60 ;; redis) down=180 ;; mosquitto) down=300 ;; esac
    at $down "stopping $service"
    compose stop "$service" > /dev/null 2>&1
    at $((down + 60)) "starting $service"
    compose start "$service" > /dev/null 2>&1
done
wait $collector
docker rm -f "$project-sim" > /dev/null
