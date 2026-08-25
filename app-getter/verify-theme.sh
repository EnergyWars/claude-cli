#!/bin/bash

set -e

APP_NAME="appgetter"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLOR_FILE="app/src/main/java/com/wafflehq/${APP_NAME}/ui/theme/Color.kt"
THEME_FILE="app/src/main/java/com/wafflehq/${APP_NAME}/ui/theme/Theme.kt"
HASH_FILE="theme-hashes.sha256"

echo "=== Theme Verification Script ==="
echo ""

# Check 1: Isolation — Color tokens must only be used in Theme.kt
echo "Check 1: Verifying Color token isolation..."
VIOLATIONS=$(grep -r \
  --include="*.kt" \
  --exclude-dir=".git" \
  -E "\b((Sapphire|Aquamarine|Amethyst|Emerald|Citrine|Garnet|Graphite)[1-9]0|(Dark|Light)(Background|Surface|SurfaceVariant|Surface3)|OnSurface(Dark|Light|VariantDark|VariantLight)|Outline(Dark|Light))\b" \
  "app/src/main/java/com/wafflehq/${APP_NAME}/ui" \
  | grep -v "$COLOR_FILE" \
  | grep -v "$THEME_FILE" \
  | grep -v "R\.string" \
  | grep -v "\.gradle" \
  || true)

if [ -n "$VIOLATIONS" ]; then
  echo "❌ FAILED: Color tokens found outside Theme.kt:"
  echo "$VIOLATIONS"
  exit 1
fi
echo "✓ Passed: Color tokens only in Theme.kt"
echo ""

# Check 2: No hardcoded Hex colors
echo "Check 2: Verifying no hardcoded Hex colors..."
HEX_VIOLATIONS=$(grep -r \
  --include="*.kt" \
  --exclude-dir=".git" \
  -E "Color\(0x[A-Fa-f0-9]{8}\)" \
  "app/src/main/java/com/wafflehq/${APP_NAME}/ui" \
  | grep -v "$COLOR_FILE" \
  | grep -v "$THEME_FILE" \
  | grep -v "Type.kt" \
  | grep -v "\.gradle" \
  || true)

if [ -n "$HEX_VIOLATIONS" ]; then
  echo "❌ FAILED: Hardcoded Hex colors found outside Color.kt/Theme.kt:"
  echo "$HEX_VIOLATIONS"
  exit 1
fi
echo "✓ Passed: No hardcoded Hex colors in UI code"
echo ""

# Check 3: Hash integrity
echo "Check 3: Verifying file integrity..."
if [ ! -f "$HASH_FILE" ]; then
  echo "❌ FAILED: $HASH_FILE not found. Run: sha256sum $COLOR_FILE $THEME_FILE > $HASH_FILE"
  exit 1
fi

cd "$PROJECT_DIR"
if sha256sum -c "$HASH_FILE" >/dev/null 2>&1; then
  echo "✓ Passed: SHA-256 hashes verified"
else
  echo "❌ FAILED: SHA-256 hash mismatch"
  echo "Current hashes:"
  sha256sum "$COLOR_FILE" "$THEME_FILE"
  echo ""
  echo "Expected hashes:"
  cat "$HASH_FILE"
  exit 1
fi
echo ""

echo "=== All checks passed ✓ ==="
