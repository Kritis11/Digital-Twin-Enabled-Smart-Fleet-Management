#!/usr/bin/env python3
"""Builds the run-to-failure dataset from TimescaleDB, trains and compares a linear-extrapolation baseline
and XGBoost quantile regression per component, adds the results to docs/model_report.md and uploads the
better model for each component to MinIO (models/rul/<component>/<version>/).

    python -m training.train_rul                  # build dataset, train, report, upload
    python -m training.train_rul --no-upload

Needs history with part failures: run `simulator.py --fast-forward 90` first.
"""

import argparse
from datetime import UTC, datetime
from pathlib import Path

import numpy as np
import pandas as pd
import xgboost as xgb
from app import model as model_store
from app import rul
from app.rul import COMPONENTS, FEATURES, QUANTILES, RulModel
from dotenv import load_dotenv
from sklearn.model_selection import GroupKFold

from training.export_data import ROOT
from training.report import write_section

DATA_DIR = Path(__file__).resolve().parent / "data"
MIN_LIFECYCLES = 5
XGB_PARAMS = dict(
    objective="reg:quantileerror",
    quantile_alpha=QUANTILES,
    n_estimators=300,
    max_depth=3,
    learning_rate=0.05,
    min_child_weight=3,
    subsample=0.9,
    random_state=0,
)


def run_to_failure(daily: pd.DataFrame, maintenance: pd.DataFrame, component: str) -> pd.DataFrame:
    """Feature rows from lifecycles that ended in a failure, labelled with rul_days until that failure.

    Lifecycles cut short by a preventive service, and the one still running, have no known failure date
    and are left out.
    """
    features = rul.build_features(daily, maintenance, component)
    failed = features[features["end_cause"] == "FAILURE"].copy()
    failed["rul_days"] = (failed["end_ts"] - failed["last_ts"]).dt.total_seconds() / 86400
    return failed[failed["rul_days"] >= 0].reset_index(drop=True)


def default_rate(data: pd.DataFrame) -> float:
    """Typical life-fraction used per day, for readings too early in a lifecycle to have a rate of their own."""
    return float(data["rate_life"].median())


def fit_xgboost(data: pd.DataFrame) -> xgb.Booster:
    return xgb.XGBRegressor(**XGB_PARAMS).fit(data[FEATURES], data["rul_days"]).get_booster()


def metrics(actual: np.ndarray, predicted: np.ndarray) -> dict:
    error = predicted - actual
    return {
        "mae": float(np.abs(error).mean()),
        "rmse": float(np.sqrt((error**2).mean())),
        "within_7d": float((np.abs(error) <= 7).mean()),
    }


def evaluate(data: pd.DataFrame, component: str) -> tuple[dict, dict]:
    """Leave-one-vehicle-out: every prediction is for a vehicle the model never saw, so no lifecycle
    is split between train and test. Returns (metrics per model kind, baseline residual quantiles)."""
    folds = GroupKFold(n_splits=data["vehicle_id"].nunique())
    predicted = {"linear": np.zeros(len(data)), "xgboost": np.zeros((len(data), 3))}
    for train_index, test_index in folds.split(data, groups=data["vehicle_id"]):
        train, test = data.iloc[train_index], data.iloc[test_index]
        predicted["linear"][test_index] = rul.linear_rul(test, default_rate(train))
        model = RulModel({"kind": "xgboost", "features": FEATURES, "component": component}, fit_xgboost(train))
        predicted["xgboost"][test_index] = model.predict(test)[["lower_bound", "rul_days", "upper_bound"]].to_numpy()

    actual = data["rul_days"].to_numpy()
    results = {"linear": metrics(actual, predicted["linear"]), "xgboost": metrics(actual, predicted["xgboost"][:, 1])}
    residual = actual - predicted["linear"]
    quantiles = {"residual_q10": float(np.quantile(residual, 0.1)), "residual_q90": float(np.quantile(residual, 0.9))}
    # XGBoost's raw quantiles are too narrow on unseen vehicles. Conformalised quantile regression:
    # widen both bounds by the 80th percentile of how far outside the interval the held-out truths fell.
    miss = np.maximum(predicted["xgboost"][:, 0] - actual, actual - predicted["xgboost"][:, 2])
    quantiles["interval_pad"] = float(max(0.0, np.quantile(miss, 0.8)))
    results["xgboost"]["coverage_raw"] = float((miss <= 0).mean())
    return results, quantiles


def train_component(
    daily: pd.DataFrame, maintenance: pd.DataFrame, component: str, version: str
) -> tuple[RulModel, dict] | None:
    data = run_to_failure(daily, maintenance, component)
    lifecycles = data.groupby(["vehicle_id", "lifecycle"]).ngroups
    if lifecycles < MIN_LIFECYCLES or data["vehicle_id"].nunique() < 2:
        print(
            f"{component}: only {lifecycles} run-to-failure lifecycles; need {MIN_LIFECYCLES} across at least 2 vehicles. "
            "Fast-forward more days."
        )
        return None
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    data.to_csv(DATA_DIR / f"rul_{component}.csv", index=False)

    results, residuals = evaluate(data, component)
    best = min(results, key=lambda kind: results[kind]["mae"])
    config = {
        "version": version,
        "component": component,
        "kind": best,
        "features": FEATURES,
        "default_rate": default_rate(data),
        **residuals,
        "metrics": results[best],
    }
    # the chosen kind is refitted on every vehicle
    model = RulModel(config, fit_xgboost(data) if best == "xgboost" else None)
    info = {
        "rows": len(data),
        "lifecycles": lifecycles,
        "vehicles": int(data["vehicle_id"].nunique()),
        "mean_life_days": float(data.groupby(["vehicle_id", "lifecycle"])["rul_days"].max().mean()),
        "results": results,
        "best": best,
        "pad": residuals["interval_pad"],
    }
    return model, info


def report(infos: dict[str, dict], version: str, uploaded: bool) -> str:
    names = {"linear": "Linear extrapolation", "xgboost": "XGBoost quantile regression"}
    lines = [
        "# Remaining useful life models",
        "",
        f"Generated {datetime.now(UTC):%Y-%m-%d %H:%M} UTC by `ml-service/training/train_rul.py`. "
        f"Model version `{version}`.",
        "",
        "## Data",
        "",
        "Run-to-failure lifecycles from fast-forwarded simulator history: one row per vehicle per day, labelled "
        "with the days left until that part's failure. Lifecycles that ended in a preventive service, and the "
        "ones still running, have no known failure date and are left out.",
        "",
        "| Component | Lifecycles | Vehicles | Daily rows | Mean life (days) |",
        "|---|---|---|---|---|",
        *[
            f"| {c} | {i['lifecycles']} | {i['vehicles']} | {i['rows']} | {i['mean_life_days']:.0f} |"
            for c, i in infos.items()
        ],
        "",
        f"Features ({len(FEATURES)}): " + ", ".join(f"`{f}`" for f in FEATURES) + ".",
        "",
        "## Results",
        "",
        "Leave-one-vehicle-out cross-validation: each vehicle's lifecycles are predicted by a model trained only "
        "on the other vehicles, so no lifecycle (or vehicle) is on both sides of a split. Errors are in days. "
        "Raw coverage is the share of true values inside XGBoost's own 10%–90% quantiles (nominal: 80%).",
        "",
        "| Component | Model | MAE | RMSE | Within ±7 days | Raw interval coverage |",
        "|---|---|---|---|---|---|",
    ]
    for component, info in infos.items():
        for kind, m in info["results"].items():
            mark = " **(selected)**" if kind == info["best"] else ""
            lines.append(
                f"| {component} | {names[kind]}{mark} | {m['mae']:.2f} | {m['rmse']:.2f} | "
                f"{m['within_7d']:.0%} | " + (f"{m['coverage_raw']:.0%}" if kind == "xgboost" else "–") + " |"
            )
    lines += [
        "",
        "The model with the lower MAE is selected per component and refitted on all vehicles. "
        + (
            f"Uploaded to MinIO as `models/rul/<component>/{version}/`."
            if uploaded
            else "Not uploaded (`--no-upload`)."
        ),
        "",
        "## Prediction intervals and confidence",
        "",
        "- XGBoost: three quantile models (10%, 50%, 90%); the median is `rul_days`. The raw outer quantiles are too "
        "narrow on vehicles the model has not seen (see the table), so both bounds are widened by a fixed number of "
        "days chosen so that 80% of held-out true values fall inside (conformalised quantile regression): "
        + ", ".join(f"{c} ±{i['pad']:.1f} d" for c, i in infos.items() if i["best"] == "xgboost")
        + ".",
        "- Linear baseline: the bounds are the 10th and 90th percentile of its error on held-out vehicles.",
        "- `confidence` = 1 − (upper − lower) / (2 × max(rul_days, 7)), clamped to 0–1: narrow intervals relative "
        "to the prediction score high.",
        "",
        "## Caveats",
        "",
        "- Simulated wear is fast on purpose (parts last weeks) so that 90 days holds several lifecycles per "
        "vehicle. The models learn the simulator's wear curves, not real parts.",
        "- The wear indicator itself is a telemetry reading here. On real vehicles pad thickness, tread depth and "
        "battery health are measured rarely or estimated, which makes the problem much harder.",
        "- Five vehicles is a small fleet: each cross-validation fold tests on a single vehicle, so the numbers "
        "will move noticeably between runs of the simulator.",
        "- For a part's first lifecycle in the data the install date is unknown, so `days_in_service` and the "
        "cumulative usage features count from the first reading instead.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--no-upload", action="store_true", help="train and report, but do not store the models in MinIO")
    p.add_argument("--no-report", action="store_true", help="leave docs/model_report.md as it is")
    args = p.parse_args()
    load_dotenv(ROOT / ".env")

    with rul.connect() as conn:
        daily, maintenance = rul.load_daily(conn), rul.load_maintenance(conn)
    version = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    infos = {}
    for component in COMPONENTS:
        trained = train_component(daily, maintenance, component, version)
        if trained is None:
            continue
        model, infos[component] = trained
        for kind, m in infos[component]["results"].items():
            print(
                f"{component:8s} {kind:8s} MAE {m['mae']:5.2f}  RMSE {m['rmse']:5.2f}  within 7d {m['within_7d']:.0%}  "
                + (f"raw coverage {m['coverage_raw']:.0%}" if kind == "xgboost" else "")
                + ("  <- selected" if kind == model.config["kind"] else "")
            )
        if not args.no_upload:
            model_store.upload(f"rul/{component}", version, model.config, model.dumps())
    if not infos:
        raise SystemExit("Nothing trained.")
    if not args.no_report:
        write_section("rul", report(infos, version, uploaded=not args.no_upload))
    print(
        f"Datasets: {DATA_DIR}/rul_<component>.csv"
        + ("" if args.no_report else "   Report: docs/model_report.md")
        + ("" if args.no_upload else f"   Uploaded version {version}")
    )


if __name__ == "__main__":
    main()
