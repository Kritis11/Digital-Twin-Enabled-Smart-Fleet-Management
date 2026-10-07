"""Remaining useful life: daily features from TimescaleDB, the two model kinds, and prediction.

Training (training/train_rul.py) and the /rul endpoint both build features with build_features,
from the same SQL, so they cannot drift apart.
"""

import os
from dataclasses import dataclass

import numpy as np
import pandas as pd
import psycopg
import xgboost as xgb

# Wearing parts: telemetry column, value when new, value at which the part counts as failed.
# These must match how failures are defined where the data comes from (simulator/simulator.py).
COMPONENTS = {
    "brakes": {"column": "brake_pad_wear", "new": 0.0, "fail": 95.0},
    "battery": {"column": "battery_health", "new": 100.0, "fail": 45.0},
    "tyres": {"column": "tyre_tread", "new": 8.0, "fail": 1.6},
    "engine": {"column": "engine_health", "new": 100.0, "fail": 40.0},
}

FEATURES = [
    "used",  # fraction of life used: 0 new, 1 at the failure threshold
    "rate_3d",
    "rate_7d",  # rolling degradation: change in `used` per day over the last 3 / 7 readings-days
    "rate_life",  # ... and since this part went into service
    "days_in_service",  # time since last maintenance of this part
    "km_in_service",
    "hours_in_service",
    "harsh_brakes_in_service",  # cumulative usage since then
    "km_per_day_7d",
    "hours_per_day_7d",
    "aggressiveness_7d",  # harsh braking, rapid acceleration and speeding per 100 km
]

QUANTILES = [0.1, 0.5, 0.9]
MAX_RUL_DAYS = 365.0
# What counts as a harsh event when summarising a day; same defaults as the backend's fleet.driving thresholds.
HARSH_BRAKE_MS2 = float(os.getenv("RUL_HARSH_BRAKE_MS2", "3.0"))
RAPID_ACCEL_MS2 = float(os.getenv("RUL_RAPID_ACCEL_MS2", "2.5"))
SPEED_LIMIT_KMH = float(os.getenv("RUL_SPEED_LIMIT_KMH", "80"))

DAILY_SQL = """
    SELECT vehicle_id, time_bucket('1 day', ts) AS day, max(ts) AS last_ts,
           last(brake_pad_wear, ts) AS brake_pad_wear, last(battery_health, ts) AS battery_health,
           last(tyre_tread, ts) AS tyre_tread, last(engine_health, ts) AS engine_health,
           max(odometer_km) AS odometer_km, max(engine_hours) AS engine_hours,
           count(*) FILTER (WHERE accel_min <= %(brake)s) AS harsh_brakes,
           count(*) FILTER (WHERE accel_max >= %(accel)s) AS rapid_accels,
           count(*) FILTER (WHERE speed > %(limit)s) AS speeding_readings
    FROM telemetry
    WHERE odometer_km IS NOT NULL AND ts >= %(since)s AND (%(vehicle)s::bigint IS NULL OR vehicle_id = %(vehicle)s)
    GROUP BY 1, 2 ORDER BY 1, 2
"""
MAINTENANCE_SQL = """
    SELECT vehicle_id, component, cause, performed_at FROM maintenance_records
    WHERE component IS NOT NULL AND (%(vehicle)s::bigint IS NULL OR vehicle_id = %(vehicle)s)
    ORDER BY vehicle_id, performed_at
"""


def connect() -> psycopg.Connection:
    return psycopg.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        port=os.getenv("POSTGRES_PORT", "5432"),
        dbname=os.environ["POSTGRES_DB"],
        user=os.environ["POSTGRES_USER"],
        password=os.environ["POSTGRES_PASSWORD"],
        connect_timeout=3,
    )


def _query(conn, sql: str, params: dict, time_column: str) -> pd.DataFrame:
    with conn.cursor() as cur:
        cur.execute(sql, params)
        frame = pd.DataFrame(cur.fetchall(), columns=[c.name for c in cur.description])
    frame[time_column] = pd.to_datetime(frame[time_column], utc=True)
    return frame


def load_daily(conn, vehicle_id: int | None = None, days: int | None = None) -> pd.DataFrame:
    """One row per vehicle per day. `days` limits how far back to look."""
    since = pd.Timestamp.now(tz="UTC") - pd.Timedelta(days=days) if days else pd.Timestamp("1970-01-01", tz="UTC")
    params = {
        "brake": -HARSH_BRAKE_MS2,
        "accel": RAPID_ACCEL_MS2,
        "limit": SPEED_LIMIT_KMH,
        "vehicle": vehicle_id,
        "since": since,
    }
    return _query(conn, DAILY_SQL, params, "last_ts")


def load_maintenance(conn, vehicle_id: int | None = None) -> pd.DataFrame:
    return _query(conn, MAINTENANCE_SQL, {"vehicle": vehicle_id}, "performed_at")


def build_features(daily: pd.DataFrame, maintenance: pd.DataFrame, component: str) -> pd.DataFrame:
    """One row per vehicle per day with FEATURES, plus vehicle_id, last_ts, lifecycle, end_ts and end_cause.

    A lifecycle runs from one maintenance of this part (or the first reading, when the install date is
    unknown) to the next. end_ts / end_cause describe the maintenance that ended it; they are NaT / None
    for the lifecycle still in progress and are only used for training labels.
    """
    spec = COMPONENTS[component]
    services = maintenance[maintenance["component"] == component]
    frames = []
    for vehicle_id, d in daily.groupby("vehicle_id"):
        d = d.sort_values("last_ts").reset_index(drop=True)
        events = services[services["vehicle_id"] == vehicle_id].sort_values("performed_at").reset_index(drop=True)
        d["used"] = (d[spec["column"]].astype(float) - spec["new"]) / (spec["fail"] - spec["new"])
        d["km"] = d["odometer_km"].astype(float).diff().fillna(0.0)
        d["hours"] = d["engine_hours"].astype(float).diff().fillna(0.0)
        d["harsh"] = d["harsh_brakes"] + d["rapid_accels"] + 0.1 * d["speeding_readings"]
        # number of services at or before each reading = which lifecycle it belongs to
        d["lifecycle"] = np.searchsorted(events["performed_at"].to_numpy(), d["last_ts"].to_numpy(), side="right")

        for lifecycle, g in d.groupby("lifecycle"):
            start = events["performed_at"].iloc[lifecycle - 1] if lifecycle > 0 else g["last_ts"].iloc[0]
            f = g[["vehicle_id", "last_ts", "lifecycle", "used"]].copy()
            f["days_in_service"] = (g["last_ts"] - start).dt.total_seconds() / 86400

            # the first row's own usage happened (mostly) before this lifecycle began
            def in_service(column: str, g: pd.DataFrame = g) -> pd.Series:
                return g[column].cumsum() - g[column].iloc[0]

            f["km_in_service"] = in_service("km")
            f["hours_in_service"] = in_service("hours")
            f["harsh_brakes_in_service"] = in_service("harsh_brakes")

            def per_day(series: pd.Series, lag: int, g: pd.DataFrame = g) -> pd.Series:
                elapsed = (g["last_ts"] - g["last_ts"].shift(lag)).dt.total_seconds() / 86400
                return (series - series.shift(lag)) / elapsed

            life_days = (g["last_ts"] - g["last_ts"].iloc[0]).dt.total_seconds() / 86400
            f["rate_life"] = ((g["used"] - g["used"].iloc[0]) / life_days).where(life_days > 0)
            # early in a lifecycle there are not yet 3 / 7 earlier readings: fall back to the longer-run rate
            f["rate_7d"] = per_day(g["used"], 7).fillna(f["rate_life"])
            f["rate_3d"] = per_day(g["used"], 3).fillna(f["rate_7d"])
            window = g[["km", "hours", "harsh"]].rolling(7, min_periods=1).sum()
            days = g["km"].rolling(7, min_periods=1).count()
            f["km_per_day_7d"] = window["km"] / days
            f["hours_per_day_7d"] = window["hours"] / days
            f["aggressiveness_7d"] = (window["harsh"] / window["km"] * 100).where(window["km"] > 1)

            ended = lifecycle < len(events)
            f["end_ts"] = events["performed_at"].iloc[lifecycle] if ended else pd.NaT
            f["end_cause"] = events["cause"].iloc[lifecycle] if ended else None
            frames.append(f)
    columns = ["vehicle_id", "last_ts", "lifecycle", *FEATURES, "end_ts", "end_cause"]
    out = pd.concat(frames, ignore_index=True)[columns] if frames else pd.DataFrame(columns=columns)
    out["end_ts"] = pd.to_datetime(out["end_ts"], utc=True)  # mixed NaT / timestamps would otherwise be object
    return out


def linear_rul(features: pd.DataFrame, default_rate: float) -> np.ndarray:
    """Baseline: extrapolate the recent degradation rate in a straight line to the failure threshold."""
    rate = features["rate_7d"].fillna(features["rate_life"]).fillna(default_rate)
    rate = rate.where(rate > 1e-4, default_rate)
    return ((1.0 - features["used"]) / rate).clip(0.0, MAX_RUL_DAYS).to_numpy()


@dataclass
class RulModel:
    """config keys: version, component, kind (xgboost | linear), features, default_rate,
    residual_q10, residual_q90 (linear), interval_pad (xgboost), metrics."""

    config: dict
    estimator: xgb.Booster | None = None

    def predict(self, features: pd.DataFrame) -> pd.DataFrame:
        """rul_days, lower_bound, upper_bound per feature row (an 80% interval), all >= 0."""
        if self.config["kind"] == "xgboost":
            # three quantile models in one booster; sort so the bounds can never cross
            q = np.sort(np.atleast_2d(self.estimator.predict(xgb.DMatrix(features[self.config["features"]]))), axis=1)
            # interval_pad widens the raw quantiles to the coverage measured on held-out vehicles
            pad = self.config.get("interval_pad", 0.0)
            lower, rul, upper = q[:, 0] - pad, q[:, 1], q[:, 2] + pad
        else:
            rul = linear_rul(features, self.config["default_rate"])
            # the baseline has no notion of uncertainty: use how far off it was on held-out vehicles
            lower, upper = rul + self.config["residual_q10"], rul + self.config["residual_q90"]
        out = pd.DataFrame(
            {"rul_days": rul, "lower_bound": np.minimum(lower, rul), "upper_bound": np.maximum(upper, rul)},
            index=features.index,
        )
        return out.clip(0.0, MAX_RUL_DAYS)

    def predict_latest(self, daily: pd.DataFrame, maintenance: pd.DataFrame) -> dict:
        """Prediction for a single vehicle's most recent reading, with a confidence in [0, 1]."""
        features = build_features(daily, maintenance, self.config["component"]).iloc[[-1]]
        p = self.predict(features).iloc[0]
        return {
            **{k: round(float(v), 1) for k, v in p.items()},
            "confidence": confidence(p["rul_days"], p["lower_bound"], p["upper_bound"]),
        }

    def dumps(self) -> bytes:
        return bytes(self.estimator.save_raw("json")) if self.estimator is not None else b"{}"

    @staticmethod
    def loads(config: dict, data: bytes) -> "RulModel":
        if config["kind"] != "xgboost":
            return RulModel(config)
        booster = xgb.Booster()
        booster.load_model(bytearray(data))
        return RulModel(config, booster)


def confidence(rul: float, lower: float, upper: float) -> float:
    """1 minus the interval's half-width relative to the prediction (floored at a week), clamped to [0, 1].

    A prediction of 30 days with bounds 27-33 scores 0.9; 30 days with bounds 15-45 scores 0.5.
    """
    return round(float(np.clip(1.0 - (upper - lower) / (2.0 * max(rul, 7.0)), 0.0, 1.0)), 2)
