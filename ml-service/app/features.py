"""Rolling-window features. Training and inference both call build_features, so they cannot drift apart."""

import pandas as pd

SIGNALS = [
    "engine_temp",
    "vibration",
    "rpm",
    "battery_voltage",
    "tyre_pressure_fl",
    "tyre_pressure_fr",
    "tyre_pressure_rl",
    "tyre_pressure_rr",
]
WINDOWS_S = [30, 120]
STATS = ["mean", "std", "min", "max", "roc"]


def feature_names(signals: list[str] = SIGNALS, windows: list[int] = WINDOWS_S) -> list[str]:
    names = []
    for s in signals:
        names.append(f"{s}_last")
        names += [f"{s}_{stat}_{w}s" for w in windows for stat in STATS]
    return names


def build_features(df: pd.DataFrame, signals: list[str] = SIGNALS, windows: list[int] = WINDOWS_S) -> pd.DataFrame:
    """One feature row per input row, indexed like `df`.

    `df` needs `vehicle_id`, `ts` (datetime) and the signal columns. Windows are time-based and
    per vehicle, looking back from each row: (ts - window, ts]. `roc` is the change across the
    window per second. A missing signal column is treated as all-NaN.
    """
    df = df.reindex(columns=["vehicle_id", "ts", *signals]).sort_values(["vehicle_id", "ts"], kind="stable")
    grouped = df.set_index("ts").groupby("vehicle_id", sort=False)
    out = {}
    for s in signals:
        out[f"{s}_last"] = df[s].to_numpy(dtype=float)
        for w in windows:
            rolling = grouped[s].rolling(f"{w}s")
            out[f"{s}_mean_{w}s"] = rolling.mean().to_numpy()
            # std of a single reading is undefined; 0 means "no variation seen yet"
            out[f"{s}_std_{w}s"] = rolling.std().fillna(0.0).to_numpy()
            out[f"{s}_min_{w}s"] = rolling.min().to_numpy()
            out[f"{s}_max_{w}s"] = rolling.max().to_numpy()
            out[f"{s}_roc_{w}s"] = rolling.apply(lambda x: x[-1] - x[0], raw=True).to_numpy() / w
    return pd.DataFrame(out, index=df.index)[feature_names(signals, windows)]
