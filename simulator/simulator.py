#!/usr/bin/env python3
"""Publishes simulated vehicle telemetry to fleet/{vehicleId}/telemetry as JSON.

Settings come from CLI args, falling back to the repo-root .env (SIM_* and MQTT_*).
Run `python simulator.py --self-check` to sanity-check the model without a broker.
"""

import argparse
import json
import math
import os
import random
import time
from datetime import datetime, timezone
from pathlib import Path

import paho.mqtt.client as mqtt
from dotenv import load_dotenv

# One depot per seeded vehicle (Bengaluru, Mysuru, Pune, Chennai, Delhi); extra vehicles reuse them.
DEPOTS = [(12.9716, 77.5946), (12.2958, 76.6394), (18.5204, 73.8567), (13.0827, 80.2707), (28.6139, 77.2090)]
# Route = closed loop around the depot, as (north_km, east_km) offsets.
LOOP_KM = [(0, 0), (6, 1), (8, 7), (3, 11), (-2, 6)]

FAULTS = ["overheating", "vibration_spike", "low_tyre_pressure", "low_battery"]
DTC = {"overheating": ["P0217"], "low_battery": ["P0562"]}
TYRES = ["fl", "fr", "rl", "rr"]


class Vehicle:
    def __init__(self, vehicle_id: int, rng: random.Random):
        self.id = vehicle_id
        self.rng = rng
        lat0, lng0 = DEPOTS[(vehicle_id - 1) % len(DEPOTS)]
        km_per_deg_lng = 111.32 * math.cos(math.radians(lat0))
        self.route = [(lat0 + n / 110.57, lng0 + e / km_per_deg_lng) for n, e in LOOP_KM]
        self.km_per_deg_lng = km_per_deg_lng
        self.leg = 0
        self.leg_progress_km = 0.0
        self.speed = 0.0
        self.target_speed = rng.uniform(40, 80)
        self.fuel = rng.uniform(60, 100)
        self.brake_wear = rng.uniform(5, 40)
        self.engine_temp = 70.0
        self.tyres = {t: rng.uniform(31, 34) for t in TYRES}
        self.fault = None
        self.fault_ticks = 0
        self.fault_tyre = None

    def _leg_km(self) -> float:
        (lat1, lng1), (lat2, lng2) = self.route[self.leg], self.route[(self.leg + 1) % len(self.route)]
        return math.hypot((lat2 - lat1) * 110.57, (lng2 - lng1) * self.km_per_deg_lng)

    def _position(self) -> tuple[float, float]:
        (lat1, lng1), (lat2, lng2) = self.route[self.leg], self.route[(self.leg + 1) % len(self.route)]
        f = self.leg_progress_km / self._leg_km()
        return lat1 + (lat2 - lat1) * f, lng1 + (lng2 - lng1) * f

    def step(self, dt: float, fault_rate: float) -> dict:
        rng = self.rng

        # Speed drifts towards a target that changes now and then (traffic, stops).
        if rng.random() < 0.05:
            self.target_speed = rng.choice([0, 20, 40, 60, 80]) + rng.uniform(-5, 5)
        prev_speed = self.speed
        self.speed = max(0.0, self.speed + max(-8.0, min(4.0, self.target_speed - self.speed)) * dt / 2 + rng.gauss(0, 0.5))
        braking = max(0.0, prev_speed - self.speed)

        km = self.speed * dt / 3600
        self.leg_progress_km += km
        while self.leg_progress_km >= self._leg_km():
            self.leg_progress_km -= self._leg_km()
            self.leg = (self.leg + 1) % len(self.route)
        lat, lng = self._position()

        # Fuel: distance-based burn plus idle burn; refuel when nearly empty.
        self.fuel -= km * 0.12 + dt * 0.0003
        if self.fuel < 5:
            self.fuel = 100.0
        self.brake_wear = min(100.0, self.brake_wear + braking * 0.0004)

        # Faults start at random and last a short while.
        if self.fault is None and rng.random() < fault_rate:
            self.fault = rng.choice(FAULTS)
            self.fault_ticks = rng.randint(10, 30)
            self.fault_tyre = rng.choice(TYRES)
        fault = self.fault

        rpm = 800 + self.speed * 28 + rng.gauss(0, 40)
        target_temp = 88 + self.speed * 0.08 + (30 if fault == "overheating" else 0)
        self.engine_temp += (target_temp - self.engine_temp) * 0.2 + rng.gauss(0, 0.3)
        vibration = 0.2 + self.speed * 0.004 + abs(rng.gauss(0, 0.05)) + (rng.uniform(2, 4) if fault == "vibration_spike" else 0)
        battery = (rng.uniform(11.2, 11.8) if fault == "low_battery" else 13.9 + rng.gauss(0, 0.1))
        tyres = {t: p + rng.gauss(0, 0.1) for t, p in self.tyres.items()}
        if fault == "low_tyre_pressure":
            tyres[self.fault_tyre] = rng.uniform(18, 22)

        if fault is not None:
            self.fault_ticks -= 1
            if self.fault_ticks <= 0:
                self.fault = None

        payload = {
            "vehicle_id": self.id,
            "ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "lat": round(lat, 6),
            "lng": round(lng, 6),
            "speed": round(self.speed, 1),
            "engine_temp": round(self.engine_temp, 1),
            "rpm": round(rpm),
            "battery_voltage": round(battery, 2),
            "fuel_level": round(self.fuel, 2),
            "vibration": round(vibration, 3),
            "brake_pad_wear": round(self.brake_wear, 3),
            "dtc_codes": DTC.get(fault, []),
            "injected_fault": fault,
        }
        payload.update({f"tyre_pressure_{t}": round(p, 1) for t, p in tyres.items()})
        return payload


def self_check() -> None:
    rng = random.Random(1)
    v = Vehicle(1, rng)
    rows = [v.step(2.0, 0.05) for _ in range(5000)]
    assert all(0 <= r["speed"] <= 130 for r in rows)
    assert all(0 < r["fuel_level"] <= 100 for r in rows)
    assert all(abs(r["lat"] - DEPOTS[0][0]) < 0.2 and abs(r["lng"] - DEPOTS[0][1]) < 0.2 for r in rows)
    assert rows[-1]["brake_pad_wear"] >= rows[0]["brake_pad_wear"]
    assert {r["injected_fault"] for r in rows} == {None, *FAULTS}
    assert all(r["battery_voltage"] < 12 for r in rows if r["injected_fault"] == "low_battery")
    assert all(min(r[f"tyre_pressure_{t}"] for t in TYRES) < 23 for r in rows if r["injected_fault"] == "low_tyre_pressure")
    assert max(r["engine_temp"] for r in rows if r["injected_fault"] == "overheating") > 110
    healthy = [r for r in rows if r["injected_fault"] is None]
    assert not any(Vehicle(1, random.Random(2)).step(2.0, 0.0)["injected_fault"] for _ in range(100))
    print(f"self-check ok: {len(rows)} ticks, {len(rows) - len(healthy)} faulty")


def main() -> None:
    load_dotenv(Path(__file__).resolve().parent.parent / ".env")
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--vehicles", type=int, default=int(os.getenv("SIM_VEHICLES", "5")), help="number of vehicles (ids 1..N)")
    p.add_argument("--interval", type=float, default=float(os.getenv("SIM_INTERVAL", "2")), help="seconds between publishes")
    p.add_argument("--fault-rate", type=float, default=float(os.getenv("SIM_FAULT_RATE", "0.02")),
                   help="chance per vehicle per tick of starting a fault (0..1)")
    p.add_argument("--host", default=os.getenv("MQTT_HOST", "localhost"))
    p.add_argument("--port", type=int, default=int(os.getenv("MQTT_PORT", "1883")))
    p.add_argument("--seed", type=int, default=None, help="random seed for repeatable runs")
    p.add_argument("--self-check", action="store_true", help="run model assertions and exit (no broker needed)")
    args = p.parse_args()

    if args.self_check:
        return self_check()
    if args.vehicles < 1 or args.interval <= 0 or not 0 <= args.fault_rate <= 1:
        p.error("need --vehicles >= 1, --interval > 0 and 0 <= --fault-rate <= 1")

    rng = random.Random(args.seed)
    vehicles = [Vehicle(i, rng) for i in range(1, args.vehicles + 1)]

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="fleet-twin-simulator")
    client.username_pw_set(os.getenv("MQTT_USERNAME"), os.getenv("MQTT_PASSWORD"))
    client.connect(args.host, args.port)
    client.loop_start()
    print(f"Publishing {args.vehicles} vehicles every {args.interval}s to {args.host}:{args.port} "
          f"(fault rate {args.fault_rate}). Ctrl+C to stop.")
    try:
        while True:
            for v in vehicles:
                msg = v.step(args.interval, args.fault_rate)
                client.publish(f"fleet/{v.id}/telemetry", json.dumps(msg), qos=1)
                if msg["injected_fault"]:
                    print(f"vehicle {v.id}: {msg['injected_fault']}")
            time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
