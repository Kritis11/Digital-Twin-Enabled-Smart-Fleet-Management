#!/usr/bin/env python3
"""Turns load-test CSVs (from collect.py) into the Markdown tables and Mermaid charts used in docs/performance.md.

usage: summarise.py label=run.csv [label=run.csv ...]
"""

import csv
import json
import pathlib
import statistics
import sys

runs = {}
for arg in sys.argv[1:]:
    label, path = arg.split("=", 1)
    with open(path) as f:
        rows = list(csv.DictReader(f))[1:]  # the first sample has no previous one to difference against
    dashboard = pathlib.Path(path.replace(".csv", ".dashboard.json"))
    runs[label] = (
        rows,
        json.loads(dashboard.read_text()) if dashboard.exists() and dashboard.read_text().strip() else {},
    )


def values(rows, column):
    return [float(r[column]) for r in rows if r.get(column) not in (None, "")]


def stat(rows, column, how=statistics.mean, digits=0):
    v = values(rows, column)
    return "–" if not v else f"{how(v):.{digits}f}"


def table(title, lines):
    print(f"\n**{title}**\n")
    print("| | " + " | ".join(runs) + " |")
    print("|---|" + "---|" * len(runs))
    for name, cell in lines:
        print(f"| {name} | " + " | ".join(cell(rows, dash) for rows, dash in runs.values()) + " |")


table(
    "Ingestion and delay",
    [
        ("Readings stored per second", lambda r, d: stat(r, "rows_per_s", digits=1)),
        ("Reading to database, mean (ms)", lambda r, d: stat(r, "stored_delay_ms")),
        ("Reading to database, p95 (ms)", lambda r, d: stat(r, "stored_p95_ms", statistics.median)),
        ("Reading to database, p99 (ms)", lambda r, d: stat(r, "stored_p99_ms", statistics.median)),
        ("Reading to database, worst (ms)", lambda r, d: stat(r, "stored_max_ms", max)),
        ("Reading to twin, mean (ms)", lambda r, d: stat(r, "twin_delay_ms")),
        ("Reading to twin, p95 (ms)", lambda r, d: stat(r, "twin_p95_ms", statistics.median)),
        ("Twin updates pushed to a browser per second", lambda r, d: stat(r, "ws_twins_per_s", digits=1)),
        ("Samples with errors", lambda r, d: str(sum(1 for x in r if x["errors"]))),
    ],
)
table(
    "ML service, as timed by the backend (mean ms per call)",
    [
        ("/anomaly", lambda r, d: stat(r, "ml_anomaly_ms", digits=1)),
        ("/health-score", lambda r, d: stat(r, "ml_health_ms", digits=1)),
        ("/rul", lambda r, d: stat(r, "ml_rul_ms", digits=1)),
        ("Vehicles scored for anomalies per second", lambda r, d: stat(r, "anomaly_calls_per_s", digits=1)),
    ],
)
table(
    "Dashboard",
    [
        (
            "GET /api/vehicles, median / worst (ms)",
            lambda r, d: f"{stat(r, 'api_vehicles_ms', statistics.median)} / {stat(r, 'api_vehicles_ms', max)}",
        ),
        (
            "GET /api/alerts, median / worst (ms)",
            lambda r, d: f"{stat(r, 'api_alerts_ms', statistics.median)} / {stat(r, 'api_alerts_ms', max)}",
        ),
        (
            "Telemetry history of a vehicle, median / worst (ms)",
            lambda r, d: f"{stat(r, 'api_history_ms', statistics.median)} / {stat(r, 'api_history_ms', max)}",
        ),
        (
            "Fleet fuel summary, median / worst (ms)",
            lambda r, d: f"{stat(r, 'api_fuel_summary_ms', statistics.median)} / {stat(r, 'api_fuel_summary_ms', max)}",
        ),
        ("Browser: sign-in to map drawn (ms)", lambda r, d: str(d.get("overviewMs", "–"))),
        ("Browser: frames per second on Fleet Overview", lambda r, d: str(d.get("fps", "–"))),
        ("Browser: main thread blocked (% of time)", lambda r, d: str(d.get("blockedPercent", "–"))),
        ("Browser: long tasks in 20 s", lambda r, d: str(d.get("longTasks", "–"))),
        (
            "Browser: open Alerts / open a vehicle (ms)",
            lambda r, d: f"{d.get('alertsMs', '–')} / {d.get('vehicleMs', '–')}",
        ),
    ],
)
containers = sorted({c[4:] for rows, _ in runs.values() for c in rows[0] if c.startswith("cpu_")})
table(
    "CPU per container, mean / peak (% of one core)",
    [(name, lambda r, d, n=name: f"{stat(r, 'cpu_' + n)} / {stat(r, 'cpu_' + n, max)}") for name in containers],
)
table("Memory per container, peak (MB)", [(name, lambda r, d, n=name: stat(r, "mem_" + n, max)) for name in containers])

for title, column in (
    ("Reading-to-database delay, mean per sample (ms)", "stored_delay_ms"),
    ("Backend CPU (% of one core)", "cpu_backend"),
):
    print(
        f'\n```mermaid\nxychart-beta\n    title "{title}"\n    x-axis "minutes into the run" 0 --> 10\n    y-axis "{column}"'
    )
    for rows, _ in runs.values():
        series = values(rows, column)
        step = max(1, len(series) // 20)
        print(
            f"    line [{', '.join(f'{statistics.mean(series[i:i + step]):.1f}' for i in range(0, len(series), step))}]"
        )
    print("```\nLines, in order: " + ", ".join(runs) + ".")
