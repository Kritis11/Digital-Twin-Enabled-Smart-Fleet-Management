#!/usr/bin/env python3
"""Trains and compares Isolation Forest and XGBoost on exported telemetry, writes docs/model_report.md
and uploads the better model (by F1 on the held-out, later-in-time split) to MinIO.

    python -m training.export_data
    python -m training.train                       # train, report, upload
    python -m training.train --no-upload           # train and report only
"""

import argparse
from datetime import UTC, datetime
from pathlib import Path

import pandas as pd
from app import model as model_store
from app.features import SIGNALS, WINDOWS_S, build_features, feature_names
from app.model import AnomalyModel
from dotenv import load_dotenv
from sklearn.ensemble import IsolationForest
from sklearn.metrics import confusion_matrix, precision_recall_fscore_support
from xgboost import XGBClassifier

from training.export_data import ANOMALY_FAULTS, DEFAULT_OUT, ROOT, load_csv, summarise
from training.report import REPORT, write_section

TEST_FRACTION = 0.2
THRESHOLD = 0.5


def time_split(df: pd.DataFrame, test_fraction: float = TEST_FRACTION) -> tuple[pd.Series, pd.Timestamp]:
    """Boolean mask of training rows: everything before the cutoff time. Never random."""
    cutoff = df["ts"].quantile(1 - test_fraction)
    return df["ts"] < cutoff, cutoff


def evaluate(y_true: pd.Series, y_pred: pd.Series, faults: pd.Series) -> dict:
    precision, recall, f1, _ = precision_recall_fscore_support(y_true, y_pred, average="binary", zero_division=0)
    tn, fp, fn, tp = confusion_matrix(y_true, y_pred, labels=[False, True]).ravel()
    return {
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
        "tn": int(tn),
        "fp": int(fp),
        "fn": int(fn),
        "tp": int(tp),
        "recall_by_fault": {str(k): float(v) for k, v in y_pred[y_true].groupby(faults[y_true]).mean().items()},
    }


def train(df: pd.DataFrame) -> tuple[dict[str, AnomalyModel], dict]:
    """Returns both fitted models (with metrics in their config) and facts about the split."""
    df = df.sort_values(["vehicle_id", "ts"]).reset_index(drop=True)
    df["injected_fault"] = df["injected_fault"].where(df["injected_fault"].isin(ANOMALY_FAULTS))
    X = build_features(df)
    y = df["injected_fault"].notna()
    is_train, cutoff = time_split(df)
    X_train, y_train, X_test, y_test = X[is_train], y[is_train], X[~is_train], y[~is_train]
    if y_train.nunique() < 2 or y_test.nunique() < 2:
        raise SystemExit("Need both healthy and faulty rows on each side of the time split; collect more data.")

    version = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    base = {
        "version": version,
        "signals": SIGNALS,
        "windows_s": WINDOWS_S,
        "features": feature_names(),
        "threshold": THRESHOLD,
        "feature_means": X_train.mean().fillna(0).to_dict(),
        "feature_stds": X_train.std().fillna(0).to_dict(),
    }

    # Unsupervised baseline: never sees the labels.
    forest = IsolationForest(n_estimators=200, random_state=0).fit(X_train.fillna(X_train.mean()))
    # Supervised: weight the positive class by its rarity.
    booster = (
        XGBClassifier(
            n_estimators=200,
            max_depth=4,
            learning_rate=0.1,
            subsample=0.9,
            colsample_bytree=0.9,
            scale_pos_weight=float((~y_train).sum() / y_train.sum()),
            random_state=0,
        )
        .fit(X_train, y_train)
        .get_booster()
    )

    models = {
        "isolation_forest": AnomalyModel({**base, "kind": "isolation_forest"}, forest),
        "xgboost": AnomalyModel({**base, "kind": "xgboost"}, booster),
    }
    for model in models.values():
        predicted = pd.Series(model.score(X_test) >= THRESHOLD, index=X_test.index)
        model.config["metrics"] = evaluate(y_test, predicted, df["injected_fault"][~is_train])

    gain = pd.Series(booster.get_score(importance_type="gain")).sort_values(ascending=False)
    info = {
        "data": summarise(df),
        "cutoff": cutoff,
        "train_rows": int(is_train.sum()),
        "test_rows": int((~is_train).sum()),
        "train_fault_rate": float(y_train.mean()),
        "test_fault_rate": float(y_test.mean()),
        "top_features": (gain / gain.sum()).head(10).to_dict(),
    }
    return models, info


def write_report(models: dict[str, AnomalyModel], info: dict, best: str, uploaded: bool) -> None:
    d = info["data"]
    names = {"isolation_forest": "Isolation Forest (unsupervised)", "xgboost": "XGBoost (supervised)"}
    lines = [
        "# Anomaly detection model",
        "",
        f"Generated {datetime.now(UTC):%Y-%m-%d %H:%M} UTC by `ml-service/training/train.py`. "
        f"Model version `{models[best].config['version']}`.",
        "",
        "## Data",
        "",
        f"- {d['rows']} telemetry rows over {d['minutes']:.0f} minutes from the simulator; "
        f"{d['fault_rows']} rows ({d['fault_rows'] / d['rows']:.0%}) carry an `injected_fault` label.",
        f"- {d['episodes']} fault episodes: "
        + ", ".join(f"{k} {v}" for k, v in sorted(d["episodes_by_fault"].items()))
        + ".",
        f"- Time-based split at {info['cutoff']:%Y-%m-%d %H:%M:%S} UTC: the first {info['train_rows']} rows train "
        f"({info['train_fault_rate']:.0%} faulty), the last {info['test_rows']} rows test "
        f"({info['test_fault_rate']:.0%} faulty). No shuffling.",
        f"- {len(models[best].config['features'])} features: latest value plus mean, std, min, max and rate of "
        f"change over {' and '.join(f'{w} s' for w in WINDOWS_S)} windows, per vehicle, for "
        + ", ".join(f"`{s}`" for s in SIGNALS)
        + ".",
        "",
        "## Results on the test split",
        "",
        "| Model | Precision | Recall | F1 |",
        "|---|---|---|---|",
    ]
    for kind, model in models.items():
        m = model.config["metrics"]
        lines.append(f"| {names[kind]} | {m['precision']:.3f} | {m['recall']:.3f} | {m['f1']:.3f} |")
    for kind, model in models.items():
        m = model.config["metrics"]
        lines += [
            "",
            f"### {names[kind]}",
            "",
            "| | Predicted healthy | Predicted anomaly |",
            "|---|---|---|",
            f"| **Actually healthy** | {m['tn']} | {m['fp']} |",
            f"| **Actually faulty** | {m['fn']} | {m['tp']} |",
            "",
            "Recall by fault type: " + ", ".join(f"{k} {v:.2f}" for k, v in sorted(m["recall_by_fault"].items())) + ".",
        ]
    lines += [
        "",
        "## Top XGBoost features (share of total gain)",
        "",
        "| Feature | Share |",
        "|---|---|",
        *[f"| `{k}` | {v:.1%} |" for k, v in info["top_features"].items()],
        "",
        "## Selected model",
        "",
        f"**{names[best]}**, chosen by F1. "
        + (
            f"Uploaded to MinIO as `models/anomaly/{models[best].config['version']}/`."
            if uploaded
            else "Not uploaded (`--no-upload`)."
        ),
        "",
        "## Caveats",
        "",
        "- The labels and the faults both come from the simulator. Its faults are abrupt step changes far "
        "outside the healthy range, so they are easy to separate; these scores say the pipeline works, not "
        "how the model would do on real vehicles.",
        "- A row is labelled faulty only while the fault is active. Engine temperature stays high for a few "
        'readings after an overheating fault ends, so some "false positives" are the tail of a real fault.',
        "- Isolation Forest assumes anomalies are rare, but about a third of simulator rows are faulty, "
        "which is a poor fit for it.",
        "",
    ]
    write_section("anomaly", "\n".join(lines))


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--data", type=Path, default=DEFAULT_OUT)
    p.add_argument("--no-upload", action="store_true")
    args = p.parse_args()
    load_dotenv(ROOT / ".env")

    models, info = train(load_csv(args.data))
    best = max(models, key=lambda kind: models[kind].config["metrics"]["f1"])
    for kind, model in models.items():
        m = model.config["metrics"]
        print(f"{kind:17s} precision {m['precision']:.3f}  recall {m['recall']:.3f}  f1 {m['f1']:.3f}")
    if not args.no_upload:
        print(f"Uploaded {best} as version {model_store.save(models[best])}")
    write_report(models, info, best, uploaded=not args.no_upload)
    print(f"Report: {REPORT}")


if __name__ == "__main__":
    main()
