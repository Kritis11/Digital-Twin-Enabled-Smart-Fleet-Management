# Security review

A review of Fleet Twin before release 1.0: what was checked, what was found, what was fixed, and
what is knowingly left as it is. It covers the code on `main` at the `v1.0` tag.

## Summary

| Area | Result |
|---|---|
| Secrets in the code or the git history | None found |
| Default or example passwords | **Fixed**: the backend now refuses to start with the example `JWT_SECRET` or `ADMIN_PASSWORD` |
| Role checks on every endpoint | Complete; one table of rules, covered by tests |
| SQL injection | None: every value reaches the database as a bound parameter |
| Input validation | In place at every endpoint; errors no longer echo internal exception messages (**fixed**) |
| CORS | One allowed origin; no wildcard |
| Tokens | 15-minute access tokens, 7-day refresh tokens, revocation on logout and on any change to the user |
| Ports exposed in production | 80 and 443 only |
| Login rate limiting | In nginx: 10 attempts a minute per address |
| HTTP security headers | HSTS, CSP (**added**), X-Frame-Options, X-Content-Type-Options, Referrer-Policy, Permissions-Policy (**added**) |
| Dependency vulnerabilities | npm and pip: none. Java: 16 high or critical **fixed** by upgrades; 4 remain, none reachable in this application (section 3) |
| Container image vulnerabilities | High or critical findings in our five images: 208 before, 92 after. What remains is 4 Java findings and Debian base-image packages with no fix published (section 3) |

## 1. What was checked and how

### Secrets

- Searched the working tree and the whole git history (every commit on every branch) for
  passwords, tokens and keys, by pattern and with gitleaks (29 commits scanned, no leaks found).
- `.env`, `.env.prod` and every other `.env.*` except the two example files are git-ignored, and
  none has ever been committed.
- The example files contain only `change-me-…` placeholders. Passwords used by the integration
  and end-to-end tests are generated per run or belong to throwaway containers.

### Default passwords

The example environment files are public, so a deployment that keeps a placeholder is as good as
unprotected. Before this review the example `JWT_SECRET` was 42 characters long and passed the
backend's length check: a server started with an unedited `.env` would have signed tokens with a
secret anyone can read, letting anyone mint an admin token.

**Fixed.** The backend refuses to start if `JWT_SECRET` or `ADMIN_PASSWORD` still begins with
`change-me`, and the setup scripts (`setup.sh`, `setup.ps1`) generate random values for every
placeholder. The database, broker, Redis and MinIO passwords cannot be checked by the backend in
the same way; in production none of those services is reachable from outside (section 1,
"Exposed ports"), and `docs/deployment.md` tells the operator to replace every one.

### Role checks

Every route is matched by one ordered list of rules in `SecurityConfig`; anything not matched is
denied. `SecurityRulesTest` asserts the outcome for each role on each group of endpoints (401
anonymous; 403 or allowed per role), and the integration and end-to-end tests exercise the same
rules against the running application. The check for this review was to list every controller
mapping (34 operations) and confirm each falls under a rule with the intended roles. No gaps.

The WebSocket handshake is open, because browsers cannot attach a header to it, but the server
accepts nothing on a connection before a STOMP `CONNECT` frame with a valid access token; a
refresh token is refused there too (integration test `webSocketRefusesAStompConnectWithoutAValidToken`).

### SQL injection

All SQL was read. Queries are JPA repository methods, or `JdbcTemplate` and `psycopg` statements
with bound parameters. The four places that build SQL text from pieces (the telemetry history
query, the component grouping in the recommendation service, the report's upcoming/overdue
query, the simulator's table clean-up) concatenate constants defined in the same file, never
request input.

### Input validation

- Request bodies are validated with Bean Validation in the backend (`@Valid`, ranges on
  coordinates, required fields, a username pattern, minimum password length) and with Pydantic in
  the ML service.
- Query parameters that select a time range or a page size are parsed against a fixed pattern or
  clamped (`period`, `interval`, `limit`), and results are capped (`max-results`, `max-buckets`,
  `max-stops`).
- nginx limits request bodies to 1 MB.

**Fixed.** Error responses used to include the message of whatever exception was thrown, which
for an unexpected failure could reveal internals such as SQL text. Errors are now RFC 9457
problem JSON: the reasons written for users (`"That username is taken"`) are returned as
`detail`, and unexpected exceptions return a bare 500.

**Fixed.** `/actuator/health` showed component details (database, Redis, disk) to anonymous
callers; it now shows the status only.

### CORS

One origin is allowed: `http://localhost:4200` in development and `https://$DOMAIN` in production
(`CORS_ALLOWED_ORIGINS`). The same list restricts the WebSocket. No wildcard, no credentials mode
(tokens travel in a header, not a cookie, which also makes CSRF inapplicable).

### Tokens

- Access tokens: HS256, 15 minutes, carry the role. Refresh tokens: 7 days, carry the user's
  token version and cannot be used as access tokens (a `typ` claim is checked both ways).
- Logging out, disabling a user, changing a role or resetting a password raises the token
  version, which invalidates every refresh token of that user.
- Passwords are stored as BCrypt hashes and are never returned by the API.

**Fixed.** Login answered faster for a username that does not exist than for a wrong password,
because the password hash was only computed when the user existed; the difference could be used
to discover usernames. The hash comparison now runs in both cases.

### Exposed ports

`docker-compose.prod.yml` publishes ports for nginx only (80, 443). The optional monitoring
profile binds Prometheus and Grafana to the server's loopback address, and the optional MQTT
overlay publishes 8883 with TLS and a password. The database, Redis, MinIO, the plain MQTT
listener, the ML service, OSRM and the backend's API and actuator ports are reachable only inside
the Docker network. The actuator has its own port, which nginx does not proxy.

### Rate limiting and headers

Login attempts are limited by nginx to 10 a minute per client address, with a burst of 5, after
which it answers 429 (`LOGIN_RATE_PER_MINUTE`). nginx sends:

| Header | Value |
|---|---|
| `Strict-Transport-Security` | `max-age=31536000` |
| `Content-Security-Policy` | scripts and connections from the site's own origin only, no plugins, no framing; images also from the OpenStreetMap tile servers (**added**) |
| `X-Frame-Options` | `DENY` |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `same-origin` |
| `Permissions-Policy` | geolocation, camera, microphone and payment switched off (**added**) |

The nginx version is no longer advertised (`server_tokens off`).

## 2. Scans

| Scan | Tool | Scope | Result |
|---|---|---|---|
| Secrets | gitleaks | Whole git history | No leaks |
| JavaScript dependencies | `npm audit` | `frontend/` (all and production-only) and `e2e/` | 0 vulnerabilities |
| Python dependencies | `pip-audit` | `ml-service/requirements.txt` (40 packages), `simulator/requirements.txt` (5) | 0 vulnerabilities |
| Lock files | Trivy (filesystem) | `package-lock.json`, both `requirements.txt` | 0 vulnerabilities |
| Java dependencies and OS packages | Trivy (image) | The five images built from this repository | Table below |

OWASP dependency-check was not used for the Java dependencies: Trivy's scan of the built image
reads the exact jars that ship, from the same vulnerability data, without needing an NVD API key.

High and critical findings per image, before and after the fixes in this review:

| Image | Before (critical / high) | After (critical / high) | What changed |
|---|---|---|---|
| backend | 7 / 9 | 4 / 0 | Library upgrades (below); `apk upgrade` |
| nginx (dashboard) | 0 / 2 | 0 / 0 | `apk upgrade` |
| backup | 3 / 79 | 0 / 0 | `apk upgrade`; removed the database image's Go tools that a backup never runs |
| ml-service | 0 / 55 | 0 / 44 | `apt-get upgrade`; pip, setuptools and wheel removed from the image |
| simulator | 0 / 53 | 0 / 44 | The same |

Java libraries upgraded ahead of the Spring Boot parent (`backend/pom.xml`):

| Library | From | To | Fixes |
|---|---|---|---|
| jackson-core, jackson-databind | 2.21.4 | 2.21.7 | 5 high (denial of service in parsing) |
| netty | 4.1.135 | 4.1.137 | 1 critical (TLS SNI handling), 1 high |
| PostgreSQL JDBC driver | 42.7.11 | 42.7.12 | 1 high (SCRAM authentication) |
| MinIO client | 8.5.17 | 8.6.0 | 1 high (XML handling) |
| Bouncy Castle | 1.78.1 | 1.86 | 2 critical, 1 high |

The scans are part of CI: `npm audit` and `pip-audit` fail the build on a high or critical
finding, and the images are scanned with Trivy on `main`.

## 3. Open issues and why they are open

### Scanner findings that remain

| Finding | Severity | Why it is not fixed | Exposure here |
|---|---|---|---|
| Tomcat 10.1.55: CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 | Critical | Fixed in 10.1.58, which was not yet on Maven Central at the time of the review. Spring Boot 3.5.16 (the latest 3.5 release) ships 10.1.55 | Not reachable: the three concern Tomcat's own security constraints and its DIGEST and FORM authenticators. This application uses none of them; access control is Spring Security with bearer tokens. Upgrade with the next Spring Boot patch release |
| Spring MVC 6.2.19: CVE-2026-47884 (`XsltView`) | Critical | Fixed only in Spring Framework 7.0.9, which means Spring Boot 4: a major upgrade, not a patch | Not reachable: it needs an application that renders views through `XsltView`. This one renders no views at all; every endpoint returns JSON or a file |
| Debian packages in the Python images (util-linux, ncurses, systemd libraries, perl-base; 44 high per image) | High | No fixed package has been published for Debian 13; `apt-get upgrade` at build time already applies everything that has | Low: local utilities, not used by the service, in a container that runs as an unprivileged user and is not reachable from outside. Rebuilding picks the fixes up as Debian publishes them |
| The third-party images (TimescaleDB, Mosquitto, Redis, MinIO, OSRM, certbot) | Not scanned in depth | Not built here; pinned by version or digest | None is reachable from outside the Docker network. Review and bump the pins on a schedule |

### Design limitations

Listed most important first. None is rated high for the intended use (a single organisation's
internal tool on its own server); several would need doing before a multi-tenant or
internet-scale deployment.

| Issue | Risk | Why it is accepted for 1.0 | What would fix it |
|---|---|---|---|
| All vehicles share one MQTT username and password, and the broker has no per-topic ACL | Anyone holding the credential can publish telemetry for any vehicle, or read the whole fleet's | Devices are simulated; MQTT is not published by default, and only with TLS when it is | Per-device credentials or client certificates and a Mosquitto ACL tying each to `fleet/<its id>/#` |
| An access token cannot be revoked before it expires | A disabled user, or a stolen access token, keeps working for up to 15 minutes | Checking every request against the database would cost a query per request; 15 minutes is the usual trade-off | A short deny-list of revoked token ids in Redis |
| Refresh tokens are not rotated: an old one stays valid for its 7 days unless the user logs out or is changed | A stolen refresh token gives a week of access | Logout and any admin change to the user do revoke it | Single-use refresh tokens with reuse detection |
| Tokens are kept in the browser's `localStorage` | Script injected into the page (XSS) could read them | Angular escapes everything rendered, no `innerHTML` is used, and the CSP allows scripts from the site's own origin only | An `HttpOnly` cookie for the refresh token, with CSRF protection |
| No account lockout | Slow password guessing is possible within the rate limit: 10 attempts a minute per address | The rate limit, BCrypt, and a 10-character minimum make it impractical | Lockout or back-off per account after repeated failures |
| Login rate limiting exists only in nginx | The development setup (no nginx) has none | Development is local | The same limit in the backend |
| No password rules beyond length; no two-factor authentication; no self-service password change | Weak passwords can be set; a user must ask an admin to change theirs | A handful of internal users | Password strength checks, TOTP, a change-password page |
| The ML service and OSRM have no authentication | Anyone who can reach them can call them | Only the backend can reach them: they are not published in production | A shared secret or mutual TLS between services |
| The backend and ML service use MinIO's root credentials | A compromise of either gives full control of the object store, including backups | One store, three buckets, one trust zone | A MinIO service account per service, limited to its bucket |
| An Isolation Forest model, if selected at training time, is loaded with `joblib` (pickle) | Whoever can write to the models bucket can run code in the ML service | The served model is XGBoost, stored as JSON; the bucket is not reachable from outside | Drop the pickle path, or sign model files |
| Database backups are not encrypted and are kept on the same server | Disk access exposes all data; losing the server loses the backups | Stated in the deployment guide, with how to mirror them elsewhere | Encrypt dumps before upload; off-site copy |
| The audit log records changes made through the API, not logins or failed logins | Password guessing leaves no trace in the application | nginx's access log has every login attempt with its status | Log authentication events |
| The CSP allows inline styles (`style-src 'unsafe-inline'`) | Injected markup could restyle the page (it could not run script) | Angular's component styles and Leaflet's positioning need it | Nonce-based styles (`ngCspNonce`) |
| HSTS is sent without `includeSubDomains` or preload | Sub-domains are not covered | The application cannot know what else lives under the domain | Add them once that is known |
| Swagger UI and the OpenAPI description are served without a login by the backend | The API's shape is visible to anyone who can reach the backend port | nginx does not proxy them, so in production they are not reachable from outside | Disable springdoc in production |

## 4. Reproducing the scans

```bash
docker run --rm -v "$PWD":/repo zricethezav/gitleaks:latest git /repo --no-banner --redact
(cd frontend && npm audit) && (cd e2e && npm audit)
pip install pip-audit && pip-audit -r ml-service/requirements.txt && pip-audit -r simulator/requirements.txt
docker compose --env-file .env.prod -f docker-compose.prod.yml --profile demo build
for image in backend ml-service nginx simulator backup; do
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest image --severity HIGH,CRITICAL fleet-twin-prod-$image
done
```
