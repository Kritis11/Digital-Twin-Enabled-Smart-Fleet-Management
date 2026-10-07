#!/bin/sh
# usage: restore.sh [dump name] [target database]
# Restores a dump from MinIO into a NEW database (default: <POSTGRES_DB>_restored) and never touches
# the live one. With no arguments it lists the dumps. docs/deployment.md explains how to switch over.
set -eu
export PGPASSWORD="$POSTGRES_PASSWORD" PGHOST="${POSTGRES_HOST:-timescaledb}" PGUSER="$POSTGRES_USER"
BUCKET="${BACKUP_BUCKET:-backups}"
mcli alias set store "http://${MINIO_HOST:-minio:9000}" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null

if [ $# -eq 0 ]; then
    mcli ls "store/$BUCKET/"
    exit
fi
target="${2:-${POSTGRES_DB}_restored}"
psql -v ON_ERROR_STOP=1 -d postgres -c "CREATE DATABASE \"$target\""
# TimescaleDB needs to be told a restore is in progress, or its catalog and the restored tables disagree.
psql -v ON_ERROR_STOP=1 -d "$target" -c "CREATE EXTENSION IF NOT EXISTS timescaledb" -c "SELECT timescaledb_pre_restore()"
mcli cat "store/$BUCKET/$1" | pg_restore -d "$target" --no-owner
psql -v ON_ERROR_STOP=1 -d "$target" -c "SELECT timescaledb_post_restore()"
echo "Restored $1 into database $target"
