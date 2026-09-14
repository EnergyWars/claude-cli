#!/usr/bin/env bash
set -euo pipefail

URL="http://localhost:${PORT:-7765}/health"
UNIT_NAME="cl-server.service"
RETRIES=3
RETRY_DELAY_SECONDS=5

check_health() {
  curl -fsS --max-time 5 "$URL" >/dev/null 2>&1
}

for ((attempt = 1; attempt <= RETRIES; attempt++)); do
  if check_health; then
    exit 0
  fi
  if ((attempt < RETRIES)); then
    sleep "$RETRY_DELAY_SECONDS"
  fi
done

UNIT_STATE="$(systemctl is-active "$UNIT_NAME" 2>/dev/null || true)"

if [[ "$UNIT_STATE" != "active" ]]; then
  echo "$(date --iso-8601=seconds): $URL nach $RETRIES Versuchen nicht erreichbar, $UNIT_NAME ist laut systemctl '$UNIT_STATE' (nicht 'active') - systemd (Restart=always) kuemmert sich bereits selbst, kein manueller Neustart" >&2
  exit 1
fi

echo "$(date --iso-8601=seconds): $URL nach $RETRIES Versuchen nicht erreichbar, $UNIT_NAME ist laut systemctl aktiv (haengt/deadlockt), starte neu" >&2
sudo -n systemctl restart "$UNIT_NAME"
sleep 3

if check_health; then
  echo "$(date --iso-8601=seconds): $UNIT_NAME laeuft wieder" >&2
  exit 0
fi

echo "$(date --iso-8601=seconds): $UNIT_NAME antwortet auch nach Neustart nicht" >&2
exit 1
