#!/bin/sh
# Makes one simulated vehicle develop a fault right now, for demos.
#   scripts/demo-fault.sh <vehicle id> [overheating|vibration_spike|low_tyre_pressure|low_battery|fuel_theft]
# The live simulator must be running; the fault lasts about a minute.
set -eu
cd "$(dirname "$0")/.."
vehicle=${1:?usage: scripts/demo-fault.sh <vehicle id> [fault]}
fault=${2:-overheating}
docker compose exec -T mosquitto sh -c \
  "mosquitto_pub -h localhost -u \"\$MQTT_USERNAME\" -P \"\$MQTT_PASSWORD\" -q 1 -t fleet/$vehicle/fault -m '{\"fault\":\"$fault\"}'"
echo "Asked vehicle $vehicle to start: $fault"
