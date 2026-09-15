#!/bin/sh
set -eu

exec systemd-run --user --scope --quiet \
    --slice=gradle-builds.slice \
    --unit="gradle-$(date +%s)-$$" \
    -- ./gradlew_ "$@"
