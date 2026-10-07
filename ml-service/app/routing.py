"""Vehicle routing: Google OR-Tools over a distance and time matrix from OSRM.

If OSRM is unreachable, or a point lies outside the map region it was built for, the matrix falls
back to straight-line distances (times a detour factor) at a fixed average speed.
"""

import itertools
import json
import math
import os
import urllib.request

from ortools.constraint_solver import pywrapcp, routing_enums_pb2

OSRM_URL = os.getenv("OSRM_URL", "http://localhost:5001")
OSRM_TIMEOUT_S = float(os.getenv("OSRM_TIMEOUT_S", "5"))
# A point that snaps to a road further away than this is outside the OSRM region.
OSRM_MAX_SNAP_M = float(os.getenv("OSRM_MAX_SNAP_M", "2000"))
FALLBACK_SPEED_KMH = float(os.getenv("ROUTE_FALLBACK_SPEED_KMH", "40"))
# Roads are longer than the straight line between two points by roughly this factor.
FALLBACK_DETOUR = float(os.getenv("ROUTE_FALLBACK_DETOUR", "1.3"))
MAX_ROUTE_S = int(os.getenv("ROUTE_MAX_SECONDS", str(24 * 3600)))
# Cost of leaving a stop unserved; far above any real route so stops are dropped only when they cannot be reached in time.
DROP_PENALTY = 10**9

Point = tuple[float, float]  # (lat, lng)
Matrix = list[list[float]]


def haversine_m(a: Point, b: Point) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def straight_line_matrix(points: list[Point]) -> tuple[Matrix, Matrix]:
    dist = [[haversine_m(a, b) * FALLBACK_DETOUR for b in points] for a in points]
    return dist, [[d / (FALLBACK_SPEED_KMH / 3.6) for d in row] for row in dist]


def _osrm(service: str, points: list[Point], query: str) -> dict:
    coords = ";".join(f"{lng:.6f},{lat:.6f}" for lat, lng in points)
    with urllib.request.urlopen(f"{OSRM_URL}/{service}/v1/driving/{coords}?{query}", timeout=OSRM_TIMEOUT_S) as r:
        return json.load(r)


def osrm_matrix(points: list[Point]) -> tuple[Matrix, Matrix]:
    """Road distances (m) and times (s). Raises if OSRM cannot answer for every pair of points."""
    body = _osrm("table", points, "annotations=distance,duration")
    off_map = [i for i, s in enumerate(body["sources"]) if s["distance"] > OSRM_MAX_SNAP_M]
    if off_map:
        raise ValueError(f"{len(off_map)} of {len(points)} points are outside the OSRM map region")
    if any(v is None for row in body["distances"] + body["durations"] for v in row):
        raise ValueError("OSRM found no road between some points")
    return body["distances"], body["durations"]


def matrix(points: list[Point]) -> tuple[Matrix, Matrix, str, str | None]:
    """(distances, durations, source, note). source is "osrm" or "straight-line"; note says why it fell back."""
    try:
        return (*osrm_matrix(points), "osrm", None)
    except Exception as e:
        return (*straight_line_matrix(points), "straight-line", f"OSRM not used: {e}")


def geometry(points: list[Point], source: str) -> list[Point]:
    """The line to draw for a route through these points: along roads if OSRM planned it, else straight legs."""
    if source == "osrm" and len(points) > 1:
        try:
            line = _osrm("route", points, "overview=full&geometries=geojson")["routes"][0]["geometry"]["coordinates"]
            return [(lat, lng) for lng, lat in line]
        except Exception:
            pass
    return points


def solve(
    vehicles: list[dict],
    stops: list[dict],
    dist: Matrix,
    dur: Matrix,
    *,
    return_to_start: bool,
    balance: int,
    max_stops_per_vehicle: int | None,
    time_limit_s: float,
) -> tuple[list[dict], list[int]]:
    """Assigns and orders stops. Matrix nodes are the vehicles' start points followed by the stops.

    vehicles: {id, cost_factor}; a higher cost factor makes each km on that vehicle count for more, so the
    solver prefers the others. stops: {id, service_s, window_start_s, window_end_s}, the window in seconds
    after departure. Returns (routes, ids of stops no vehicle could serve in time).
    """
    n_vehicles = len(vehicles)
    depots = list(range(n_vehicles))
    manager = pywrapcp.RoutingIndexManager(n_vehicles + len(stops), n_vehicles, depots, depots)
    routing = pywrapcp.RoutingModel(manager)

    def leg(matrix_: Matrix, i: int, j: int) -> float:
        # Without a return trip the way back to the start is free and takes no time.
        return 0 if j < n_vehicles and not return_to_start else matrix_[i][j]

    for v, vehicle in enumerate(vehicles):
        factor = vehicle["cost_factor"]
        cost = routing.RegisterTransitCallback(
            lambda a, b, f=factor: int(leg(dist, manager.IndexToNode(a), manager.IndexToNode(b)) * f)
        )
        routing.SetArcCostEvaluatorOfVehicle(cost, v)

    def travel_time(a: int, b: int) -> int:
        i, j = manager.IndexToNode(a), manager.IndexToNode(b)
        service = stops[i - n_vehicles]["service_s"] if i >= n_vehicles else 0
        return int(leg(dur, i, j) + service)

    routing.AddDimension(routing.RegisterTransitCallback(travel_time), MAX_ROUTE_S, MAX_ROUTE_S, True, "Time")
    time = routing.GetDimensionOrDie("Time")
    # Penalises the longest route, which spreads the work instead of loading one vehicle.
    time.SetGlobalSpanCostCoefficient(balance)

    if max_stops_per_vehicle:
        count = routing.RegisterUnaryTransitCallback(lambda a: int(manager.IndexToNode(a) >= n_vehicles))
        routing.AddDimension(count, 0, max_stops_per_vehicle, True, "Stops")

    for s, stop in enumerate(stops):
        index = manager.NodeToIndex(n_vehicles + s)
        routing.AddDisjunction([index], DROP_PENALTY)
        start, end = stop.get("window_start_s"), stop.get("window_end_s")
        if start is not None or end is not None:
            # 0 is a real bound (the window closes at departure), so test for None rather than falsiness
            time.CumulVar(index).SetRange(start or 0, MAX_ROUTE_S if end is None else min(end, MAX_ROUTE_S))

    params = pywrapcp.DefaultRoutingSearchParameters()
    params.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    params.local_search_metaheuristic = routing_enums_pb2.LocalSearchMetaheuristic.GUIDED_LOCAL_SEARCH
    params.time_limit.FromMilliseconds(int(time_limit_s * 1000))
    solution = routing.SolveWithParameters(params)
    if solution is None:
        return [], [s["id"] for s in stops]

    routes, served = [], set()
    for v, vehicle in enumerate(vehicles):
        index, nodes, visits = routing.Start(v), [v], []
        while not routing.IsEnd(index):
            index = solution.Value(routing.NextVar(index))
            node = manager.IndexToNode(index)
            nodes.append(node)
            if node >= n_vehicles:
                stop = stops[node - n_vehicles]
                served.add(stop["id"])
                visits.append({"id": stop["id"], "arrival_s": solution.Value(time.CumulVar(index))})
        if visits:
            routes.append(
                {
                    "vehicle_id": vehicle["id"],
                    "stops": visits,
                    "distance_m": round(sum(leg(dist, a, b) for a, b in itertools.pairwise(nodes))),
                    "duration_s": solution.Value(time.CumulVar(index)),
                    "nodes": nodes if return_to_start else nodes[:-1],
                }
            )
    return routes, [s["id"] for s in stops if s["id"] not in served]
