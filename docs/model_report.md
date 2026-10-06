<!-- anomaly:start -->
# Anomaly detection model

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
<!-- anomaly:end -->

<!-- rul:start -->
# Remaining useful life models

Generated 2026-10-06 17:38 UTC by `ml-service/training/train_rul.py`. Model version `20261006T173754Z`.

## Data

Run-to-failure lifecycles from fast-forwarded simulator history: one row per vehicle per day, labelled with the days left until that part's failure. Lifecycles that ended in a preventive service, and the ones still running, have no known failure date and are left out.

| Component | Lifecycles | Vehicles | Daily rows | Mean life (days) |
|---|---|---|---|---|
| brakes | 19 | 5 | 365 | 19 |
| battery | 11 | 5 | 294 | 26 |
| tyres | 12 | 5 | 348 | 29 |
| engine | 11 | 5 | 366 | 33 |

Features (11): `used`, `rate_3d`, `rate_7d`, `rate_life`, `days_in_service`, `km_in_service`, `hours_in_service`, `harsh_brakes_in_service`, `km_per_day_7d`, `hours_per_day_7d`, `aggressiveness_7d`.

## Results

Leave-one-vehicle-out cross-validation: each vehicle's lifecycles are predicted by a model trained only on the other vehicles, so no lifecycle (or vehicle) is on both sides of a split. Errors are in days. Raw coverage is the share of true values inside XGBoost's own 10%–90% quantiles (nominal: 80%).

| Component | Model | MAE | RMSE | Within ±7 days | Raw interval coverage |
|---|---|---|---|---|---|
| brakes | Linear extrapolation | 2.29 | 3.48 | 94% | – |
| brakes | XGBoost quantile regression **(selected)** | 1.99 | 2.86 | 96% | 47% |
| battery | Linear extrapolation | 4.33 | 7.44 | 79% | – |
| battery | XGBoost quantile regression **(selected)** | 1.77 | 2.72 | 95% | 49% |
| tyres | Linear extrapolation **(selected)** | 3.14 | 5.61 | 90% | – |
| tyres | XGBoost quantile regression | 3.29 | 4.99 | 84% | 45% |
| engine | Linear extrapolation | 4.16 | 7.03 | 81% | – |
| engine | XGBoost quantile regression **(selected)** | 2.41 | 3.40 | 93% | 58% |

The model with the lower MAE is selected per component and refitted on all vehicles. Uploaded to MinIO as `models/rul/<component>/20261006T173754Z/`.

## Prediction intervals and confidence

- XGBoost: three quantile models (10%, 50%, 90%); the median is `rul_days`. The raw outer quantiles are too narrow on vehicles the model has not seen (see the table), so both bounds are widened by a fixed number of days chosen so that 80% of held-out true values fall inside (conformalised quantile regression): brakes ±1.7 d, battery ±0.9 d, engine ±1.7 d.
- Linear baseline: the bounds are the 10th and 90th percentile of its error on held-out vehicles.
- `confidence` = 1 − (upper − lower) / (2 × max(rul_days, 7)), clamped to 0–1: narrow intervals relative to the prediction score high.

## Caveats

- Simulated wear is fast on purpose (parts last weeks) so that 90 days holds several lifecycles per vehicle. The models learn the simulator's wear curves, not real parts.
- The wear indicator itself is a telemetry reading here. On real vehicles pad thickness, tread depth and battery health are measured rarely or estimated, which makes the problem much harder.
- Five vehicles is a small fleet: each cross-validation fold tests on a single vehicle, so the numbers will move noticeably between runs of the simulator.
- For a part's first lifecycle in the data the install date is unknown, so `days_in_service` and the cumulative usage features count from the first reading instead.
<!-- rul:end -->
