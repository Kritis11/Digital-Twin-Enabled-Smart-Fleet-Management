import pandas as pd
import pytest

from app.features import SIGNALS, build_features, feature_names


def frame(rows):
    """rows: (vehicle_id, seconds, engine_temp)"""
    t0 = pd.Timestamp("2026-10-06T10:00:00Z")
    return pd.DataFrame({
        "vehicle_id": [r[0] for r in rows],
        "ts": [t0 + pd.Timedelta(seconds=r[1]) for r in rows],
        "engine_temp": [float(r[2]) for r in rows],
    })


def test_columns_match_feature_names_and_missing_signals_are_nan():
    X = build_features(frame([(1, 0, 90)]))
    assert list(X.columns) == feature_names()
    assert len(feature_names()) == len(SIGNALS) * 11  # last + 5 stats x 2 windows
    assert X["vibration_mean_30s"].isna().all()
    assert X["vibration_std_30s"].eq(0).all()


def test_rolling_stats_over_time_windows():
    # readings every 10 s: 90, 92, 94, 96, 110
    X = build_features(frame([(1, 0, 90), (1, 10, 92), (1, 20, 94), (1, 30, 96), (1, 40, 110)]))
    last = X.iloc[-1]
    # 30 s window is (10 s, 40 s]: 94, 96, 110. The reading at exactly t-30 s is excluded.
    assert last["engine_temp_last"] == 110
    assert last["engine_temp_mean_30s"] == pytest.approx(100)
    assert last["engine_temp_min_30s"] == 94
    assert last["engine_temp_max_30s"] == 110
    assert last["engine_temp_std_30s"] == pytest.approx(pd.Series([94, 96, 110]).std())
    assert last["engine_temp_roc_30s"] == pytest.approx((110 - 94) / 30)
    # 120 s window still sees everything
    assert last["engine_temp_min_120s"] == 90
    assert last["engine_temp_roc_120s"] == pytest.approx((110 - 90) / 120)
    # first row: a single reading
    assert X.iloc[0]["engine_temp_std_30s"] == 0
    assert X.iloc[0]["engine_temp_roc_30s"] == 0


def test_windows_never_mix_vehicles_and_output_follows_input_index():
    # interleaved and out of time order on purpose
    df = frame([(2, 10, 200), (1, 0, 90), (2, 0, 100), (1, 10, 92)])
    X = build_features(df)
    assert list(X.index) == [1, 3, 2, 0]  # sorted by vehicle, then time, keeping the caller's index
    assert X.loc[3, "engine_temp_max_30s"] == 92    # vehicle 1 never sees vehicle 2's 200
    assert X.loc[0, "engine_temp_min_30s"] == 100
    assert X.loc[0, "engine_temp_mean_30s"] == 150
