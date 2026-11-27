#!/bin/sh
set -e

DATA_DIR="${MUSE_DATA_DIR:-/data}"
SQLITE_DIR="${MUSE_SQLITE_DIR:-${DATA_DIR}/sqlite}"
LOG_DIR="${MUSE_LOG_DIR:-${DATA_DIR}/logs}"

if [ "$(id -u)" = "0" ]; then
    mkdir -p "$DATA_DIR" "$SQLITE_DIR" "$LOG_DIR"
    chown -R 10001:10001 "$DATA_DIR" "$SQLITE_DIR" "$LOG_DIR"
    export HOME=/app
    exec setpriv --reuid=10001 --regid=10001 --clear-groups sh -c "exec java $JAVA_OPTS -jar /app/app.jar"
fi

mkdir -p "$SQLITE_DIR" "$LOG_DIR"
exec sh -c "exec java $JAVA_OPTS -jar /app/app.jar"
