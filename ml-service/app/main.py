"""Fleet Twin ML service: anomaly detection and health score. /rul is still a placeholder (Phase 2)."""

import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Literal

import pandas as pd
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from . import model as model_store
from .model import AnomalyModel

# Repo-root .env when run from a checkout; real env vars (e.g. in Docker) win.
load_dotenv(Path(__file__).resolve().parents[2] / ".env")

log = logging.getLogger("uvicorn.error")

# Health score = 100 - penalties. All three weights are configurable.
WARNING_PENALTY = float(os.getenv("HEALTH_WARNING_PENALTY", "10"))
CRITICAL_PENALTY = float(os.getenv("HEALTH_CRITICAL_PENALTY", "25"))
ANOMALY_WEIGHT = float(os.getenv("HEALTH_ANOMALY_WEIGHT", "30"))

MODEL: AnomalyModel | None = None


def reload_model() -> None:
    """Loads the newest model from MinIO. On any failure the current model (or none) stays in place."""
    global MODEL
    try:
        latest = model_store.load_latest()
    except Exception as e:
        log.warning("Could not load a model from MinIO: %s", e)
        return
    if latest is None:
        log.warning("No anomaly model in MinIO yet; /anomaly returns 503 until one is trained")
        return
    MODEL = latest
    log.info("Loaded anomaly model %s (%s)", latest.config["version"], latest.config["kind"])


@asynccontextmanager
async def lifespan(_: FastAPI):
    reload_model()
    yield


app = FastAPI(title="Fleet Twin ML Service", version="0.2.0", lifespan=lifespan)


class Reading(BaseModel):
    """One telemetry reading. Signals are optional so callers can send what they have."""

    ts: datetime
    speed: float | None = None
    engine_temp: float | None = None
    rpm: float | None = None
    battery_voltage: float | None = None
    fuel_level: float | None = None
    vibration: float | None = None
    tyre_pressure_fl: float | None = None
    tyre_pressure_fr: float | None = None
    tyre_pressure_rl: float | None = None
    tyre_pressure_rr: float | None = None
    brake_pad_wear: float | None = None


class AnomalyRequest(BaseModel):
    vehicle_id: int
    # The vehicle's recent readings (ideally the last 2 minutes); the newest one is scored.
    readings: list[Reading] = Field(min_length=1)


class AnomalyResponse(BaseModel):
    vehicle_id: int
    is_anomaly: bool
    score: float = Field(ge=0, le=1)
    reasons: list[str]
    model_version: str


class HealthScoreRequest(BaseModel):
    vehicle_id: int
    components: dict[str, Literal["OK", "WARNING", "CRITICAL"]] = {}
    anomaly_score: float | None = Field(default=None, ge=0, le=1)


class HealthScoreResponse(BaseModel):
    vehicle_id: int
    health_score: float = Field(ge=0, le=100)
    # What was subtracted from 100, per component plus "anomaly". Empty for a perfect score.
    deductions: dict[str, float]


class RulRequest(BaseModel):
    vehicle_id: int
    component: str


class RulResponse(BaseModel):
    vehicle_id: int
    component: str
    rul_days: float = Field(ge=0)
    confidence: float = Field(ge=0, le=1)


@app.get("/health")
def health() -> dict[str, str | None]:
    return {"status": "ok", "model_version": MODEL.config["version"] if MODEL else None}


@app.post("/model/reload")
def reload() -> dict[str, str | None]:
    """Picks up a newly trained model without restarting the service."""
    reload_model()
    return health()


@app.post("/anomaly")
def anomaly(req: AnomalyRequest) -> AnomalyResponse:
    if MODEL is None:
        raise HTTPException(status_code=503, detail="no anomaly model loaded")
    readings = pd.DataFrame([r.model_dump() for r in req.readings]).assign(vehicle_id=req.vehicle_id)
    # None -> NaN; without this an all-missing signal would be an object column and break rolling()
    readings = readings.astype({c: float for c in readings.columns if c not in ("ts", "vehicle_id")})
    readings["ts"] = pd.to_datetime(readings["ts"], utc=True)
    score, is_anomaly, reasons = MODEL.predict(readings)
    return AnomalyResponse(
        vehicle_id=req.vehicle_id,
        is_anomaly=is_anomaly,
        score=round(score, 4),
        reasons=reasons,
        model_version=MODEL.config["version"],
    )


@app.post("/health-score")
def health_score(req: HealthScoreRequest) -> HealthScoreResponse:
    penalty = {"OK": 0.0, "WARNING": WARNING_PENALTY, "CRITICAL": CRITICAL_PENALTY}
    deductions = {name: penalty[status] for name, status in req.components.items() if penalty[status]}
    if req.anomaly_score:
        deductions["anomaly"] = round(ANOMALY_WEIGHT * req.anomaly_score, 1)
    return HealthScoreResponse(
        vehicle_id=req.vehicle_id,
        health_score=max(0.0, round(100.0 - sum(deductions.values()), 1)),
        deductions=deductions,
    )


@app.post("/rul")
def rul(req: RulRequest) -> RulResponse:
    # Placeholder until Phase 2.
    return RulResponse(vehicle_id=req.vehicle_id, component=req.component, rul_days=365.0, confidence=0.0)
