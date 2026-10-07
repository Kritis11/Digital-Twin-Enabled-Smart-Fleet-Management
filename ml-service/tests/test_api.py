import numpy as np
import pandas as pd
import pytest
from app import main
from app.features import SIGNALS, WINDOWS_S, build_features, feature_names
from app.model import AnomalyModel
from fastapi.testclient import TestClient
from xgboost import XGBClassifier

HEALTHY = {
    "engine_temp": 90.0,
    "vibration": 0.4,
    "rpm": 2000.0,
    "battery_voltage": 13.9,
    "tyre_pressure_fl": 32.0,
    "tyre_pressure_fr": 32.0,
    "tyre_pressure_rl": 32.0,
    "tyre_pressure_rr": 32.0,
}


def readings(n=30, **override):
    """n readings 2 s apart; `override` replaces signal values in the last 5."""
    t0 = pd.Timestamp("2026-10-06T10:00:00Z")
    return [
        {"ts": (t0 + pd.Timedelta(seconds=2 * i)).isoformat(), **HEALTHY, **(override if i >= n - 5 else {})}
        for i in range(n)
    ]


@pytest.fixture
def client():
    # Not used as a context manager, so the lifespan (MinIO load) never runs.
    main.MODEL = None
    yield TestClient(main.app)
    main.MODEL = None


@pytest.fixture
def model():
    """A small XGBoost model that learns one thing: a hot engine is an anomaly."""
    rng = np.random.default_rng(0)
    n = 400
    df = pd.DataFrame({s: HEALTHY[s] + rng.normal(0, 0.05, n) for s in SIGNALS})
    df["vehicle_id"] = 1
    df["ts"] = pd.Timestamp("2026-10-06T09:00:00Z") + pd.to_timedelta(np.arange(n) * 2, unit="s")
    hot = (np.arange(n) // 20) % 2 == 1
    df.loc[hot, "engine_temp"] += 30
    booster = XGBClassifier(n_estimators=20, max_depth=2).fit(build_features(df), hot).get_booster()
    return AnomalyModel(
        {
            "version": "test",
            "kind": "xgboost",
            "signals": SIGNALS,
            "windows_s": WINDOWS_S,
            "features": feature_names(),
            "threshold": 0.5,
        },
        booster,
    )


def test_anomaly_returns_503_without_a_model(client):
    r = client.post("/anomaly", json={"vehicle_id": 1, "readings": readings()})
    assert r.status_code == 503


def test_anomaly_rejects_empty_window(client, model):
    main.MODEL = model
    assert client.post("/anomaly", json={"vehicle_id": 1, "readings": []}).status_code == 422


def test_anomaly_scores_healthy_and_faulty_windows(client, model):
    main.MODEL = model

    ok = client.post("/anomaly", json={"vehicle_id": 7, "readings": readings()}).json()
    assert ok == {"vehicle_id": 7, "is_anomaly": False, "score": ok["score"], "reasons": [], "model_version": "test"}
    assert ok["score"] < 0.5

    bad = client.post("/anomaly", json={"vehicle_id": 7, "readings": readings(engine_temp=120.0)}).json()
    assert bad["is_anomaly"] is True and bad["score"] > 0.5
    assert bad["reasons"] and bad["reasons"][0].startswith("engine_temp_")


def test_anomaly_accepts_partial_readings(client, model):
    main.MODEL = model
    r = client.post("/anomaly", json={"vehicle_id": 1, "readings": [{"ts": "2026-10-06T10:00:00Z", "engine_temp": 91}]})
    assert r.status_code == 200 and r.json()["is_anomaly"] is False


def test_health_score_formula(client):
    perfect = client.post("/health-score", json={"vehicle_id": 1, "components": {"engine": "OK"}}).json()
    assert perfect == {"vehicle_id": 1, "health_score": 100.0, "deductions": {}}

    r = client.post(
        "/health-score",
        json={
            "vehicle_id": 1,
            "components": {"engine": "CRITICAL", "battery": "WARNING", "fuel": "OK"},
            "anomaly_score": 0.5,
        },
    ).json()
    assert r["deductions"] == {"engine": 25.0, "battery": 10.0, "anomaly": 15.0}
    assert r["health_score"] == 50.0


def test_health_score_never_goes_below_zero(client):
    components = {name: "CRITICAL" for name in ["engine", "battery", "brakes", "tyre_fl", "tyre_fr"]}
    r = client.post("/health-score", json={"vehicle_id": 1, "components": components, "anomaly_score": 1.0}).json()
    assert r["health_score"] == 0.0


def test_health_score_rejects_unknown_status(client):
    r = client.post("/health-score", json={"vehicle_id": 1, "components": {"engine": "BROKEN"}})
    assert r.status_code == 422
