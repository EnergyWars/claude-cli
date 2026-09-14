#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_NAME="cl-server-watchdog.service"
TIMER_NAME="cl-server-watchdog.timer"
TARGET_UNIT="cl-server.service"

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

echo "==> Installiere $SERVICE_NAME und $TIMER_NAME"
$SUDO install -m 644 -o root -g root "$ROOT_DIR/systemd/$SERVICE_NAME" "/etc/systemd/system/$SERVICE_NAME"
$SUDO install -m 644 -o root -g root "$ROOT_DIR/systemd/$TIMER_NAME" "/etc/systemd/system/$TIMER_NAME"
$SUDO systemctl daemon-reload
$SUDO systemctl enable --now "$TIMER_NAME"

if ! systemctl is-active --quiet "$TIMER_NAME"; then
  echo "Fehler: $TIMER_NAME laeuft nicht. Details: journalctl -u ${TIMER_NAME%.timer} -n 50" >&2
  exit 1
fi

echo "==> Pruefe passwortlose sudo-Berechtigung fuer 'systemctl restart $TARGET_UNIT'"
if sudo -n -l systemctl restart "$TARGET_UNIT" >/dev/null 2>&1; then
  echo "OK - Watchdog kann $TARGET_UNIT im Ernstfall ohne Passwortabfrage neu starten."
else
  echo "Warnung: 'sudo -n systemctl restart $TARGET_UNIT' verlangt aktuell ein Passwort oder ist nicht erlaubt." >&2
  echo "  Der Watchdog kann den Server dann im Ernstfall NICHT automatisch neu starten." >&2
  echo "  Einrichten: siehe systemd/README.md, Abschnitt 'Installation des Watchdogs' (NOPASSWD-Sudoers-Regel, z. B. 'sudo visudo -f /etc/sudoers.d/cl-server')." >&2
fi

echo "Installiert und aktiv: $TIMER_NAME"
systemctl --no-pager --lines=0 status "$TIMER_NAME"
systemctl list-timers "$TIMER_NAME" --no-pager
