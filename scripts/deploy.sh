#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENTRY="$ROOT_DIR/dist/index.js"
BUNDLE="$ROOT_DIR/dist/cl.bundle.js"
ESBUILD="$ROOT_DIR/node_modules/.bin/esbuild"
TARGET_DIR="$HOME/.local/bin"
TARGET_NAME="cl"

if [ ! -f "$ENTRY" ]; then
  echo "Fehler: $ENTRY nicht gefunden. Zuerst 'npm run build' ausfuehren." >&2
  exit 1
fi

# Deployed file must be self-contained (no node_modules alongside it in ~/.local/bin).
"$ESBUILD" "$ENTRY" --bundle --platform=node --format=esm --outfile="$BUNDLE"

mkdir -p "$TARGET_DIR"
cp "$BUNDLE" "$TARGET_DIR/$TARGET_NAME"
chmod +x "$TARGET_DIR/$TARGET_NAME"

echo "Deployed: $TARGET_DIR/$TARGET_NAME"

case ":$PATH:" in
  *":$TARGET_DIR:"*) ;;
  *) echo "Hinweis: $TARGET_DIR ist nicht im PATH." >&2 ;;
esac
