# Changelog

Bugs found and fixed during the final review (branch `final-review`, released as `v1.0`). No
product features were added; the additions are tests, measurement, tooling and documentation.

| Found by | Problem | Fix |
|---|---|---|
| End-to-end test (admin journey) | On User Management, error messages such as "Password must be at least 10 characters" or "That username is taken" never appeared: the list reload that follows a failed change cleared the message straight away. | The message is now cleared when the next action starts, not by the reload (`frontend/src/app/users/users.ts`). |
| End-to-end test (route planning, production stack) | When some vehicles were fit but no stop could be reached within the 24-hour route limit, the Route Planner showed only the note about OSRM ("OSRM not used: …") and no explanation of why there were no routes. | The page now distinguishes "no vehicle is fit" from "no stop is reachable" and says which (`frontend/src/app/route-planner/route-planner.ts`). |
| Failure test (Redis stopped under load) | With Redis unreachable, telemetry ingestion stalled and most readings sent during the outage were never stored: about 5,300 of 6,000 in one minute at 100 readings a second. The Redis client queued each twin update and waited up to a minute for the server, on the thread that stores telemetry. | Redis commands are rejected at once while it is unreachable and time out after 500 ms otherwise (`RedisConfig`, `REDIS_TIMEOUT`); the resulting warning is logged every 10 seconds with a count instead of once per reading. Covered by `ApiIT.telemetryIsStillStoredWhileRedisIsDown`. Details in [performance.md](performance.md). |
| Security review | The example `JWT_SECRET` in `.env.example` was long enough to pass the backend's check, so a deployment from the unedited example file signed login tokens with a publicly known key. | The backend refuses to start while `JWT_SECRET` or `ADMIN_PASSWORD` has its example value; the setup scripts generate every secret. |
| Security review | Login answered faster for unknown usernames than for wrong passwords; error responses could include internal exception messages; `/actuator/health` showed component details to anyone. | The password check runs either way; errors are problem JSON with only the reason written for the user; health details need a login. |
| Fresh-machine test | The README's reanalyse command had no login token (it has needed one since authentication was added), and the first-time setup left the example secrets in place. | README corrected; `setup.sh` / `setup.ps1` added. |
| Load test | Running the ML service with several workers, and scoring vehicles in parallel, each made scoring slower. | Both were tried during the review and withdrawn before release; the explanation is in [performance.md](performance.md). |

## Other changes in the review

- Tests: backend integration tests with Testcontainers, a Playwright end-to-end suite, coverage reports, all in CI.
- Performance: telemetry inserted without a preceding SELECT, vehicle identity cached, the vehicle list read with one Redis call, live updates batched in the browser.
- Security: Content-Security-Policy and Permissions-Policy headers; Jackson, Netty, the PostgreSQL driver, the MinIO client and Bouncy Castle upgraded; OS packages upgraded in the images.
- Tooling: Spotless, ruff, black, ESLint and Prettier, checked in CI; one-command setup; demo reset and live-fault scripts (the simulator accepts a fault request on `fleet/{id}/fault`).
- Logging: one line per failure in one style, including scheduled jobs.
- Documentation: performance, security, configuration reference, project report, demo script, presentation outline.
