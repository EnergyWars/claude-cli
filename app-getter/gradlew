#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if [[ -x "$here/gradlew_" ]]; then
  runner="$here/gradlew_"
elif [[ -x ./gradlew_ ]]; then
  runner=./gradlew_
else
  runner=$(command -v gradle) || { echo "gradlew: neither gradlew_ nor gradle found" >&2; exit 127; }
fi

exec systemd-run --user --scope --quiet --slice=gradle.slice -p OOMPolicy=continue "$runner" "$@"
