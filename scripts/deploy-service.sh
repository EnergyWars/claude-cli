#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TSX="$ROOT_DIR/node_modules/.bin/tsx"
CL_BIN="$HOME/.local/bin/cl"
UNIT_NAME="cl-server.service"
UNIT_PATH="/etc/systemd/system/$UNIT_NAME"
RENDERED="$ROOT_DIR/dist/$UNIT_NAME"

echo "==> Build + Bundle ($CL_BIN)"
npm --prefix "$ROOT_DIR" run build
npm --prefix "$ROOT_DIR" run deploy

if [ ! -x "$CL_BIN" ]; then
  echo "Fehler: $CL_BIN fehlt nach dem Build." >&2
  exit 1
fi

supports_sqlite() {
  [ -x "$1" ] && "$1" -e 'require("node:sqlite")' >/dev/null 2>&1
}

NODE_BIN="${CL_SERVICE_NODE:-$(command -v node || true)}"
if ! supports_sqlite "$NODE_BIN"; then
  NODE_BIN=""
  while IFS= read -r candidate; do
    if supports_sqlite "$candidate"; then
      NODE_BIN="$candidate"
      break
    fi
  done < <(find "$HOME/.nvm/versions/node" -mindepth 3 -maxdepth 3 -path '*/bin/node' 2>/dev/null | sort -Vr)
fi

if ! supports_sqlite "$NODE_BIN"; then
  echo "Fehler: Kein Node mit 'node:sqlite' gefunden (noetig: >= 22.5, empfohlen 24)." >&2
  echo "        Installieren (z. B. 'nvm install 24') oder CL_SERVICE_NODE=<pfad/zu/node> setzen." >&2
  exit 1
fi

CL_SERVICE_NODE_DIR="$(cd "$(dirname "$NODE_BIN")" && pwd)"
export CL_SERVICE_NODE_DIR
echo "Node fuer den Service: $CL_SERVICE_NODE_DIR/node ($("$NODE_BIN" --version))"

mkdir -p "$ROOT_DIR/dist"
"$TSX" "$ROOT_DIR/scripts/render-service-unit.ts" > "$RENDERED"

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

if systemctl is-active --quiet "$UNIT_NAME"; then
  echo "==> Stoppe laufenden Service"
  $SUDO systemctl stop "$UNIT_NAME"
fi

$SUDO install -m 644 -o root -g root "$RENDERED" "$UNIT_PATH"
$SUDO systemctl daemon-reload
$SUDO systemctl enable "$UNIT_NAME"

echo "==> Starte Service mit $CL_BIN"
$SUDO systemctl start "$UNIT_NAME"

if ! systemctl is-active --quiet "$UNIT_NAME"; then
  echo "Fehler: $UNIT_NAME laeuft nicht. Details: journalctl -u ${UNIT_NAME%.service} -n 50" >&2
  $SUDO systemctl --no-pager --lines=20 status "$UNIT_NAME" || true
  exit 1
fi

echo "Installiert: $UNIT_PATH"
$SUDO systemctl --no-pager --lines=0 status "$UNIT_NAME"
