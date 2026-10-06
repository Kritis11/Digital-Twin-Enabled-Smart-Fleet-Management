# Driver score

A score from 0 to 100 per trip, per day and per period, for each vehicle. It is a plain formula over
detected driving events, with every number in `backend/src/main/resources/application.yml` under
`fleet.driving`. There is no model involved.

## Formula

```
penalty points = sum over events of  weight(event type) x multiplier(severity)

score = max(0, 100 - penalty points x 100 / max(distance in km, min-score-km))
```

In words: the score is 100 minus the penalty points collected per 100 km. Normalising by distance
means a long careful trip is not marked down for having more events than a short one, and
`min-score-km` (10) stops a single event on a very short trip from producing an extreme score.

- **Per trip**: the trip's own events and distance. Stored on the trip (`penalty_points`, `driver_score`).
- **Per day / per period**: the same formula over the summed penalty points and summed distance of
  the trips that started in that day or period. It is not an average of trip scores, so short trips
  do not count as much as long ones.

## Weights

| Event | Weight | Detected when |
|---|---|---|
| `HARSH_BRAKING` | 1.0 | Peak deceleration since the previous reading is at least `harsh-brake-ms2` (3.0 m/s²) |
| `RAPID_ACCELERATION` | 0.6 | Peak acceleration is at least `rapid-accel-ms2` (2.5 m/s²) |
| `SPEEDING` | 0.4 | Speed is above `speed-limit-kmh` (80). One event per continuous run above the limit |
| `SHARP_CORNERING` | 0.6 | Heading changes by at least `cornering-degrees` (25°) between two readings while the slower of them is at or above `cornering-speed-kmh` (30). At most one per minute |
| `EXCESSIVE_IDLING` | 0.3 | Standing with the engine on for at least `idle-after` (2 min) |

Fuel events (`FUEL_DROP`, `LOW_EFFICIENCY`) are stored in the same table but carry no weight: they
are not the driver's doing.

## Severity

| Severity | Multiplier | Braking / acceleration | Speeding (peak) | Cornering (speed) | Idling (duration) |
|---|---|---|---|---|---|
| LOW | 1.0 | under 1.3 × the limit | under 10% over | under 1.25 × the limit | under 2 × the limit |
| MEDIUM | 1.5 | 1.3 × or more | 10% or more over | 1.25 × or more | 2 × or more |
| HIGH | 2.0 | 1.6 × or more | 20% or more over | 1.5 × or more | 3 × or more |

## Worked example

A 120 km day with 4 harsh braking events (3 LOW, 1 HIGH), 6 speeding runs (all MEDIUM) and
1 excessive idle (LOW):

```
penalty = 3 x 1.0 x 1.0  +  1 x 1.0 x 2.0  +  6 x 0.4 x 1.5  +  1 x 0.3 x 1.0  =  8.9
score   = 100 - 8.9 x 100 / 120  =  92.6
```

## Trips

A trip starts when the vehicle moves and ends when telemetry stops, or the vehicle stands still, for
`trip-gap` (5 minutes). A trip that ends by standing still ends at the moment it stopped; the parked
time is not part of it. Trips shorter than 100 m are discarded.

## Limits

- Braking and acceleration use the peak values the vehicle reports (`accel_min`, `accel_max`). If a
  vehicle does not report them, they are derived from the change in speed, which only works when
  readings are at most 10 seconds apart.
- Cornering is judged from position changes, so it is only as good as the reporting interval.
- Speed is compared with one fleet-wide limit, not the limit of the road being driven.
- Events and trips for history are produced by replaying stored telemetry
  (`POST /api/admin/reanalyse`), with whatever thresholds are configured at that moment.
