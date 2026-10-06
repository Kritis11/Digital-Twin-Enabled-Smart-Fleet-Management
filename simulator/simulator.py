#!/usr/bin/env python3
"""Simulated vehicle telemetry.

Live mode publishes to fleet/{vehicleId}/telemetry as JSON and listens on fleet/{vehicleId}/maintenance
for {"component": ...} commands that reset a component's wear.

Fast-forward mode (--fast-forward DAYS) generates history ending now and writes it straight to
TimescaleDB, with component failures and the maintenance records that reset wear.

Settings come from CLI args, falling back to the repo-root .env (SIM_*, MQTT_*, POSTGRES_*).
Run `python simulator.py --self-check` to sanity-check the model without a broker or database.
"""

import argparse
import io
import json
import math
import os
import random
import signal
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import paho.mqtt.client as mqtt
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
STATE_FILE = Path(__file__).resolve().parent / "state.json"

# One depot per seeded vehicle (Bengaluru, Mysuru, Pune, Chennai, Delhi); extra vehicles reuse them.
DEPOTS = [(12.9716, 77.5946), (12.2958, 76.6394), (18.5204, 73.8567), (13.0827, 80.2707), (28.6139, 77.2090)]
# Route = closed loop around the depot, as (north_km, east_km) offsets.
LOOP_KM = [(0, 0), (6, 1), (8, 7), (3, 11), (-2, 6)]

# Short sensor faults for the anomaly model; fuel_theft parks the vehicle and drains the tank.
SIGNAL_FAULTS = ["overheating", "vibration_spike", "low_tyre_pressure", "low_battery"]
FAULTS = SIGNAL_FAULTS + ["fuel_theft"]
DTC = {"overheating": ["P0217"], "low_battery": ["P0562"]}
TYRES = ["fl", "fr", "rl", "rr"]

# Driver profile per vehicle id (others are "normal"). This is the ground truth the driver score should recover.
PROFILE_BY_VEHICLE = {1: "calm", 2: "normal", 3: "aggressive", 4: "normal", 5: "aggressive"}
PROFILES = {
    # cruise: target speeds picked from (km/h); idle_weight: how often the target is 0;
    # harsh_*: events per driving hour; corner: speed carried through a turn; fuel/wear: multipliers
    "calm":       {"cruise": [20, 40, 55, 65],  "idle_weight": 0.5, "harsh_brake": 0.3, "harsh_accel": 0.3, "corner": 15, "fuel": 0.93, "wear": 0.85},
    "normal":     {"cruise": [20, 40, 60, 80],  "idle_weight": 1.0, "harsh_brake": 1.5, "harsh_accel": 1.5, "corner": 26, "fuel": 1.00, "wear": 1.00},
    "aggressive": {"cruise": [30, 60, 85, 100], "idle_weight": 2.0, "harsh_brake": 6.0, "harsh_accel": 7.0, "corner": 42, "fuel": 1.20, "wear": 1.30},
}

# Wearing components: telemetry field, value when new, value at which the part fails.
# Wear is deliberately fast (a part lasts weeks, not years) so 90 days holds several lifecycles.
COMPONENTS = {
    "brakes":  {"field": "brake_pad_wear", "new": 0.0,   "fail": 95.0, "noise": 0.3},
    "battery": {"field": "battery_health", "new": 100.0, "fail": 45.0, "noise": 0.4},
    "tyres":   {"field": "tyre_tread",     "new": 8.0,   "fail": 1.6,  "noise": 0.03},
    "engine":  {"field": "engine_health",  "new": 100.0, "fail": 40.0, "noise": 0.4},
}
MAINTENANCE_TYPE = {"brakes": "Brake pad replacement", "battery": "Battery replacement",
                    "tyres": "Tyre replacement", "engine": "Engine service"}
# Share of lifecycles that end in a preventive service before the part fails.
PREVENTIVE_SHARE = 0.2


def used(component: str, value: float) -> float:
    """Fraction of life used: 0 when new, 1 at the failure threshold."""
    c = COMPONENTS[component]
    return (value - c["new"]) / (c["fail"] - c["new"])


class Vehicle:
    def __init__(self, vehicle_id: int, rng: random.Random):
        self.id = vehicle_id
        self.rng = rng
        self.profile_name = PROFILE_BY_VEHICLE.get(vehicle_id, "normal")
        self.profile = PROFILES[self.profile_name]
        lat0, lng0 = DEPOTS[(vehicle_id - 1) % len(DEPOTS)]
        km_per_deg_lng = 111.32 * math.cos(math.radians(lat0))
        self.route = [(lat0 + n / 110.57, lng0 + e / km_per_deg_lng) for n, e in LOOP_KM]
        self.km_per_deg_lng = km_per_deg_lng
        self.leg = 0
        self.leg_progress_km = 0.0
        self.speed = 0.0
        self.target_speed = rng.choice(self.profile["cruise"])
        self.fuel = rng.uniform(60, 100)
        self.engine_temp = 70.0
        self.tyres = {t: rng.uniform(31, 34) for t in TYRES}
        self.odometer_km = rng.uniform(20_000, 120_000)
        self.engine_hours = self.odometer_km / 45
        # Each vehicle wears each part at its own rate; a new part gets a slightly different one.
        self.wear_factor = {c: rng.uniform(0.75, 1.3) for c in COMPONENTS}
        self.part_factor = {c: 1.0 for c in COMPONENTS}
        # Start part-way through each part's life so the fleet isn't synchronised.
        self.wear = {c: v["new"] + rng.uniform(0.0, 0.5) * (v["fail"] - v["new"]) for c, v in COMPONENTS.items()}
        self.fault = None
        self.fault_ticks = 0
        self.fault_tyre = None

    # -- state carried from fast-forward into live mode --

    def state(self) -> dict:
        return {"wear": self.wear, "wear_factor": self.wear_factor, "part_factor": self.part_factor,
                "odometer_km": self.odometer_km, "engine_hours": self.engine_hours, "fuel": self.fuel}

    def restore(self, state: dict) -> None:
        for key, value in state.items():
            setattr(self, key, value)

    def service(self, component: str) -> None:
        """A workshop replaced or serviced the part: wear back to new."""
        if component in COMPONENTS:
            self.wear[component] = COMPONENTS[component]["new"]
            self.part_factor[component] = self.rng.uniform(0.9, 1.1)

    def failed(self) -> list[str]:
        return [c for c in COMPONENTS if used(c, self.wear[c]) >= 1.0]

    def age(self, hours: float) -> None:
        """Battery ages with the calendar, driven or not, and faster as it gets weaker."""
        rate = 0.075 * self.wear_factor["battery"] * self.part_factor["battery"]
        self.wear["battery"] -= rate * hours * (1 + used("battery", self.wear["battery"]))

    # -- movement --

    def _leg_km(self) -> float:
        (lat1, lng1), (lat2, lng2) = self.route[self.leg], self.route[(self.leg + 1) % len(self.route)]
        return math.hypot((lat2 - lat1) * 110.57, (lng2 - lng1) * self.km_per_deg_lng)

    def _position(self) -> tuple[float, float]:
        (lat1, lng1), (lat2, lng2) = self.route[self.leg], self.route[(self.leg + 1) % len(self.route)]
        f = self.leg_progress_km / self._leg_km()
        return lat1 + (lat2 - lat1) * f, lng1 + (lng2 - lng1) * f

    def step(self, dt: float, fault_rate: float, ts: datetime | None = None) -> dict:
        """Advances `dt` seconds and returns the telemetry payload. Works for 2 s live ticks and 30 s history ticks."""
        rng, profile = self.rng, self.profile
        ticks = dt / 2.0  # the model's rates were tuned per 2 s tick

        # Faults start at random and last a short while (live mode only: history passes fault_rate 0).
        if self.fault is None and rng.random() < fault_rate:
            self.fault = rng.choice(FAULTS)
            self.fault_ticks = rng.randint(10, 30)
            self.fault_tyre = rng.choice(TYRES)
        fault = self.fault
        # Fuel goes missing part-way through the stop, once the vehicle has been standing for a while.
        if fault == "fuel_theft" and self.fault_ticks == 5:
            self.fuel = max(6.0, self.fuel - rng.uniform(8, 15))

        # Speed drifts towards a target that changes now and then (traffic, stops).
        if rng.random() < 1 - 0.95 ** ticks:
            idle = rng.random() < 0.2 * profile["idle_weight"]
            self.target_speed = 0.0 if idle else rng.choice(profile["cruise"]) + rng.uniform(-5, 5)
        prev_speed = self.speed
        target = 0.0 if fault == "fuel_theft" else self.target_speed
        self.speed = max(0.0, self.speed + max(-4.0 * dt, min(2.0 * dt, target - self.speed)) + rng.gauss(0, 0.5))
        if fault == "fuel_theft":
            self.speed = 0.0

        km = self.speed * dt / 3600
        self.leg_progress_km += km
        cornered = False
        while self.leg_progress_km >= self._leg_km():
            self.leg_progress_km -= self._leg_km()
            self.leg = (self.leg + 1) % len(self.route)
            cornered = True
        if cornered:
            # How fast the turn is taken is what separates the profiles.
            self.speed = min(self.speed, max(5.0, profile["corner"] + rng.gauss(0, 4)))
        lat, lng = self._position()

        # Peak longitudinal acceleration in this interval (m/s^2), as a telematics unit would report it.
        accel = (self.speed - prev_speed) / 3.6 / dt
        accel_min, accel_max = min(accel, 0.0), max(accel, 0.0)
        harsh_brakes = 0
        if self.speed > 10:
            hours = dt / 3600
            if rng.random() < 1 - math.exp(-profile["harsh_brake"] * hours):
                accel_min = -rng.uniform(3.2, 6.0)
                harsh_brakes = 1
            if rng.random() < 1 - math.exp(-profile["harsh_accel"] * hours):
                accel_max = rng.uniform(2.6, 4.5)
        braking_kmh = max(0.0, prev_speed - self.speed)

        self.odometer_km += km
        self.engine_hours += dt / 3600

        # Fuel: distance-based burn plus idle burn; refuel when nearly empty.
        self.fuel -= (km * 0.12 + dt * 0.0003) * profile["fuel"]
        if self.fuel < 5:
            self.fuel = 100.0

        # Wear. Each part wears faster as it nears the end of its life.
        w, f = profile["wear"], {c: self.wear_factor[c] * self.part_factor[c] for c in COMPONENTS}
        brakes_used = used("brakes", self.wear["brakes"])
        self.wear["brakes"] += f["brakes"] * (km * 0.0035 + harsh_brakes * 0.06 + braking_kmh * 0.0004) * (1 + 0.6 * brakes_used ** 2)
        self.wear["tyres"] -= f["tyres"] * km * 0.0007 * w * (1 + 0.4 * used("tyres", self.wear["tyres"]))
        self.wear["engine"] -= f["engine"] * (dt / 3600) * 0.24 * w * (1 + 0.5 * used("engine", self.wear["engine"]) ** 2)
        self.age(dt / 3600)

        rpm = 800 + self.speed * 28 + rng.gauss(0, 40)
        target_temp = 88 + self.speed * 0.08 + (30 if fault == "overheating" else 0)
        self.engine_temp += (target_temp - self.engine_temp) * (1 - 0.8 ** ticks) + rng.gauss(0, 0.3)
        vibration = 0.2 + self.speed * 0.004 + abs(rng.gauss(0, 0.05)) + (rng.uniform(2, 4) if fault == "vibration_spike" else 0)
        battery = (rng.uniform(11.2, 11.8) if fault == "low_battery" else 13.9 + rng.gauss(0, 0.1))
        tyres = {t: p + rng.gauss(0, 0.1) for t, p in self.tyres.items()}
        if fault == "low_tyre_pressure":
            tyres[self.fault_tyre] = rng.uniform(18, 22)

        if fault is not None:
            self.fault_ticks -= 1
            if self.fault_ticks <= 0:
                self.fault = None

        def reading(component: str, digits: int) -> float:
            c = COMPONENTS[component]
            value = self.wear[component] + rng.gauss(0, c["noise"])
            # sensor noise must not make a new part read better than new (e.g. negative pad wear)
            return round(max(c["new"], value) if c["new"] < c["fail"] else min(c["new"], value), digits)

        payload = {
            "vehicle_id": self.id,
            "ts": (ts or datetime.now(timezone.utc)).isoformat(timespec="milliseconds"),
            "lat": round(lat, 6),
            "lng": round(lng, 6),
            "speed": round(self.speed, 1),
            "engine_temp": round(self.engine_temp, 1),
            "rpm": round(rpm),
            "battery_voltage": round(battery, 2),
            "fuel_level": round(self.fuel, 2),
            "vibration": round(vibration, 3),
            "brake_pad_wear": reading("brakes", 2),
            "battery_health": reading("battery", 2),
            "tyre_tread": reading("tyres", 3),
            "engine_health": reading("engine", 2),
            "odometer_km": round(self.odometer_km, 2),
            "engine_hours": round(self.engine_hours, 3),
            "accel_min": round(accel_min, 2),
            "accel_max": round(accel_max, 2),
            "dtc_codes": DTC.get(fault, []),
            "injected_fault": fault,
        }
        payload.update({f"tyre_pressure_{t}": round(p, 1) for t, p in tyres.items()})
        return payload


# ---------------------------------------------------------------- fast-forward

TELEMETRY_COLUMNS = [
    "vehicle_id", "ts", "lat", "lng", "speed", "engine_temp", "rpm", "battery_voltage", "fuel_level", "vibration",
    "tyre_pressure_fl", "tyre_pressure_fr", "tyre_pressure_rl", "tyre_pressure_rr", "brake_pad_wear",
    "battery_health", "tyre_tread", "engine_health", "odometer_km", "engine_hours", "accel_min", "accel_max",
    "dtc_codes", "injected_fault",
]


def generate_history(vehicles: list[Vehicle], days: int, step_s: float, end: datetime, rng: random.Random):
    """Simulates `days` of shifts ending at `end`. Returns (telemetry rows, maintenance records).

    Each day a vehicle drives one to three shifts between 06:00 and 20:00 UTC and is parked otherwise
    (no rows, which is what makes trips). A part that reaches its failure threshold takes the vehicle
    off the road for the rest of the day and is replaced the next morning; some parts are instead
    serviced preventively a little before they would fail.
    """
    rows, maintenance = [], []
    start_day = (end - timedelta(days=days)).replace(hour=0, minute=0, second=0, microsecond=0)
    # Per vehicle and part: the used-fraction at which it gets a preventive service, or None to run to failure.
    plan = {(v.id, c): _service_plan(rng) for v in vehicles for c in COMPONENTS}

    for day in range(days + 1):
        midnight = start_day + timedelta(days=day)
        for v in vehicles:
            def replace(component: str, cause: str, when: datetime) -> None:
                maintenance.append((v.id, component, MAINTENANCE_TYPE[component], cause, when,
                                    f"Simulated {cause.lower()} maintenance", round(rng.uniform(3000, 40000), 2)))
                v.service(component)
                plan[(v.id, component)] = _service_plan(rng)

            # Morning workshop visit: failed parts are replaced, planned services are done.
            for c in COMPONENTS:
                if used(c, v.wear[c]) >= 1.0:
                    replace(c, "FAILURE", midnight + timedelta(hours=5))
                elif plan[(v.id, c)] is not None and used(c, v.wear[c]) >= plan[(v.id, c)]:
                    replace(c, "PREVENTIVE", midnight + timedelta(hours=5))

            # Parked overnight: the battery still ages, and occasionally fuel goes missing.
            if rng.random() < 0.02:
                v.fuel = max(6.0, v.fuel - rng.uniform(10, 20))

            clock = midnight + timedelta(hours=6, minutes=rng.uniform(0, 90))
            broken = False
            for _ in range(rng.randint(1, 3)):
                shift_end = clock + timedelta(hours=rng.uniform(2, 4))
                v.speed = 0.0
                while clock < shift_end and clock < end and clock.hour < 20:
                    row = v.step(step_s, 0.0, clock)
                    failed = v.failed()
                    if failed:
                        row["injected_fault"] = f"{failed[0]}_failure"
                        broken = True
                    rows.append(row)
                    clock += timedelta(seconds=step_s)
                    if broken:
                        break
                parked = rng.uniform(0.6, 1.5)
                v.age(parked)
                clock += timedelta(hours=parked)
                if broken or clock >= end or clock.hour >= 20:
                    break
            v.age(max(0.0, (midnight + timedelta(days=1) - clock).total_seconds() / 3600))
    return rows, maintenance


def _service_plan(rng: random.Random) -> float | None:
    return rng.uniform(0.8, 0.92) if rng.random() < PREVENTIVE_SHARE else None


def fast_forward(args, rng: random.Random) -> None:
    import psycopg  # only fast-forward talks to the database

    end = datetime.now(timezone.utc)
    start = (end - timedelta(days=args.fast_forward)).replace(hour=0, minute=0, second=0, microsecond=0)
    vehicles = [Vehicle(i, rng) for i in range(1, args.vehicles + 1)]
    dsn = (f"host={os.getenv('POSTGRES_HOST', 'localhost')} port={os.getenv('POSTGRES_PORT', '5432')} "
           f"dbname={os.environ['POSTGRES_DB']} user={os.environ['POSTGRES_USER']} password={os.environ['POSTGRES_PASSWORD']}")
    with psycopg.connect(dsn) as conn, conn.cursor() as cur:
        existing = cur.execute("SELECT count(*) FROM telemetry WHERE ts >= %s", (start,)).fetchone()[0]
        if existing and not args.replace:
            raise SystemExit(f"{existing} telemetry rows already exist since {start:%Y-%m-%d}. History would overlap them; "
                             "re-run with --replace to delete them (and the derived trips, events and maintenance records) first.")
        t0 = time.time()
        rows, maintenance = generate_history(vehicles, args.fast_forward, args.step, end, rng)
        print(f"Simulated {args.fast_forward} days for {len(vehicles)} vehicles in {time.time() - t0:.0f}s: "
              f"{len(rows)} telemetry rows, {len(maintenance)} maintenance records")
        if args.replace:
            for table, column in (("telemetry", "ts"), ("maintenance_records", "performed_at"),
                                  ("trips", "started_at"), ("driving_events", "ts")):
                if cur.execute("SELECT to_regclass(%s)", (table,)).fetchone()[0]:
                    cur.execute(f"DELETE FROM {table} WHERE {column} >= %s", (start,))
        with cur.copy(f"COPY telemetry ({', '.join(TELEMETRY_COLUMNS)}) FROM STDIN") as copy:
            for r in rows:
                r["dtc_codes"] = "{" + ",".join(r["dtc_codes"]) + "}"
                copy.write_row([r[c] for c in TELEMETRY_COLUMNS])
        cur.executemany(
            "INSERT INTO maintenance_records (vehicle_id, component, type, cause, performed_at, description, cost) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s)", maintenance)
    failures = sum(1 for m in maintenance if m[3] == "FAILURE")
    print(f"Wrote history to TimescaleDB ({failures} failures, {len(maintenance) - failures} preventive services).")
    STATE_FILE.write_text(json.dumps({str(v.id): v.state() for v in vehicles}, indent=1))
    print(f"Saved wear state to {STATE_FILE.name}; live mode continues from it.")
    print("Next: POST http://localhost:8080/api/admin/reanalyse to build trips, events and scores from this history.")


# ---------------------------------------------------------------- self-check and live mode

def self_check() -> None:
    rng = random.Random(1)
    v = Vehicle(1, rng)
    rows = [v.step(2.0, 0.05) for _ in range(5000)]
    assert all(0 <= r["speed"] <= 130 for r in rows)
    assert all(0 < r["fuel_level"] <= 100 for r in rows)
    assert all(abs(r["lat"] - DEPOTS[0][0]) < 0.2 and abs(r["lng"] - DEPOTS[0][1]) < 0.2 for r in rows)
    assert rows[-1]["brake_pad_wear"] >= rows[0]["brake_pad_wear"] - 1
    assert rows[-1]["odometer_km"] > rows[0]["odometer_km"]
    assert {r["injected_fault"] for r in rows} == {None, *FAULTS}
    assert all(r["battery_voltage"] < 12 for r in rows if r["injected_fault"] == "low_battery")
    assert all(min(r[f"tyre_pressure_{t}"] for t in TYRES) < 23 for r in rows if r["injected_fault"] == "low_tyre_pressure")
    assert max(r["engine_temp"] for r in rows if r["injected_fault"] == "overheating") > 110
    assert all(r["speed"] == 0 for r in rows if r["injected_fault"] == "fuel_theft")
    healthy = [r for r in rows if r["injected_fault"] is None]
    assert not any(Vehicle(1, random.Random(2)).step(2.0, 0.0)["injected_fault"] for _ in range(100))

    # Speed must not overshoot its target at the coarse history step.
    coarse = Vehicle(2, random.Random(3))
    assert all(coarse.step(30.0, 0.0)["speed"] <= 100 for _ in range(2000))

    # Driver profiles: the aggressive driver brakes harshly far more often than the calm one, per km.
    def harsh_per_100km(vehicle_id: int) -> float:
        d = Vehicle(vehicle_id, random.Random(4))
        km0 = d.odometer_km
        n = sum(d.step(30.0, 0.0)["accel_min"] <= -3 for _ in range(20000))
        return n / (d.odometer_km - km0) * 100
    assert harsh_per_100km(3) > 4 * harsh_per_100km(1)

    # History: parts fail and get replaced, wear resets, and rows stay in time order per vehicle.
    hrng = random.Random(5)
    fleet = [Vehicle(i, hrng) for i in (1, 3)]
    history, maintenance = generate_history(fleet, 60, 30.0, datetime(2026, 6, 1, tzinfo=timezone.utc), hrng)
    assert {m[1] for m in maintenance} == set(COMPONENTS), "every part should need work within 60 days"
    assert {m[3] for m in maintenance} <= {"FAILURE", "PREVENTIVE"} and any(m[3] == "FAILURE" for m in maintenance)
    for vehicle_id in (1, 3):
        ts = [r["ts"] for r in history if r["vehicle_id"] == vehicle_id]
        assert ts == sorted(ts) and len(set(ts)) == len(ts)
    assert any(r["injected_fault"] == "brakes_failure" for r in history)
    fleet[0].wear["brakes"] = 90.0
    fleet[0].service("brakes")
    assert fleet[0].wear["brakes"] == 0.0
    print(f"self-check ok: {len(rows)} live ticks ({len(rows) - len(healthy)} faulty), "
          f"{len(history)} history rows, {len(maintenance)} maintenance records")


def main() -> None:
    load_dotenv(ROOT / ".env")
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--vehicles", type=int, default=int(os.getenv("SIM_VEHICLES", "5")), help="number of vehicles (ids 1..N)")
    p.add_argument("--interval", type=float, default=float(os.getenv("SIM_INTERVAL", "2")), help="seconds between publishes")
    p.add_argument("--fault-rate", type=float, default=float(os.getenv("SIM_FAULT_RATE", "0.02")),
                   help="chance per vehicle per tick of starting a fault (0..1)")
    p.add_argument("--host", default=os.getenv("MQTT_HOST", "localhost"))
    p.add_argument("--port", type=int, default=int(os.getenv("MQTT_PORT", "1883")))
    p.add_argument("--seed", type=int, default=None, help="random seed for repeatable runs")
    p.add_argument("--self-check", action="store_true", help="run model assertions and exit (no broker needed)")
    p.add_argument("--fast-forward", type=int, metavar="DAYS", help="write DAYS of history to TimescaleDB and exit")
    p.add_argument("--step", type=float, default=30.0, help="seconds between readings in fast-forward history")
    p.add_argument("--replace", action="store_true", help="with --fast-forward: delete existing data in the period first")
    p.add_argument("--fresh", action="store_true", help="live mode: ignore saved wear state and start new vehicles")
    args = p.parse_args()

    if args.self_check:
        return self_check()
    if args.vehicles < 1 or args.interval <= 0 or not 0 <= args.fault_rate <= 1:
        p.error("need --vehicles >= 1, --interval > 0 and 0 <= --fault-rate <= 1")

    rng = random.Random(args.seed)
    if args.fast_forward:
        return fast_forward(args, rng)

    vehicles = {i: Vehicle(i, rng) for i in range(1, args.vehicles + 1)}
    if STATE_FILE.exists() and not args.fresh:
        for vehicle_id, state in json.loads(STATE_FILE.read_text()).items():
            if int(vehicle_id) in vehicles:
                vehicles[int(vehicle_id)].restore(state)
        print(f"Continuing from wear state in {STATE_FILE.name} (use --fresh to ignore it).")

    def on_message(_client, _userdata, message) -> None:
        # fleet/{id}/maintenance {"component": "brakes"}: the backend says the part was replaced.
        try:
            vehicle = vehicles[int(message.topic.split("/")[1])]
            component = json.loads(message.payload)["component"]
        except (KeyError, ValueError, IndexError):
            return
        vehicle.service(component)
        print(f"vehicle {vehicle.id}: {component} serviced, wear reset")

    # `kill` (SIGTERM) should stop it as cleanly as Ctrl+C, so the wear state still gets saved.
    signal.signal(signal.SIGTERM, signal.default_int_handler)

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="fleet-twin-simulator")
    client.username_pw_set(os.getenv("MQTT_USERNAME"), os.getenv("MQTT_PASSWORD"))
    client.on_message = on_message
    client.on_connect = lambda c, *_: c.subscribe("fleet/+/maintenance", qos=1)
    client.connect(args.host, args.port)
    client.loop_start()
    print(f"Publishing {args.vehicles} vehicles every {args.interval}s to {args.host}:{args.port} "
          f"(fault rate {args.fault_rate}). Ctrl+C to stop.")
    try:
        while True:
            for v in vehicles.values():
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
        STATE_FILE.write_text(json.dumps({str(v.id): v.state() for v in vehicles.values()}, indent=1))


if __name__ == "__main__":
    main()
