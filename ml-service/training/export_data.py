#!/usr/bin/env python3
"""Exports telemetry from TimescaleDB to CSV for training. `injected_fault` is the ground-truth label.

    python -m training.export_data                 # -> training/data/telemetry.csv
    python -m training.export_data --out other.csv

Reads the database settings from the repo-root .env. Warns if there is too little data to train on.
"""

import argparse
import os
from pathlib import Path

import pandas as pd
import psycopg
import psycopg.sql
from app.features import SIGNALS
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = Path(__file__).resolve().parent / "data" / "telemetry.csv"

# Below this the test split holds too few faults of each type for the metrics to mean much.
MIN_EPISODES = 80
MIN_MINUTES = 45

# The sensor faults the anomaly model should catch. Other labels (fuel_theft, <part>_failure) are not
# visible in its input signals and count as healthy here.
ANOMALY_FAULTS = ["overheating", "vibration_spike", "low_tyre_pressure", "low_battery"]


def load_csv(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    df["ts"] = pd.to_datetime(df["ts"], utc=True, format="ISO8601")
    return df


def summarise(df: pd.DataFrame) -> dict:
    """Row, fault-row and fault-episode counts. An episode is a run of consecutive rows with the same fault."""
    df = df.sort_values(["vehicle_id", "ts"])
    fault = df["injected_fault"].where(df["injected_fault"].isin(ANOMALY_FAULTS), "")
    starts = (fault != "") & (fault != fault.groupby(df["vehicle_id"]).shift())
    return {
        "rows": len(df),
        "fault_rows": int((fault != "").sum()),
        "episodes": int(starts.sum()),
        "episodes_by_fault": df.loc[starts, "injected_fault"].value_counts().to_dict(),
        "minutes": (df["ts"].max() - df["ts"].min()).total_seconds() / 60 if len(df) else 0.0,
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", type=Path, default=DEFAULT_OUT)
    p.add_argument(
        "--from",
        dest="since",
        default="-infinity",
        help="only export readings at or after this timestamp (use it to leave out fast-forwarded "
        "history, whose 30 s readings do not suit the 30 s / 2 min feature windows)",
    )
    args = p.parse_args()

    load_dotenv(ROOT / ".env")
    dsn = (
        f"host={os.getenv('POSTGRES_HOST', 'localhost')} port={os.getenv('POSTGRES_PORT', '5432')} "
        f"dbname={os.environ['POSTGRES_DB']} user={os.environ['POSTGRES_USER']} password={os.environ['POSTGRES_PASSWORD']}"
    )
    # Fixed-width ISO timestamps; Postgres' default text form drops trailing zeros, which pandas parses badly.
    ts = "to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') AS ts"
    columns = ", ".join(["vehicle_id", ts, *SIGNALS, "injected_fault"])
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with psycopg.connect(dsn) as conn, conn.cursor() as cur, open(args.out, "wb") as f:
        query = (
            f"COPY (SELECT {columns} FROM telemetry WHERE ts >= {{}} ORDER BY vehicle_id, ts) "
            "TO STDOUT WITH CSV HEADER"
        ).format(psycopg.sql.Literal(args.since).as_string(conn))
        with cur.copy(query) as copy:
            for chunk in copy:
                f.write(chunk)

    s = summarise(load_csv(args.out))
    print(f"Wrote {s['rows']} rows to {args.out}")
    print(
        f"  span {s['minutes']:.0f} min, {s['fault_rows']} fault rows, "
        f"{s['episodes']} fault episodes {s['episodes_by_fault']}"
    )
    if s["episodes"] < MIN_EPISODES or s["minutes"] < MIN_MINUTES:
        rate = s["episodes"] / s["minutes"] if s["minutes"] > 1 else 2.0  # ~2 episodes/min at default settings
        more = max(MIN_MINUTES - s["minutes"], (MIN_EPISODES - s["episodes"]) / max(rate, 0.1))
        print(
            f"WARNING: too little data for a trustworthy model (want >= {MIN_EPISODES} episodes over "
            f">= {MIN_MINUTES} min). Run the simulator for about {more:.0f} more minutes, then export again."
        )


if __name__ == "__main__":
    main()
