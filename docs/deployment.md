# Deployment

How to run the whole of Fleet Twin on one Linux server with `docker-compose.prod.yml`: nginx in
front with HTTPS, everything else on an internal Docker network.

```
internet ──80/443──▶ nginx ──▶ backend ──▶ timescaledb, redis, mosquitto, minio, ml-service ──▶ osrm
                       │                         ▲
                    certbot                    backup (pg_dump ──▶ minio)
```

Only nginx publishes ports. The database, Redis, MinIO, MQTT, the ML service, OSRM and the
backend's actuator port cannot be reached from outside the server.

## 1. What you need

- A Linux server (Ubuntu 22.04 or later is what this was written against) with at least
  2 CPU cores, 8 GB of memory and 30 GB of disk. Preparing the default map region is the hungriest
  step (about 4 GB of memory for a minute or two); day-to-day use needs much less.
- Docker Engine with the Compose plugin (v2.20 or later): <https://docs.docker.com/engine/install/>.
- A domain name you control.
- Ports 80 and 443 open in the server's firewall (and in the cloud provider's, if any). Port 80
  must stay open: Let's Encrypt uses it to issue and renew the certificate.

## 2. Domain and DNS

Create an `A` record (and an `AAAA` record if the server has IPv6) for the host name you want,
for example `fleet.example.com`, pointing at the server's public address. Check it before going on:

```bash
dig +short fleet.example.com      # must print the server's address
```

Let's Encrypt cannot issue a certificate until this resolves from the public internet.

## 3. Get the code and configure it

```bash
git clone <repository url> fleet-twin && cd fleet-twin
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

Edit `.env.prod`:

| Setting | What to put |
|---|---|
| `DOMAIN` | The host name from step 2 |
| `LETSENCRYPT_EMAIL` | Your address, for expiry warnings. Empty means no certificate is requested |
| `JWT_SECRET` | At least 32 random characters: `openssl rand -base64 48` |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | The first dashboard login (password: 10+ characters) |
| `POSTGRES_PASSWORD`, `MQTT_PASSWORD`, `MINIO_ROOT_PASSWORD`, `REDIS_PASSWORD` | A different random value each: `openssl rand -base64 36` |
| `OSRM_PBF_URL` | An OpenStreetMap extract covering where the fleet drives |
| `BACKUP_INTERVAL_HOURS`, `BACKUP_RETENTION_DAYS` | Defaults: a dump every 24 hours, kept 14 days |

`.env.prod` is git-ignored and must never be committed. Keep a copy somewhere safe: without
`POSTGRES_PASSWORD` and `MINIO_ROOT_PASSWORD` the data volumes are of no use.

Every command below starts the same way, so define a shortcut for the session:

```bash
alias fleet='docker compose --env-file .env.prod -f docker-compose.prod.yml'
```

## 4. Start

```bash
fleet up -d --build
fleet ps            # every service should become "healthy" (osrm takes a few minutes the first time)
```

The first start builds the images (about ten minutes), creates the database schema and the admin
user, downloads and prepares the map, and takes a first backup.

## 5. HTTPS

Nothing to do if `DOMAIN` and `LETSENCRYPT_EMAIL` are set and DNS is right:

1. nginx starts with a self-signed certificate so that it can answer on port 80.
2. The `certbot` container asks Let's Encrypt for a certificate, proving control of the domain
   through `http://DOMAIN/.well-known/acme-challenge/`.
3. Within a minute nginx notices the new certificate and reloads.
4. certbot checks twice a day and renews when the certificate has under 30 days left; nginx picks
   the renewal up the same way.

```bash
fleet logs certbot                      # "Successfully received certificate"
curl -I https://fleet.example.com       # no certificate warning, HTTP 200
```

While experimenting, set `LETSENCRYPT_STAGING=1`: staging certificates are not trusted by
browsers but do not count against Let's Encrypt's limit of 5 failed attempts per hour. To move to
a real certificate afterwards, clear the setting and remove the staging one:
`fleet run --rm --entrypoint certbot certbot delete --cert-name $DOMAIN`, then `fleet up -d`.

HTTP is redirected to HTTPS, and browsers are told to use HTTPS only (HSTS).

## 6. First login

Open `https://DOMAIN` and sign in with `ADMIN_USERNAME` / `ADMIN_PASSWORD`. Under
**User Management** create a user for each person with the role they need
([user guide](user-guide.md)). `ADMIN_PASSWORD` in `.env.prod` is only read while there are no
users; changing the admin's password later is done in User Management.

## 7. Vehicles and models

**Real vehicles** publish JSON to MQTT topic `fleet/{vehicleId}/telemetry`. MQTT is not reachable
from outside by default. To accept devices over TLS on port 8883, add the overlay to every
command (and open 8883 in the firewall):

```bash
alias fleet='docker compose --env-file .env.prod -f docker-compose.prod.yml -f docker-compose.mqtt-tls.yml'
fleet up -d
```

Devices connect to `DOMAIN:8883` with TLS and `MQTT_USERNAME` / `MQTT_PASSWORD`. Mosquitto uses
the web certificate and reads it when it starts, so restart it after a renewal
(`fleet restart mosquitto`; a monthly cron job is enough).

**Demo data** instead of real vehicles:

```bash
fleet --profile demo run --rm simulator --fast-forward 90     # 90 days of history, takes seconds
fleet --profile demo up -d                                    # live simulated vehicles
# then, signed in as admin, rebuild trips and events from that history:
#   POST https://DOMAIN/api/admin/reanalyse
```

**ML models** are trained from the data in the database and stored in MinIO; a new server has none,
and until then there are no remaining-life predictions and health scores use component statuses only.

```bash
fleet exec ml-service python -m training.train_rul            # needs history with part failures
fleet exec ml-service python -m training.export_data          # anomaly model: needs 45+ minutes of telemetry
fleet exec ml-service python -m training.train
fleet restart ml-service                                      # loads the newest models
```

## 8. Backups

The `backup` container runs `pg_dump` (custom format) every `BACKUP_INTERVAL_HOURS` and stores the
dump in the MinIO bucket `backups` as `fleettwin-<UTC time>.dump`. Dumps older than
`BACKUP_RETENTION_DAYS` are deleted after each successful run. The container is healthy while the
last successful dump is younger than two intervals, so `fleet ps` shows a stuck backup.

```bash
fleet logs backup                       # one "backup ok: ..." line per run
fleet exec backup backup.sh once        # take a dump now, e.g. before an update
fleet exec backup restore.sh            # list the dumps
```

What is and is not covered:

- The dump holds everything in PostgreSQL: telemetry, alerts, trips, recommendations, maintenance
  records, users, the audit log and the list of reports.
- Report files and ML models are in MinIO, not in the dump. Models can be retrained; report files
  can be generated again.
- **The dumps are on the same server as the database.** That protects against a bad migration or
  a mistaken delete, not against losing the server. Copy the bucket elsewhere on a schedule, for
  example to S3 or another machine with the MinIO client: `mc mirror store/backups remote/fleet-backups`.

## 9. Restore

`restore.sh` restores a dump into a **new** database and leaves the live one alone, so a restore
can be checked before anything is replaced.

```bash
fleet exec backup restore.sh                                              # pick a dump
fleet exec backup restore.sh fleettwin-20261007T085241Z.dump fleettwin_restored
```

It creates the database, runs TimescaleDB's `timescaledb_pre_restore()`, restores, and runs
`timescaledb_post_restore()`. `pg_restore` prints warnings about TimescaleDB's own catalog tables;
those are expected. Check the result:

```bash
fleet exec timescaledb sh -c 'psql -U $POSTGRES_USER -d fleettwin_restored -c "select count(*), max(ts) from telemetry"'
```

To put the restored database in place of the live one, stop everything that uses the database,
swap the names, and start again:

```bash
fleet stop backend ml-service backup
fleet exec timescaledb sh -c 'psql -U $POSTGRES_USER -d postgres \
  -c "ALTER DATABASE $POSTGRES_DB RENAME TO ${POSTGRES_DB}_old" \
  -c "ALTER DATABASE ${POSTGRES_DB}_restored RENAME TO $POSTGRES_DB"'
fleet up -d
```

If PostgreSQL refuses the rename because the database is still in use, a client is still
connected: check `fleet ps` and stop it, then try again. The old database stays as
`fleettwin_old` until you drop it, which is your way back if the restored one turns out wrong.
Twins in Redis are rebuilt from the next telemetry.

On a brand-new server: do steps 1 to 4 with the same `.env.prod`, copy the dump into the new
MinIO bucket `backups`, and restore it the same way.

## 10. Updating to a new version

```bash
fleet exec backup backup.sh once        # a dump from just before the update
git pull
fleet up -d --build                     # rebuilds what changed and replaces those containers
fleet ps
```

Database migrations (Flyway) run when the new backend starts. Expect the dashboard to show
"Reconnecting…" for under a minute while the backend restarts. nginx stays up; telemetry that
vehicles publish during that minute may not be stored.

To go back: `git checkout <previous tag or commit>` and `fleet up -d --build`. If the newer version
had already migrated the database, restore the dump taken before the update (section 9); Flyway
does not undo migrations.

`docker image prune -f` removes the images left behind by old builds.

## 11. Health, logs and monitoring

```bash
fleet ps                                # health of every container
fleet logs -f backend                   # logs; each container keeps at most 5 files of 10 MB
fleet exec backend wget -qO- http://localhost:8081/actuator/health
```

Every container restarts automatically after a crash or a reboot (`restart: unless-stopped`).

The backend exposes Spring Actuator metrics in Prometheus format on its management port (8081),
which is only reachable inside the Docker network. An optional profile adds Prometheus and Grafana:

```bash
# set GRAFANA_ADMIN_PASSWORD in .env.prod first
fleet --profile monitoring up -d
```

Both listen on the server's loopback only. Reach them through an SSH tunnel from your machine:

```bash
ssh -L 3000:localhost:3000 -L 9090:localhost:9090 user@fleet.example.com
# Grafana: http://localhost:3000   Prometheus: http://localhost:9090
```

In Grafana add a Prometheus data source with URL `http://prometheus:9090`. Useful series:
`http_server_requests_seconds_count` (requests by URI and status), `jvm_memory_used_bytes`,
`hikaricp_connections_active`, `process_cpu_usage`. Dashboard 4701 ("JVM (Micrometer)") from
grafana.com works as is.

For a check from outside, point any uptime monitor at `https://DOMAIN/healthz` (nginx) and
`https://DOMAIN/api/vehicles` expecting 401 (nginx, the backend and its security are all up).

## 12. Security notes

- Logins are rate-limited by nginx to 10 a minute per client address (HTTP 429 beyond that).
- The ML service and OSRM have no authentication and rely on not being reachable from outside.
  Do not publish their ports.
- All devices share one MQTT username and password. Anyone holding them can publish telemetry for
  any vehicle.
- Rotating `JWT_SECRET` (change it, `fleet up -d backend`) signs everybody out.
- The nginx master process runs as root, as it must to bind ports 80 and 443 and read the
  certificate key; its worker processes and every other application container run unprivileged.

## 13. Troubleshooting

- **Browser warns about the certificate**: nginx is still on its self-signed certificate. See
  `fleet logs certbot`. Usual causes: DNS does not point at the server yet, port 80 is closed, or
  `LETSENCRYPT_EMAIL` is empty. After fixing, `fleet restart certbot`.
- **certbot: `too many failed authorizations`**: Let's Encrypt's hourly limit. Wait an hour and use
  `LETSENCRYPT_STAGING=1` until issuing works.
- **502 Bad Gateway**: nginx is up but the backend is not (yet). `fleet ps`, `fleet logs backend`.
- **Backend keeps restarting**: read the last lines of `fleet logs backend`. `JWT_SECRET` shorter
  than 32 characters and a changed `POSTGRES_PASSWORD` on an existing volume are the common ones.
- **`password authentication failed` after changing a password in `.env.prod`**: PostgreSQL only
  reads `POSTGRES_PASSWORD` when its volume is first created. Either put the old password back or
  change it inside the database with `ALTER USER`.
- **Dashboard loads but says "Reconnecting…" forever**: the WebSocket is blocked. `DOMAIN` must be
  exactly the host name in the browser's address bar, because the backend accepts WebSocket and
  API calls only from `https://DOMAIN`.
- **Login answers 429**: the rate limit; wait a minute.
- **osrm is `unhealthy` or routes are straight-line estimates**: `fleet logs osrm`. A failed
  download or too little memory during preparation are the usual causes; fix and `fleet up -d osrm`.
- **backup is `unhealthy`**: `fleet logs backup` shows the failing step (database or MinIO down,
  disk full).
- **Disk is filling up**: `docker system df`. Telemetry is the part that grows; old report files
  and dumps are in the MinIO volume.
- **Forgot the admin password**: another admin can reset it in User Management. With no admin
  left, set a new BCrypt hash in the `users` table by hand, or, on a system with nothing to lose,
  delete the users and restart the backend to recreate the admin from `.env.prod`.
