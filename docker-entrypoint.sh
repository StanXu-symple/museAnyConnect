#!/bin/sh
set -e

DATA_DIR="${MUSE_DATA_DIR:-/data}"
SQLITE_DIR="${MUSE_SQLITE_DIR:-${DATA_DIR}/sqlite}"
LOG_DIR="${MUSE_LOG_DIR:-${DATA_DIR}/logs}"

# 若以 root 启动，先修正挂载数据卷权限再降权为非 root 用户 muse (UID 10001) 重新执行本脚本
if [ "$(id -u)" = "0" ]; then
    mkdir -p "$DATA_DIR" "$SQLITE_DIR" "$LOG_DIR"
    chown -R 10001:10001 "$DATA_DIR" "$SQLITE_DIR" "$LOG_DIR"
    export HOME=/app
    exec setpriv --reuid=10001 --regid=10001 --clear-groups /app/docker-entrypoint.sh "$@"
fi

mkdir -p "$SQLITE_DIR" "$LOG_DIR"

# 同步主服务与内置侧车的共享鉴权令牌，避免单边配置导致鉴权不一致
if [ -z "${TG_BRIDGE_TOKEN:-}" ] && [ -n "${MUSE_TELEGRAM_BRIDGE_TOKEN:-}" ]; then
    export TG_BRIDGE_TOKEN="$MUSE_TELEGRAM_BRIDGE_TOKEN"
elif [ -z "${MUSE_TELEGRAM_BRIDGE_TOKEN:-}" ] && [ -n "${TG_BRIDGE_TOKEN:-}" ]; then
    export MUSE_TELEGRAM_BRIDGE_TOKEN="$TG_BRIDGE_TOKEN"
fi

EMBEDDED_BRIDGE="${MUSE_EMBEDDED_TG_BRIDGE:-true}"
BRIDGE_URL="${MUSE_TELEGRAM_BRIDGE_URL:-http://127.0.0.1:8081}"
BRIDGE_PID=""
JAVA_PID=""

# 判断是否指向本容器回环地址；若用户显式配置了外部侧车地址则跳过内置拉起
should_start_embedded_bridge() {
    [ "$EMBEDDED_BRIDGE" = "true" ] || return 1
    [ -x /app/tg-user-bridge ] || return 1
    case "$BRIDGE_URL" in
        http://127.0.0.1:*|http://localhost:*|https://127.0.0.1:*|https://localhost:*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

cleanup() {
    if [ -n "$JAVA_PID" ]; then
        kill -TERM "$JAVA_PID" 2>/dev/null || true
    fi
    if [ -n "$BRIDGE_PID" ]; then
        kill -TERM "$BRIDGE_PID" 2>/dev/null || true
    fi
}

trap cleanup TERM INT

if should_start_embedded_bridge; then
    export TG_BRIDGE_HOST="${TG_BRIDGE_HOST:-127.0.0.1}"
    export TG_BRIDGE_PORT="${TG_BRIDGE_PORT:-8081}"
    /app/tg-user-bridge &
    BRIDGE_PID=$!
fi

# 后台拉起 Java 主服务并由脚本转发退出信号，确保容器优雅停机
# shellcheck disable=SC2086
java $JAVA_OPTS -jar /app/app.jar &
JAVA_PID=$!

set +e
wait "$JAVA_PID"
EXIT_CODE=$?
set -e

if [ -n "$BRIDGE_PID" ]; then
    kill -TERM "$BRIDGE_PID" 2>/dev/null || true
    wait "$BRIDGE_PID" 2>/dev/null || true
fi

exit "$EXIT_CODE"
