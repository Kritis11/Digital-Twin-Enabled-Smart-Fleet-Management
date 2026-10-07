#!/bin/sh
# Dumps the whole database to MinIO every BACKUP_INTERVAL_HOURS and deletes dumps older than
# BACKUP_RETENTION_DAYS. `backup.sh once` takes a single dump and exits.
set -eu -o pipefail
export PGPASSWORD="$POSTGRES_PASSWORD"
BUCKET="${BACKUP_BUCKET:-backups}"

mcli alias set store "http://${MINIO_HOST:-minio:9000}" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null
mcli mb --ignore-existing "store/$BUCKET" > /dev/null

dump() {
    name="fleettwin-$(date -u +%Y%m%dT%H%M%SZ).dump"
    # custom format: compressed, and pg_restore can restore it selectively
    if pg_dump -h "${POSTGRES_HOST:-timescaledb}" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc | mcli pipe "store/$BUCKET/$name" > /dev/null; then
        touch /tmp/last-backup-ok
        echo "$(date -u +%FT%TZ) backup ok: $BUCKET/$name"
        mcli rm --recursive --force --older-than "${BACKUP_RETENTION_DAYS:-14}d" "store/$BUCKET/" || true
    else
        # a failed dump leaves a truncated object behind; do not keep something that looks like a backup
        mcli rm --force "store/$BUCKET/$name" > /dev/null 2>&1 || true
        echo "$(date -u +%FT%TZ) BACKUP FAILED" >&2
        return 1
    fi
}

if [ "${1:-}" = "once" ]; then
    dump
    exit
fi
while :; do
    dump || true
    sleep $(( ${BACKUP_INTERVAL_HOURS:-24} * 3600 ))
done
