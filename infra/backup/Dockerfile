# The database image itself, so pg_dump always matches the server version, plus the MinIO client (mcli).
# It is large, but the server already has every one of its layers for the database, so this adds a few MB.
FROM timescale/timescaledb:2.30.2-pg16
RUN apk add --no-cache minio-client
COPY backup.sh restore.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore.sh
USER postgres
ENV MC_CONFIG_DIR=/tmp/.mc
# Healthy while the last successful dump is younger than two backup intervals.
HEALTHCHECK --interval=5m --timeout=10s --start-period=2m --retries=1 \
  CMD [ -n "$(find /tmp/last-backup-ok -mmin -$(( ${BACKUP_INTERVAL_HOURS:-24} * 120 )) 2>/dev/null)" ] || exit 1
ENTRYPOINT ["backup.sh"]
