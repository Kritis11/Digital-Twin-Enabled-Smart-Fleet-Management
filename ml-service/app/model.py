"""The anomaly model wrapper and its versioned storage in MinIO (bucket `models`, prefix `anomaly/<version>/`)."""

import io
import json
import os
from dataclasses import dataclass

import joblib
import numpy as np
import pandas as pd
import xgboost as xgb
from minio import Minio

from .features import build_features

PREFIX = "anomaly"


@dataclass
class AnomalyModel:
    """config keys: version, kind (xgboost | isolation_forest), signals, windows_s, features,
    threshold, feature_means, feature_stds, metrics."""

    config: dict
    estimator: object  # xgb.Booster or sklearn IsolationForest

    def score(self, features: pd.DataFrame) -> np.ndarray:
        """Anomaly score in [0, 1] per feature row."""
        X = features[self.config["features"]]
        if self.config["kind"] == "xgboost":
            return self.estimator.predict(xgb.DMatrix(X))
        # decision_function is > 0 for inliers and < 0 for outliers, roughly within [-0.5, 0.5]
        X = X.fillna(self.config["feature_means"])
        return np.clip(0.5 - self.estimator.decision_function(X), 0.0, 1.0)

    def predict(self, readings: pd.DataFrame, top: int = 3) -> tuple[float, bool, list[str]]:
        """Scores the newest reading of one vehicle's recent window. Returns (score, is_anomaly, reasons)."""
        features = build_features(readings, self.config["signals"], self.config["windows_s"]).iloc[[-1]]
        score = float(self.score(features)[0])
        is_anomaly = score >= self.config["threshold"]
        return score, is_anomaly, self._reasons(features, top) if is_anomaly else []

    def _reasons(self, features: pd.DataFrame, top: int) -> list[str]:
        names = self.config["features"]
        row = features[names].iloc[0]
        if self.config["kind"] == "xgboost":
            # TreeSHAP contributions, built into XGBoost; the last column is the bias term.
            weights = self.estimator.predict(xgb.DMatrix(features[names]), pred_contribs=True)[0][:-1]
        else:
            means = pd.Series(self.config["feature_means"])
            stds = pd.Series(self.config["feature_stds"]).replace(0, 1.0)
            weights = ((row - means[names]) / stds[names]).abs().fillna(0).to_numpy()
        order = [i for i in np.argsort(weights)[::-1][:top] if weights[i] > 0]
        return [f"{names[i]}={row.iloc[i]:.2f}" for i in order]

    def dumps(self) -> bytes:
        if self.config["kind"] == "xgboost":
            return bytes(self.estimator.save_raw("json"))
        buffer = io.BytesIO()
        joblib.dump(self.estimator, buffer)
        return buffer.getvalue()

    @staticmethod
    def loads(config: dict, data: bytes) -> "AnomalyModel":
        if config["kind"] == "xgboost":
            booster = xgb.Booster()
            booster.load_model(bytearray(data))
            return AnomalyModel(config, booster)
        # joblib is pickle: only ever load from our own bucket.
        return AnomalyModel(config, joblib.load(io.BytesIO(data)))


def _client() -> tuple[Minio, str]:
    endpoint = os.getenv("MINIO_ENDPOINT", f"localhost:{os.getenv('MINIO_API_PORT', '9000')}")
    client = Minio(
        endpoint,
        access_key=os.environ["MINIO_ROOT_USER"],
        secret_key=os.environ["MINIO_ROOT_PASSWORD"],
        secure=os.getenv("MINIO_SECURE", "false").lower() == "true",
    )
    return client, os.getenv("MODEL_BUCKET", "models")


def save(model: AnomalyModel) -> str:
    """Uploads under anomaly/<version>/ and returns the version."""
    client, bucket = _client()
    if not client.bucket_exists(bucket):
        client.make_bucket(bucket)
    version = model.config["version"]
    for name, data in (("model.bin", model.dumps()), ("config.json", json.dumps(model.config, indent=2).encode())):
        client.put_object(bucket, f"{PREFIX}/{version}/{name}", io.BytesIO(data), len(data))
    return version


def load_latest() -> AnomalyModel | None:
    """Newest version in the bucket, or None if nothing has been trained yet."""
    client, bucket = _client()
    if not client.bucket_exists(bucket):
        return None
    # config.json is uploaded last, so its presence means the version is complete.
    versions = sorted(
        o.object_name.split("/")[1]
        for o in client.list_objects(bucket, prefix=f"{PREFIX}/", recursive=True)
        if o.object_name.endswith("/config.json")
    )
    if not versions:
        return None

    def read(name: str) -> bytes:
        response = client.get_object(bucket, f"{PREFIX}/{versions[-1]}/{name}")
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()

    return AnomalyModel.loads(json.loads(read("config.json")), read("model.bin"))
