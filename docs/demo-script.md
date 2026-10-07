# Demo script (10 minutes)

A walkthrough of Fleet Twin for an audience that has not seen it: what to click and what to say.
Times are a guide; the whole thing fits in ten minutes with a minute to spare for questions.

## Before the audience arrives

1. Start the stack (README, "Start and stop"): infrastructure, backend, ML service, frontend.
2. Reset to the demo state. This deletes the fleet data in the development database and rebuilds it:

   ```bash
   scripts/demo-reset.sh
   ```

   It ends by printing the state: which vehicles have failing parts, how many alerts and
   recommendations are open, and how many vehicles are fit for a route. The simulator is left
   running with random faults switched off, so nothing unexpected happens during the demo.
3. Open two browser windows on `http://localhost:4200`:
   - **Window A**: signed in as the admin (`ADMIN_USERNAME` / `ADMIN_PASSWORD` from `.env`).
   - **Window B** (a private window): signed in as a technician. Create the user first under
     User Management if it does not exist (for example `tina`, role TECHNICIAN).
4. On the **Alerts** page, filter to severity *Critical* and acknowledge any critical alert of
   `KA02CD5678` and `TN09GH3456`. That leaves two vehicles fit for a route, which step 6 relies on:
   one of them gets the live fault, the other gets the route.
5. Keep a terminal open in the project folder for the live fault in step 5.
6. Check the dot at the bottom of the menu says **Live** in both windows.

This walkthrough was run end to end against the live stack on the reset data; the timings quoted
in step 5 are the ones observed.

## The walkthrough

### 1. The problem and the idea (1 min) — *Window A, Fleet Overview*

> "A fleet operator finds out a vehicle has a problem when it breaks down. Fleet Twin keeps a live
> digital twin of every vehicle, built from the telemetry it sends every two seconds, predicts
> which parts will fail and when, and plans the work and the routes around that."

Point at the map: one arrow per vehicle, pointing where it is heading, coloured by health.

> "Five trucks in five cities. Green is healthy, amber needs attention, red has a critical
> problem. Everything on this page updates live; nothing is being refreshed."

Point at the cards: vehicles moving, open alerts, **urgent recommendations**, **lowest remaining life**.

### 2. One vehicle's twin (2 min) — *click the vehicle named on the "Lowest remaining life" card*

> "This is the twin of one truck."

- **Component tiles**: "Each part has a status from simple threshold rules: this is the classic
  monitoring layer."
- **Health score gauge**: "One number that combines those statuses with an anomaly model's verdict."
- **Remaining useful life**: "This is the predictive layer. For each part, a model trained on
  90 days of run-to-failure history predicts the days left. The bar is the prediction, the dark
  band the range it is 80% sure of. This one is nearly at the end."
- **Recommendations**: read one aloud. "And this is what makes it usable: not a score but an
  instruction, with its evidence. Replace this part, by this date, because of this prediction,
  this status and these alerts. The rules are plain configuration, not a black box."
- Scroll to **Driving and fuel**: "The same telemetry also gives us driver behaviour: every harsh
  brake and speeding episode on a timeline, a driver score, and fuel efficiency per day."

### 3. The fleet's drivers and fuel (1 min) — *Drivers & Fuel*

> "Across the fleet the difference between drivers is obvious. The simulator gives each truck a
> calm, normal or aggressive driver, and the score recovers exactly that: high nineties, mid
> eighties, mid forties. And it matters for money: the aggressive drivers get clearly fewer
> kilometres per litre, and idling alone burned this many litres."

Point at the ranking, the efficiency chart, then **Utilisation**: "and which vehicles are under- or
over-used."

### 4. Maintenance planning, with roles (1.5 min) — *Maintenance Planner, then Window B*

In Window A: "Every recommendation in the fleet, most urgent first."

Switch to **Window B**: "This is a technician's login. Fewer pages: only what is needed to work on
vehicles." Point at the shorter menu.

- Click **Complete** on the urgent recommendation for the vehicle from step 2.

> "The technician has done the job. That one click records the maintenance, resets the part's wear
> in the twin, and tells the vehicle."

- Go back to **Window A**, open that vehicle: the tile is green, the remaining-life bar is long again
  (it refreshes within a minute; the status changes at once).

### 5. A live fault (1.5 min) — *Window A on Fleet Overview, terminal visible*

> "So far this was history. Now something goes wrong while we watch."

Pick a healthy (green) vehicle, say vehicle 4, and run in the terminal:

```bash
scripts/demo-fault.sh 4 overheating
```

Within ten seconds the **Open alerts** card and the Alerts badge in the menu count up, and the
vehicle's arrow changes colour. Click the vehicle:

- The **Engine** tile turns amber after about 8 seconds (107 °C), then red at about 18 seconds
  (115 °C) as the temperature climbs.
- The engine temperature chart shows the spike, with a marker where the alert was raised.
- After the next scoring cycle (up to 10 seconds) a purple **ML anomaly** line names the readings the
  model found abnormal, and the health score drops.

> "Two independent detectors caught it: the threshold rule, and the anomaly model, which was never
> told what overheating is; it learned what normal looks like."

Open **Alerts**: the new alerts are at the top, marked RULE and ML. Click **Acknowledge** on one.

### 6. Route planning that knows about health (1.5 min) — *Route Planner*

> "Tomorrow's deliveries. I click the stops on the map…"

- Zoom into Bengaluru (scroll on the `KA01AB1234` marker until streets are visible). Click
  **Set a depot**, click the middle of the map, then click 8 to 10 stops around it.
- Click **Optimise routes**.

> "It has assigned the stops and ordered them, using real road distances, with the time and fuel
> for each route. But look at who is missing."

Point at **Vehicles left out**: "These trucks were not considered, and it says why: an urgent
recommendation, a critical alert that nobody has acknowledged, a part predicted to fail within
three days. The vehicle we just overheated is on this list. A planner that does not know about
vehicle health would have sent it."

The truck whose battery was replaced in step 4 is also still on the list, with "1 open critical
alert": the part is new, but nobody has acknowledged the alert yet. If asked, that is deliberate:
a vehicle goes back on the road when a person has looked, not when a number changes.

### 7. Reports and control (1 min) — *Reports, then User Management*

- **Reports**: choose *Maintenance report*, PDF, **Generate**. Open the downloaded file: completed
  work with costs, what is coming up, what is overdue. "The same for fleet health, drivers and
  fuel, as PDF or Excel."
- **User Management**: "Four roles. And an audit log: here is the technician completing that
  recommendation two minutes ago, and me acknowledging the alert."

### 8. Close (30 s)

> "So: a live twin of every vehicle; two layers of fault detection; predictions of remaining life
> with honest uncertainty; recommendations a person can act on; and planning that uses all of it.
> It is tested end to end, secured with role-based logins, and deploys to a single server with
> one command."

## If something goes wrong

| Symptom | What to do |
|---|---|
| "Reconnecting…" instead of "Live" | The backend restarted; it reconnects by itself within seconds. Carry on. |
| No remaining-life bars | The ML service is not running. Start it; bars appear within a minute. |
| The fault does nothing | The simulator is not running (`pgrep -f simulator.py`). Start it: `cd simulator && .venv/bin/python simulator.py --fault-rate 0`. |
| Route Planner says no vehicle is fit | Acknowledge the critical alerts of one or two healthy vehicles on the Alerts page and optimise again. It is also a good moment to make the point about health-aware planning. |
| Distances are "straight-line estimate" | A stop is outside the map region (Karnataka) or OSRM is still starting. The routes are still valid; say so and move on. |
| Anything else | `scripts/demo-reset.sh --yes` restores the starting state in about a minute. |

## Questions that usually come up

- **Is the data real?** No: a simulator with wear models and driver profiles. Wear is sped up so
  that 90 days contain several failures per part. The pipeline is what is real.
- **How accurate are the predictions?** On held-out vehicles the remaining-life error is two to
  three days, and 83–95% of predictions are within a week. Numbers and caveats are in
  `docs/model_report.md`.
- **What happens if the ML service is down?** Rules, alerts, driver scores, fuel analysis and
  recommendations carry on; predictions pause and resume. This is tested (`docs/performance.md`).
- **How many vehicles can it handle?** See `docs/performance.md`.
