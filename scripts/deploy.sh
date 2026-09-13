#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENTRY="$ROOT_DIR/dist/index.js"
BUNDLE="$ROOT_DIR/dist/cl.bundle.mjs"
ESBUILD="$ROOT_DIR/node_modules/.bin/esbuild"
TARGET_DIR="$HOME/.local/bin"
TARGET_NAME="cl"

if [ ! -f "$ENTRY" ]; then
  echo "Fehler: $ENTRY nicht gefunden. Zuerst 'npm run build' ausfuehren." >&2
  exit 1
fi

# Deployed file must be self-contained (no node_modules alongside it in ~/.local/bin).
"$ESBUILD" "$ENTRY" --bundle --platform=node --format=esm \
  --banner:js="import { createRequire as __createRequire } from 'node:module'; const require = __createRequire(import.meta.url);" \
  --outfile="$BUNDLE"

mkdir -p "$TARGET_DIR"
cp "$BUNDLE" "$TARGET_DIR/$TARGET_NAME.mjs"

# "$TARGET_NAME" (ohne Extension, direkt im PATH aufrufbar) ist ein duenner Bash-Wrapper statt des
# Bundles selbst: ohne ".mjs"-Endung und ohne package.json mit "type":"module" daneben muss Node das
# ESM-Format des Bundles per Syntax-Heuristik erraten - auf Node 20 (Mindestversion laut "engines")
# schlaegt das fehl ("Cannot use import statement outside a module"). Mit fester ".mjs"-Endung ist der
# Modultyp eindeutig, unabhaengig von der Node-Version. Der Shebang bleibt erhalten, damit sowohl die
# interaktive Shell als auch systemd (ExecStart=... exec'd das File per Shebang, nicht ueber "node ...")
# denselben Wrapper transparent nutzen.
# find-sqlite-node.sh wird hineinkopiert statt per "source" eingebunden, weil das deployte File auf dem
# Zielrechner ohne dieses Repo lauffaehig sein muss (siehe Kommentar oben: self-contained).
{
  echo '#!/usr/bin/env bash'
  cat "$ROOT_DIR/scripts/find-sqlite-node.sh"
  echo "NODE_BIN=\$(find_sqlite_node) || exit 1"
  echo "exec \"\$NODE_BIN\" \"$TARGET_DIR/$TARGET_NAME.mjs\" \"\$@\""
} > "$TARGET_DIR/$TARGET_NAME"
chmod +x "$TARGET_DIR/$TARGET_NAME"

echo "Deployed: $TARGET_DIR/$TARGET_NAME"

case ":$PATH:" in
  *":$TARGET_DIR:"*) ;;
  *) echo "Hinweis: $TARGET_DIR ist nicht im PATH." >&2 ;;
esac
