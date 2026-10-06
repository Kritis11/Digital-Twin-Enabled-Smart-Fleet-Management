# Anomaly model report

Generated 2026-10-06 15:15 UTC by `ml-service/training/train.py`. Model version `20261006T151526Z`.

## Data

- 2190 telemetry rows over 15 minutes from the simulator; 645 rows (29%) carry an `injected_fault` label.
- 34 fault episodes: low_battery 14, low_tyre_pressure 4, overheating 11, vibration_spike 5.
- Time-based split at 2026-10-06 15:12:15 UTC: the first 1751 rows train (31% faulty), the last 439 rows test (24% faulty). No shuffling.
- 88 features: latest value plus mean, std, min, max and rate of change over 30 s and 120 s windows, per vehicle, for `engine_temp`, `vibration`, `rpm`, `battery_voltage`, `tyre_pressure_fl`, `tyre_pressure_fr`, `tyre_pressure_rl`, `tyre_pressure_rr`.

## Results on the test split

| Model | Precision | Recall | F1 |
|---|---|---|---|
| Isolation Forest (unsupervised) | 0.960 | 0.226 | 0.366 |
| XGBoost (supervised) | 0.946 | 0.991 | 0.968 |

### Isolation Forest (unsupervised)

| | Predicted healthy | Predicted anomaly |
|---|---|---|
| **Actually healthy** | 332 | 1 |
| **Actually faulty** | 82 | 24 |

Recall by fault type: low_battery 0.27, overheating 0.00, vibration_spike 0.32.

### XGBoost (supervised)

| | Predicted healthy | Predicted anomaly |
|---|---|---|
| **Actually healthy** | 327 | 6 |
| **Actually faulty** | 1 | 105 |

Recall by fault type: low_battery 1.00, overheating 0.95, vibration_spike 1.00.

## Top XGBoost features (share of total gain)

| Feature | Share |
|---|---|
| `tyre_pressure_rr_roc_30s` | 20.4% |
| `tyre_pressure_rr_roc_120s` | 12.8% |
| `battery_voltage_roc_120s` | 7.2% |
| `tyre_pressure_rl_last` | 6.3% |
| `battery_voltage_last` | 5.5% |
| `vibration_last` | 5.4% |
| `engine_temp_last` | 4.6% |
| `tyre_pressure_rr_last` | 3.7% |
| `battery_voltage_roc_30s` | 2.9% |
| `engine_temp_mean_120s` | 2.9% |

## Selected model

**XGBoost (supervised)**, chosen by F1. Uploaded to MinIO as `models/anomaly/20261006T151526Z/`.

## Caveats

- The labels and the faults both come from the simulator. Its faults are abrupt step changes far outside the healthy range, so they are easy to separate; these scores say the pipeline works, not how the model would do on real vehicles.
- A row is labelled faulty only while the fault is active. Engine temperature stays high for a few readings after an overheating fault ends, so some "false positives" are the tail of a real fault.
- Isolation Forest assumes anomalies are rare, but about a third of simulator rows are faulty, which is a poor fit for it.
