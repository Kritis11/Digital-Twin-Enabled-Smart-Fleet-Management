import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app import main, rul
from app.rul import FEATURES, RulModel

T0 = pd.Timestamp("2026-07-01T18:00:00Z")


def history(days=30, wear_per_day=3.0, vehicle_id=1, service_on_day=None):
    """Brake pads wearing `wear_per_day` % a day from new; optionally replaced on the morning of a given day."""
    rows, wear = [], 0.0
    for day in range(days):
        if day == service_on_day:
            wear = 0.0
        wear += wear_per_day
        rows.append({"vehicle_id": vehicle_id, "last_ts": T0 + pd.Timedelta(days=day), "brake_pad_wear": wear,
                     "battery_health": 100.0, "tyre_tread": 8.0, "engine_health": 100.0,
                     "odometer_km": 1000.0 + 200.0 * (day + 1), "engine_hours": 50.0 + 6.0 * (day + 1),
                     "harsh_brakes": 2, "rapid_accels": 1, "speeding_readings": 10})
    maintenance = pd.DataFrame(columns=["vehicle_id", "component", "cause", "performed_at"])
    if service_on_day is not None:
        maintenance.loc[0] = [vehicle_id, "brakes", "FAILURE", T0 + pd.Timedelta(days=service_on_day) - pd.Timedelta(hours=13)]
    maintenance["performed_at"] = pd.to_datetime(maintenance["performed_at"], utc=True)
    return pd.DataFrame(rows), maintenance


def test_features_for_steady_wear():
    daily, maintenance = history(days=10)
    f = rul.build_features(daily, maintenance, "brakes")
    assert list(f.columns) == ["vehicle_id", "last_ts", "lifecycle", *FEATURES, "end_ts", "end_cause"]
    last = f.iloc[-1]
    assert last["used"] == pytest.approx(30.0 / 95.0)
    for rate in ("rate_3d", "rate_7d", "rate_life"):
        assert last[rate] == pytest.approx(3.0 / 95.0)
    assert last["days_in_service"] == 9          # install date unknown: counted from the first reading
    assert last["km_in_service"] == pytest.approx(9 * 200.0)
    assert last["hours_in_service"] == pytest.approx(9 * 6.0)
    assert last["km_per_day_7d"] == pytest.approx(200.0)
    assert last["aggressiveness_7d"] == pytest.approx((2 + 1 + 0.1 * 10) / 200.0 * 100)
    assert pd.isna(last["end_ts"]) and last["end_cause"] is None
    # the very first reading has no rate yet
    assert pd.isna(f.iloc[0]["rate_life"]) and pd.isna(f.iloc[0]["rate_7d"])


def test_maintenance_starts_a_new_lifecycle():
    daily, maintenance = history(days=20, service_on_day=12)
    f = rul.build_features(daily, maintenance, "brakes")
    assert list(f["lifecycle"]) == [0] * 12 + [1] * 8
    before, after = f.iloc[11], f.iloc[12]
    assert before["end_cause"] == "FAILURE" and before["end_ts"] == maintenance["performed_at"].iloc[0]
    assert after["used"] == pytest.approx(3.0 / 95.0)
    assert after["days_in_service"] == pytest.approx(13 / 24)   # serviced 13 hours before that day's last reading
    assert after["km_in_service"] == 0
    # rates never reach back across the replacement
    assert pd.isna(after["rate_life"])
    assert f.iloc[-1]["rate_7d"] == pytest.approx(3.0 / 95.0)
    # another part is unaffected by the brake job
    assert set(rul.build_features(daily, maintenance, "engine")["lifecycle"]) == {0}


def test_linear_baseline_extrapolates_to_the_failure_threshold():
    daily, maintenance = history(days=10)
    f = rul.build_features(daily, maintenance, "brakes")
    # 30% worn at 3%/day with failure at 95%: (95 - 30) / 3 days to go
    assert rul.linear_rul(f.iloc[[-1]], default_rate=0.5)[0] == pytest.approx(65.0 / 3.0)
    # no rate yet -> the fleet's typical rate
    assert rul.linear_rul(f.iloc[[0]], default_rate=0.05)[0] == pytest.approx((1 - 3 / 95) / 0.05)
    # a part that is not wearing is capped, not infinite
    flat = f.iloc[[-1]].assign(rate_7d=0.0, rate_life=0.0)
    assert rul.linear_rul(flat, default_rate=1e-9)[0] == rul.MAX_RUL_DAYS


def test_bounds_are_ordered_and_confidence_tracks_interval_width():
    daily, maintenance = history(days=10)
    model = RulModel({"component": "brakes", "kind": "linear", "features": FEATURES, "default_rate": 0.03,
                      "residual_q10": -4.0, "residual_q90": 2.0})
    p = model.predict_latest(daily, maintenance)
    assert p["rul_days"] == pytest.approx(21.7, abs=0.05)
    assert p["lower_bound"] == pytest.approx(17.7, abs=0.05) and p["upper_bound"] == pytest.approx(23.7, abs=0.05)
    assert p["confidence"] == pytest.approx(1 - 6 / (2 * 21.67), abs=0.01)
    assert rul.confidence(30, 27, 33) == 0.9 and rul.confidence(30, 15, 45) == 0.5 and rul.confidence(2, 0, 40) == 0.0
    # a worn-out part: never negative
    worn = RulModel({**model.config, "residual_q10": -50.0}).predict_latest(daily, maintenance)
    assert worn["lower_bound"] == 0.0


def test_xgboost_quantile_model_round_trips_and_keeps_bounds_ordered():
    from training.train_rul import fit_xgboost
    rng = np.random.default_rng(0)
    frames = []
    for vehicle_id in range(1, 5):
        daily, maintenance = history(days=31, wear_per_day=float(rng.uniform(2.6, 3.4)), vehicle_id=vehicle_id, service_on_day=None)
        f = rul.build_features(daily, maintenance, "brakes")
        f["rul_days"] = (1 - f["used"]) / f["rate_life"].bfill()
        frames.append(f)
    data = pd.concat(frames, ignore_index=True)
    config = {"component": "brakes", "kind": "xgboost", "features": FEATURES, "default_rate": 0.03}
    model = RulModel.loads(config, RulModel(config, fit_xgboost(data)).dumps())
    p = model.predict(data)
    assert (p["lower_bound"] <= p["rul_days"]).all() and (p["rul_days"] <= p["upper_bound"]).all()
    assert (p >= 0).all().all()
    assert np.abs(p["rul_days"] - data["rul_days"]).mean() < 3


@pytest.fixture
def client(monkeypatch):
    main.RUL_MODELS.clear()
    monkeypatch.setattr(main, "load_vehicle_history", lambda vehicle_id: history(days=10))
    yield TestClient(main.app)
    main.RUL_MODELS.clear()


def test_rul_endpoint(client):
    assert client.post("/rul", json={"vehicle_id": 1, "component": "wipers"}).status_code == 404
    assert client.post("/rul", json={"vehicle_id": 1, "component": "brakes"}).status_code == 503

    main.RUL_MODELS["brakes"] = RulModel({"version": "test", "component": "brakes", "kind": "linear", "features": FEATURES,
                                          "default_rate": 0.03, "residual_q10": -4.0, "residual_q90": 2.0})
    body = client.post("/rul", json={"vehicle_id": 1, "component": "brakes"}).json()
    assert set(body) == {"vehicle_id", "component", "rul_days", "lower_bound", "upper_bound", "confidence", "model_version"}
    assert body["lower_bound"] <= body["rul_days"] <= body["upper_bound"]
    assert body["rul_days"] == pytest.approx(21.7, abs=0.05) and 0 <= body["confidence"] <= 1


def test_rul_endpoint_without_history(client, monkeypatch):
    main.RUL_MODELS["brakes"] = RulModel({"version": "test", "component": "brakes", "kind": "linear", "features": FEATURES,
                                          "default_rate": 0.03, "residual_q10": -4.0, "residual_q90": 2.0})
    monkeypatch.setattr(main, "load_vehicle_history", lambda vehicle_id: (history(days=0)[0], history(days=0)[1]))
    assert client.post("/rul", json={"vehicle_id": 9, "component": "brakes"}).status_code == 404

    def database_down(vehicle_id):
        raise OSError("connection refused")
    monkeypatch.setattr(main, "load_vehicle_history", database_down)
    assert client.post("/rul", json={"vehicle_id": 1, "component": "brakes"}).status_code == 503
