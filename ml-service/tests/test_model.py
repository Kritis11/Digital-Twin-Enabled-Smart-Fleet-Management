"""The anomaly model wrapper (both kinds), its storage in MinIO, and how the service loads models at start."""

import io
from types import SimpleNamespace

import numpy as np
import pandas as pd
import pytest
from app import main, routing
from app import model as store
from app.features import SIGNALS, WINDOWS_S, build_features, feature_names
from app.model import AnomalyModel
from sklearn.ensemble import IsolationForest
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


def window(n=60, **last):
    """n healthy readings 2 s apart with a little noise; `last` overrides signals in the final 5."""
    rng = np.random.default_rng(1)
    df = pd.DataFrame({s: HEALTHY[s] + rng.normal(0, 0.05, n) for s in SIGNALS})
    for signal, value in last.items():
        df.loc[n - 5 :, signal] = value
    df["vehicle_id"] = 1
    df["ts"] = pd.Timestamp("2026-10-06T09:00:00Z") + pd.to_timedelta(np.arange(n) * 2, unit="s")
    return df


def config(kind, **extra):
    return {
        "version": "v1",
        "kind": kind,
        "signals": SIGNALS,
        "windows_s": WINDOWS_S,
        "features": feature_names(),
        "threshold": 0.5,
        **extra,
    }


@pytest.fixture
def forest():
    features = build_features(window(400)).fillna(0)
    estimator = IsolationForest(n_estimators=50, random_state=0).fit(features)
    return AnomalyModel(
        config(
            "isolation_forest",
            threshold=0.48,
            feature_means=features.mean().to_dict(),
            feature_stds=features.std().to_dict(),
        ),
        estimator,
    )


@pytest.fixture
def booster():
    df = window(400)
    hot = (np.arange(400) // 20) % 2 == 1
    df.loc[hot, "engine_temp"] += 30
    return AnomalyModel(
        config("xgboost"), XGBClassifier(n_estimators=20, max_depth=2).fit(build_features(df), hot).get_booster()
    )


def test_isolation_forest_flags_an_outlier_and_names_what_moved_most(forest):
    score, is_anomaly, reasons = forest.predict(window())
    assert not is_anomaly and reasons == [] and 0 <= score < 0.48

    score, is_anomaly, reasons = forest.predict(window(engine_temp=125.0))
    assert is_anomaly and 0.48 <= score <= 1
    assert len(reasons) == 3 and all(r.startswith("engine_temp_") for r in reasons)


@pytest.mark.parametrize("fixture", ["forest", "booster"])
def test_a_model_scores_the_same_after_a_round_trip_through_bytes(fixture, request):
    model = request.getfixturevalue(fixture)
    restored = AnomalyModel.loads(model.config, model.dumps())
    for readings in (window(), window(engine_temp=125.0)):
        assert restored.predict(readings)[0] == pytest.approx(model.predict(readings)[0])


class FakeMinio:
    """Just enough of the MinIO client: objects in a dict."""

    def __init__(self):
        self.objects = {}
        self.buckets = set()

    def bucket_exists(self, bucket):
        return bucket in self.buckets

    def make_bucket(self, bucket):
        self.buckets.add(bucket)

    def put_object(self, bucket, name, data, length):
        self.objects[name] = data.read()

    def list_objects(self, bucket, prefix, recursive):
        return [SimpleNamespace(object_name=n) for n in self.objects if n.startswith(prefix)]

    def get_object(self, bucket, name):
        response = io.BytesIO(self.objects[name])
        response.release_conn = lambda: None
        return response


@pytest.fixture
def minio(monkeypatch):
    fake = FakeMinio()
    monkeypatch.setattr(store, "_client", lambda: (fake, "models"))
    return fake


def test_nothing_stored_yet_means_no_model(minio):
    assert store.load_latest() is None  # no bucket
    minio.make_bucket("models")
    assert store.load_latest() is None  # empty bucket


def test_the_newest_complete_version_is_loaded(minio, booster):
    assert store.save(booster) == "v1"
    assert minio.buckets == {"models"} and set(minio.objects) == {"anomaly/v1/model.bin", "anomaly/v1/config.json"}

    booster.config["version"] = "v2"
    store.save(booster)
    # v3 was interrupted before its config.json was written, so it must be ignored
    minio.objects["anomaly/v3/model.bin"] = b"half an upload"

    loaded = store.load_latest()
    assert loaded.config["version"] == "v2"
    assert loaded.predict(window(engine_temp=125.0))[1] is True


def test_service_loads_what_exists_and_carries_on_without_the_rest(minio, booster, monkeypatch):
    monkeypatch.setattr(main, "MODEL", None)
    monkeypatch.setattr(main, "RUL_MODELS", {})
    main.reload_model()  # nothing trained yet: no models, no exception
    assert main.MODEL is None and main.RUL_MODELS == {}

    store.save(booster)
    store.upload(
        "rul/tyres", "r1", {"version": "r1", "component": "tyres", "kind": "linear", "default_rate": 0.03}, b"{}"
    )
    assert main.reload()["rul_models"] == {"tyres": "r1"}
    assert main.MODEL.config["version"] == "v1"

    def unreachable():
        raise ConnectionError("MinIO is down")

    monkeypatch.setattr(store, "_client", unreachable)
    main.reload_model()  # keeps what it had
    assert main.MODEL.config["version"] == "v1" and set(main.RUL_MODELS) == {"tyres"}


POINTS = [(12.97, 77.59), (12.95, 77.60), (12.99, 77.62)]


def osrm_reply(snap_m=10.0, hole=False):
    """What OSRM answers: a table with every point `snap_m` from a road, or a route."""

    def reply(service, points, query):
        if service == "route":
            return {"routes": [{"geometry": {"coordinates": [[lng, lat] for lat, lng in points] + [[77.7, 13.0]]}}]}
        n = len(points)
        table = [[0.0 if i == j else 1000.0 * abs(i - j) for j in range(n)] for i in range(n)]
        if hole:
            table[0][1] = None
        return {"sources": [{"distance": snap_m}] * n, "distances": table, "durations": table}

    return reply


def test_road_distances_are_used_when_osrm_covers_every_point(monkeypatch):
    monkeypatch.setattr(routing, "_osrm", osrm_reply())
    dist, dur, source, note = routing.matrix(POINTS)
    assert source == "osrm" and note is None and dist[0][2] == 2000.0
    assert routing.geometry(POINTS, source)[-1] == (13.0, 77.7)  # OSRM's line, as (lat, lng)


@pytest.mark.parametrize(
    "reply, why",
    [
        (osrm_reply(snap_m=50_000), "outside the OSRM map region"),
        (osrm_reply(hole=True), "no road between some points"),
    ],
)
def test_points_osrm_cannot_route_fall_back_to_straight_lines(monkeypatch, reply, why):
    monkeypatch.setattr(routing, "_osrm", reply)
    dist, dur, source, note = routing.matrix(POINTS)
    assert source == "straight-line" and why in note
    assert dist[0][1] == pytest.approx(routing.haversine_m(POINTS[0], POINTS[1]) * routing.FALLBACK_DETOUR)
    assert routing.geometry(POINTS, source) == POINTS  # straight legs between the points
