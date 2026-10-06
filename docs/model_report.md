# Anomaly model report

Generated 2026-10-06 15:47 UTC by `ml-service/training/train.py`. Model version `20261006T154759Z`.

## Data

- 7080 telemetry rows over 47 minutes from the simulator; 2049 rows (29%) carry an `injected_fault` label.
- 104 fault episodes: low_battery 28, low_tyre_pressure 24, overheating 32, vibration_spike 20.
- Time-based split at 2026-10-06 15:38:29 UTC: the first 5660 rows train (30% faulty), the last 1420 rows test (25% faulty). No shuffling.
- 88 features: latest value plus mean, std, min, max and rate of change over 30 s and 120 s windows, per vehicle, for `engine_temp`, `vibration`, `rpm`, `battery_voltage`, `tyre_pressure_fl`, `tyre_pressure_fr`, `tyre_pressure_rl`, `tyre_pressure_rr`.

## Results on the test split

| Model | Precision | Recall | F1 |
|---|---|---|---|
| Isolation Forest (unsupervised) | 0.779 | 0.151 | 0.252 |
| XGBoost (supervised) | 0.988 | 0.969 | 0.978 |

### Isolation Forest (unsupervised)

| | Predicted healthy | Predicted anomaly |
|---|---|---|
| **Actually healthy** | 1053 | 15 |
| **Actually faulty** | 299 | 53 |

Recall by fault type: low_battery 0.00, low_tyre_pressure 0.53, overheating 0.00, vibration_spike 0.01.

### XGBoost (supervised)

| | Predicted healthy | Predicted anomaly |
|---|---|---|
| **Actually healthy** | 1064 | 4 |
| **Actually faulty** | 11 | 341 |

Recall by fault type: low_battery 1.00, low_tyre_pressure 1.00, overheating 0.92, vibration_spike 1.00.

## Top XGBoost features (share of total gain)

| Feature | Share |
|---|---|
| `battery_voltage_roc_30s` | 13.1% |
| `vibration_last` | 9.6% |
| `battery_voltage_last` | 7.2% |
| `engine_temp_roc_30s` | 6.0% |
| `battery_voltage_max_30s` | 5.9% |
| `battery_voltage_roc_120s` | 5.7% |
| `tyre_pressure_rr_last` | 5.3% |
| `tyre_pressure_fr_roc_120s` | 5.3% |
| `tyre_pressure_rr_roc_120s` | 4.2% |
| `engine_temp_last` | 3.8% |

## Selected model

**XGBoost (supervised)**, chosen by F1. Uploaded to MinIO as `models/anomaly/20261006T154759Z/`.

## Caveats

- The labels and the faults both come from the simulator. Its faults are abrupt step changes far outside the healthy range, so they are easy to separate; these scores say the pipeline works, not how the model would do on real vehicles.
- A row is labelled faulty only while the fault is active. Engine temperature stays high for a few readings after an overheating fault ends, so some "false positives" are the tail of a real fault.
- Isolation Forest assumes anomalies are rare, but about a third of simulator rows are faulty, which is a poor fit for it.
