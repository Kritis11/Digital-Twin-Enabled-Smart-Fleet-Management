import pytest
from fastapi.testclient import TestClient

from app import main, routing

# Two clusters about 30 km apart, with one vehicle parked in each.
WEST, EAST = (12.90, 77.40), (12.90, 77.70)
VEHICLES = [{"id": 1, "lat": WEST[0], "lng": WEST[1]}, {"id": 2, "lat": EAST[0], "lng": EAST[1]}]
STOPS = [{"id": 10, "lat": 12.91, "lng": 77.41}, {"id": 11, "lat": 12.92, "lng": 77.42},
         {"id": 20, "lat": 12.91, "lng": 77.71}, {"id": 21, "lat": 12.92, "lng": 77.72}]


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(routing, "OSRM_URL", "http://127.0.0.1:9")  # nothing listens here
    return TestClient(main.app)  # no context manager: the lifespan (MinIO load) never runs


def optimise(client, **body):
    r = client.post("/optimise-routes", json={"vehicles": VEHICLES, "stops": STOPS, "time_limit_s": 1, **body})
    assert r.status_code == 200, r.text
    return r.json()


def test_falls_back_to_straight_lines_and_keeps_each_vehicle_in_its_cluster(client):
    body = optimise(client)
    assert body["distance_source"] == "straight-line" and "OSRM not used" in body["note"]
    assert body["unassigned"] == []
    by_vehicle = {r["vehicle_id"]: sorted(s["id"] for s in r["stops"]) for r in body["routes"]}
    assert by_vehicle == {1: [10, 11], 2: [20, 21]}
    route = body["routes"][0]
    assert route["distance_m"] > 0 and route["duration_s"] > 0
    assert route["geometry"][0] == route["geometry"][-1] == list(WEST)  # out and back


def test_no_return_trip_is_shorter(client):
    there_and_back = sum(r["distance_m"] for r in optimise(client)["routes"])
    one_way = optimise(client, return_to_start=False)
    assert sum(r["distance_m"] for r in one_way["routes"]) < there_and_back
    assert len(one_way["routes"][0]["geometry"]) == 3  # start and two stops


def test_cost_factor_moves_work_to_the_cheaper_vehicle(client):
    depot = [{"id": 1, "lat": 12.9, "lng": 77.5, "cost_factor": 3.0}, {"id": 2, "lat": 12.9, "lng": 77.5}]
    body = optimise(client, vehicles=depot)
    assert [r["vehicle_id"] for r in body["routes"]] == [2]


def test_stop_that_cannot_be_reached_in_its_window_is_left_unassigned(client):
    # 30 km away at 40 km/h takes far longer than 60 seconds
    stops = [*STOPS[:2], {"id": 99, "lat": 12.90, "lng": 77.70, "window_end_s": 60}]
    body = optimise(client, vehicles=VEHICLES[:1], stops=stops)
    assert body["unassigned"] == [99]
    already_closed = [*STOPS[:2], {"id": 98, "lat": 12.91, "lng": 77.41, "window_end_s": 0}]
    assert optimise(client, vehicles=VEHICLES[:1], stops=already_closed)["unassigned"] == [98]
    assert sorted(s["id"] for s in body["routes"][0]["stops"]) == [10, 11]


def test_time_window_delays_arrival(client):
    stops = [{**STOPS[0], "window_start_s": 3600}]
    visit = optimise(client, vehicles=VEHICLES[:1], stops=stops)["routes"][0]["stops"][0]
    assert visit["arrival_s"] >= 3600


def test_max_stops_per_vehicle(client):
    depot = [{"id": 1, "lat": 12.9, "lng": 77.5}, {"id": 2, "lat": 12.9, "lng": 77.5}]
    body = optimise(client, vehicles=depot, max_stops_per_vehicle=2)
    assert sorted(len(r["stops"]) for r in body["routes"]) == [2, 2]
