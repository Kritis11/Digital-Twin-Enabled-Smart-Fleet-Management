"""Fleet Twin ML service. Every model endpoint is a placeholder returning dummy values."""

from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="Fleet Twin ML Service", version="0.1.0")


class TelemetryFeatures(BaseModel):
    """Latest readings for one vehicle. All optional so callers can send what they have."""

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
    dtc_codes: list[str] = []


class VehicleRequest(BaseModel):
    vehicle_id: int
    telemetry: TelemetryFeatures = TelemetryFeatures()


class RulRequest(VehicleRequest):
    component: str


class AnomalyResponse(BaseModel):
    vehicle_id: int
    is_anomaly: bool
    score: float = Field(ge=0, le=1)
    reasons: list[str]


class HealthScoreResponse(BaseModel):
    vehicle_id: int
    health_score: float = Field(ge=0, le=100)
    component_scores: dict[str, float]


class RulResponse(BaseModel):
    vehicle_id: int
    component: str
    rul_days: float = Field(ge=0)
    confidence: float = Field(ge=0, le=1)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/anomaly")
def anomaly(req: VehicleRequest) -> AnomalyResponse:
    return AnomalyResponse(vehicle_id=req.vehicle_id, is_anomaly=False, score=0.0, reasons=[])


@app.post("/health-score")
def health_score(req: VehicleRequest) -> HealthScoreResponse:
    return HealthScoreResponse(
        vehicle_id=req.vehicle_id,
        health_score=100.0,
        component_scores={"engine": 100.0, "battery": 100.0, "brakes": 100.0, "tyres": 100.0},
    )


@app.post("/rul")
def rul(req: RulRequest) -> RulResponse:
    return RulResponse(vehicle_id=req.vehicle_id, component=req.component, rul_days=365.0, confidence=0.0)
