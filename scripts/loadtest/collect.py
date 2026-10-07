#!/usr/bin/env python3
"""Samples a running production-compose stack during a load test and writes one CSV row per sample.

usage: collect.py <compose project> <admin password> <out.csv> <seconds> [--every 10]

Per sample: telemetry rows ingested (and so the rate), reading-to-database and reading-to-twin delay
(mean over the interval, and the p95/p99/max the backend reports), ML call times as seen by the
backend, dashboard API response times measured through nginx, WebSocket twin updates received per
second, and CPU and memory of every container (docker stats).
"""

import argparse
import contextlib
import csv
import json
import re
import ssl
import subprocess
import threading
import time
import urllib.request

p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
p.add_argument("project")
p.add_argument("password")
p.add_argument("out")
p.add_argument("seconds", type=int)
p.add_argument("--every", type=int, default=10)
p.add_argument("--base", default="https://localhost")
args = p.parse_args()

TLS = ssl._create_unverified_context()  # the local stack has a self-signed certificate
BACKEND = f"{args.project}-backend-1"
METRIC = re.compile(r"^(\w+)(?:\{([^}]*)\})? ([0-9.eE+-]+|NaN)$")


def http(method, path, body=None, token=None):
    req = urllib.request.Request(args.base + path, method=method, data=json.dumps(body).encode() if body else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, context=TLS, timeout=30) as r:
        data = r.read()
    return time.perf_counter() - t0, data


def login():
    return json.loads(http("POST", "/api/auth/login", {"username": "admin", "password": args.password})[1])[
        "accessToken"
    ]


def prometheus():
    """{(name, labels): value} from the backend's management port, which only the Docker network can reach."""
    text = subprocess.run(
        ["docker", "exec", BACKEND, "wget", "-qO-", "http://localhost:8081/actuator/prometheus"],
        capture_output=True,
        text=True,
        timeout=20,
    ).stdout
    out = {}
    for line in text.splitlines():
        m = METRIC.match(line)
        if m:
            out[(m.group(1), m.group(2) or "")] = float(m.group(3))
    return out


def pick(metrics, name, *label_parts):
    return sum(v for (n, labels), v in metrics.items() if n == name and all(part in labels for part in label_parts))


def docker_stats():
    out = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}} {{.MemUsage}}"],
        capture_output=True,
        text=True,
        timeout=30,
    ).stdout
    stats = {}
    for line in out.splitlines():
        name, cpu, mem = line.split()[:3]
        if name.startswith(args.project + "-"):
            value, unit = re.match(r"([0-9.]+)(\w+)", mem).groups()
            mb = float(value) * {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "B": 1 / 1048576}[unit]
            stats[name[len(args.project) + 1 :].rsplit("-", 1)[0]] = (float(cpu.rstrip("%")), round(mb))
    return stats


# -- WebSocket: count /topic/twins frames with a minimal STOMP client on the stdlib --------------------
twin_frames = 0


def websocket_listener(token):
    """Raw WebSocket over TLS (RFC 6455) + STOMP CONNECT/SUBSCRIBE; counts MESSAGE frames. Reconnects if dropped."""
    import base64
    import os
    import socket
    import struct

    global twin_frames

    def send(sock, text):
        payload = text.encode()
        header = bytes([0x81])
        n = len(payload)
        header += bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n)
        mask = os.urandom(4)
        sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    host = args.base.split("://")[1]
    while True:
        try:
            sock = TLS.wrap_socket(socket.create_connection((host, 443), timeout=30), server_hostname=host)
            key = base64.b64encode(os.urandom(16)).decode()
            sock.sendall(
                (
                    f"GET /ws HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {args.base}\r\n\r\n"
                ).encode()
            )
            buffer = b""
            while b"\r\n\r\n" not in buffer:
                buffer += sock.recv(4096)
            buffer = buffer.split(b"\r\n\r\n", 1)[1]
            send(sock, f"CONNECT\naccept-version:1.2\nhost:{host}\nAuthorization:Bearer {token}\n\n\0")
            send(sock, "SUBSCRIBE\nid:0\ndestination:/topic/twins\n\n\0")
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                twin_frames += chunk.count(b"MESSAGE\n")
        except Exception:
            pass
        time.sleep(2)
        with contextlib.suppress(Exception):
            token = login()


token = login()
threading.Thread(target=websocket_listener, args=(token,), daemon=True).start()

API = {
    "vehicles": "/api/vehicles",
    "alerts": "/api/alerts?status=all",
    "history": "/api/vehicles/1/telemetry",
    "fuel_summary": "/api/fleet/fuel-summary?period=24h",
}
fields = [
    "t",
    "rows_per_s",
    "stored_delay_ms",
    "stored_p95_ms",
    "stored_p99_ms",
    "stored_max_ms",
    "twin_delay_ms",
    "twin_p95_ms",
    "ml_anomaly_ms",
    "ml_health_ms",
    "ml_rul_ms",
    "anomaly_calls_per_s",
    "ws_twins_per_s",
    "errors",
] + [f"api_{k}_ms" for k in API]
previous, previous_frames, started, containers = None, 0, time.time(), []
previous_at = frames_at = started
rows = []
while time.time() - started < args.seconds:
    time.sleep(args.every)
    row = {"t": round(time.time() - started), "errors": ""}
    try:
        m = prometheus()
        scraped_at = time.time()
        if previous:

            def mean_ms(name, *labels, now=m, before=previous):
                count = pick(now, name + "_count", *labels) - pick(before, name + "_count", *labels)
                total = pick(now, name + "_sum", *labels) - pick(before, name + "_sum", *labels)
                return round(1000 * total / count, 1) if count > 0 else ""

            stored = pick(m, "fleet_telemetry_stored_delay_seconds_count") - pick(
                previous, "fleet_telemetry_stored_delay_seconds_count"
            )
            row["rows_per_s"] = round(
                stored / (scraped_at - previous_at), 1
            )  # a sample takes a while, so not args.every
            row["stored_delay_ms"] = mean_ms("fleet_telemetry_stored_delay_seconds")
            row["twin_delay_ms"] = mean_ms("fleet_telemetry_twin_delay_seconds")
            row["stored_p95_ms"] = round(1000 * pick(m, "fleet_telemetry_stored_delay_seconds", 'quantile="0.95"'), 1)
            row["stored_p99_ms"] = round(1000 * pick(m, "fleet_telemetry_stored_delay_seconds", 'quantile="0.99"'), 1)
            row["stored_max_ms"] = round(1000 * pick(m, "fleet_telemetry_stored_delay_seconds_max"), 1)
            row["twin_p95_ms"] = round(1000 * pick(m, "fleet_telemetry_twin_delay_seconds", 'quantile="0.95"'), 1)
            for key, uri in (
                ("ml_anomaly_ms", 'uri="/anomaly"'),
                ("ml_health_ms", 'uri="/health-score"'),
                ("ml_rul_ms", 'uri="/rul"'),
            ):
                row[key] = mean_ms("http_client_requests_seconds", uri)
            # every online vehicle should be scored once per fleet.ml.interval-ms (10 s): vehicles / 10 calls a second
            scored = pick(m, "http_client_requests_seconds_count", 'uri="/anomaly"') - pick(
                previous, "http_client_requests_seconds_count", 'uri="/anomaly"'
            )
            row["anomaly_calls_per_s"] = round(scored / (scraped_at - previous_at), 1)
        previous, previous_at = m, scraped_at
    except Exception as e:
        row["errors"] += f"metrics: {e}; "
    for key, path in API.items():
        try:
            row[f"api_{key}_ms"] = round(1000 * http("GET", path, token=token)[0], 1)
        except urllib.error.HTTPError as e:
            if e.code == 401:
                token = login()
            row["errors"] += f"{key}: HTTP {e.code}; "
        except Exception as e:
            row["errors"] += f"{key}: {type(e).__name__}; "
    now = time.time()
    row["ws_twins_per_s"] = round((twin_frames - previous_frames) / (now - frames_at), 1)
    previous_frames, frames_at = twin_frames, now
    try:
        for name, (cpu, mem) in docker_stats().items():
            row[f"cpu_{name}"], row[f"mem_{name}"] = cpu, mem
            if name not in containers:
                containers.append(name)
    except Exception as e:
        row["errors"] += f"stats: {e}; "
    rows.append(row)
    print(
        json.dumps({k: v for k, v in row.items() if not k.startswith(("cpu_", "mem_")) or k.endswith("backend")}),
        flush=True,
    )

columns = fields + [f"{kind}_{name}" for name in sorted(containers) for kind in ("cpu", "mem")]
with open(args.out, "w", newline="") as f:
    writer = csv.DictWriter(f, fieldnames=columns)
    writer.writeheader()
    writer.writerows(rows)
