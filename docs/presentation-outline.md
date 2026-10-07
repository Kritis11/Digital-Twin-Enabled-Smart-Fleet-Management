# Presentation outline

Fourteen slides for a 12–15 minute talk, followed by the live demo
([demo-script.md](demo-script.md)) or with its screenshots if a live demo is not possible.
Each slide lists what to say and what to show. Screenshots are in `docs/screenshots/`; diagrams are
the Mermaid blocks in the named documents (render them on GitHub or at mermaid.live and export as PNG).

| # | Slide | Key points | Visual |
|---|---|---|---|
| 1 | **Title** | Digital-Twin-Enabled Smart Fleet Management. Name, programme, supervisor, date. | `screenshots/fleet-overview.png` as a faded background |
| 2 | **The problem** | Fleets maintain vehicles on fixed schedules or after breakdowns. Breakdowns are expensive and unplanned; schedules replace parts that are still good. Operators already have telemetry but mostly use it for location. | One sentence per point; no visual needed |
| 3 | **Objectives** | O1: a real-time digital twin of every vehicle. O2: predict failures and turn predictions into maintenance decisions. O3: optimise operation (routes, fuel, drivers) in a system an organisation can actually run. | The objectives table from `project-report.md` |
| 4 | **Approach in one picture** | Telemetry in over MQTT; a twin per vehicle; rules and two kinds of model on top; recommendations and route planning as the outputs; a role-based dashboard. | Architecture diagram from `README.md` |
| 5 | **The digital twin** | State, position, every sensor value, a status per component, health score. Updated within tens of milliseconds of each reading and pushed to the browser. | `screenshots/vehicle-detail.png` (top third) |
| 6 | **Data: the simulator** | No real fleet was available, so a simulator with physical wear curves, three driver profiles, injected faults, and a fast-forward mode that writes 90 days of history with failures and repairs. Honest about what that means for the results. | The wear and driver profile table from `project-report.md` |
| 7 | **Anomaly detection** | Rolling-window features over 8 signals; supervised XGBoost against an unsupervised Isolation Forest baseline; explanations from TreeSHAP. Precision 0.99, recall 0.97. | Results table and confusion matrix from `model_report.md` |
| 8 | **Remaining useful life** | Run-to-failure lifecycles; XGBoost quantile regression against linear extrapolation; leave-one-vehicle-out validation; conformalised intervals. Error of 2–3 days, 83–95% within a week. | RUL results table from `model_report.md`; `screenshots/vehicle-detail.png` (RUL bars) |
| 9 | **From prediction to action** | Recommendation rules combine RUL, status, alerts, anomaly history and service intervals. Each one states its evidence. Completing one closes the loop: maintenance recorded, wear reset. | `screenshots/maintenance-planner.png` |
| 10 | **Drivers and fuel** | Five event types, a transparent score formula, fuel efficiency and idling cost, fuel-theft detection. The score separates the three driver profiles cleanly. | `screenshots/drivers-fuel.png` |
| 11 | **Health-aware route optimisation** | Vehicle routing with OR-Tools on OSRM road distances. Unfit vehicles are excluded with reasons; efficient, well-driven ones preferred. | `screenshots/route-planner.png` |
| 12 | **A system, not a notebook** | Four roles with JWT logins and an audit log; PDF and Excel reports; one-command deployment behind HTTPS with backups; CI. | `screenshots/user-management.png`, deployment diagram from `deployment.md` |
| 13 | **Does it hold up?** | Test pyramid: unit, integration against real services, end-to-end in a browser; coverage above 90%. Load: what it sustains and where it slows down. Failure tests: what happens when the ML service, Redis or the broker goes away. Security review findings. | Summary tables from `performance.md` and `security.md` |
| 14 | **Limitations and next steps** | Simulated data; five vehicles; per-vehicle not per-driver scores; no capacities or traffic in routing. Next: a pilot on real telemetry, per-device credentials, capacities. | Bullet list |

## Backup slides

- **Driver score formula** (`driver_score.md`): for the question "how is the score calculated?"
- **Prediction intervals** (`model_report.md`, "Prediction intervals and confidence"): for "how sure is the model?"
- **Access matrix** (`user-guide.md`, "Roles at a glance"): for "who can do what?"
- **Data path** (`architecture.md`): for detailed architecture questions.

## Timing

| Part | Minutes |
|---|---|
| Slides 1–4: problem, objectives, approach | 3 |
| Slides 5–11: what was built and how well it works | 7 |
| Slides 12–14: engineering, evidence, limits | 3 |
| Live demo (shortened version of the demo script: steps 2, 5 and 6) | 5 |
| Questions | remaining time |
