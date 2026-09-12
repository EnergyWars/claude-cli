#!/bin/sh
set -eu

LOCK_DIR="$HOME/.gradlew-locks"
mkdir -p "$LOCK_DIR"

while :; do
    exec 3>"$LOCK_DIR/slot0.lock"
    if flock -n 3; then
        break
    fi
    exec 3>&-

    exec 4>"$LOCK_DIR/slot1.lock"
    if flock -n 4; then
        break
    fi
    exec 4>&-

    sleep 0.2
done

exec ./gradlew_ "$@"
