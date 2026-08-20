#!/bin/bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_SRC="$PROJECT_ROOT/app/src/main/java/com/wafflehq/base"

ERRORS=0
WARNINGS=0

echo "=== WaffleHQ Color System Validation ==="
echo ""

# 1. Check that Color.kt is only imported in Theme.kt
echo "1. Checking Color.kt imports..."
INVALID_IMPORTS=$(grep -r "import.*Color\.kt\|import.*from.*Color" "$APP_SRC" --include="*.kt" \
  | grep -v "Theme.kt" \
  | grep -v "import androidx.compose.ui.graphics.Color" \
  | grep -v "^Binary" || true)

if [ -z "$INVALID_IMPORTS" ]; then
  echo -e "${GREEN}✓${NC} Color.kt only imported in Theme.kt"
else
  echo -e "${RED}✗${NC} Color.kt imported outside Theme.kt:"
  echo "$INVALID_IMPORTS"
  ERRORS=$((ERRORS + 1))
fi

# 2. Check that color tokens (Sapphire, Emerald, etc.) are not imported outside Theme.kt
echo ""
echo "2. Checking direct color token imports..."
TOKEN_IMPORTS=$(grep -r "import.*\(Sapphire\|Emerald\|Amethyst\|Citrine\|Garnet\|DarkBackground\|LightBackground\|OnSurface\)" \
  "$APP_SRC" --include="*.kt" | grep -v "Theme.kt" || true)

if [ -z "$TOKEN_IMPORTS" ]; then
  echo -e "${GREEN}✓${NC} No direct color token imports outside Theme.kt"
else
  echo -e "${RED}✗${NC} Direct color token imports found outside Theme.kt:"
  echo "$TOKEN_IMPORTS" | cut -d: -f1 | sort -u
  ERRORS=$((ERRORS + 1))
fi

# 3. Check for hardcoded hex colors in non-theme files
echo ""
echo "3. Checking for hardcoded hex colors..."
HEX_COLORS=$(grep -r "Color(0x[0-9A-Fa-f]" "$APP_SRC" --include="*.kt" \
  | grep -v "Color.kt" \
  | grep -v "Theme.kt" || true)

if [ -z "$HEX_COLORS" ]; then
  echo -e "${GREEN}✓${NC} No hardcoded hex colors outside Color.kt/Theme.kt"
else
  echo -e "${RED}✗${NC} Hardcoded hex colors found:"
  echo "$HEX_COLORS"
  ERRORS=$((ERRORS + 1))
fi

# 4. Check that all required color tokens exist in Color.kt
echo ""
echo "4. Checking for required color tokens..."
COLOR_KT="$APP_SRC/ui/theme/Color.kt"

REQUIRED_HUES=("Sapphire" "Emerald" "Amethyst" "Citrine" "Garnet")
REQUIRED_TONES=("10" "20" "30" "40" "80" "90")

MISSING=0
for HUE in "${REQUIRED_HUES[@]}"; do
  for TONE in "${REQUIRED_TONES[@]}"; do
    if ! grep -q "val ${HUE}${TONE} =" "$COLOR_KT"; then
      echo -e "${RED}✗${NC} Missing: ${HUE}${TONE}"
      MISSING=$((MISSING + 1))
    fi
  done
done

if [ $MISSING -eq 0 ]; then
  echo -e "${GREEN}✓${NC} All required color hues and tones present"
else
  ERRORS=$((ERRORS + MISSING))
fi

# 5. Check for Period-tracker colors
echo ""
echo "5. Checking for Period-tracker colors..."
PERIOD_COLORS=("period-actual-light" "period-actual-dark" "period-predicted")
PERIOD_MISSING=0

for COLOR in "${PERIOD_COLORS[@]}"; do
  if ! grep -qi "val.*[Pp]eriod.*[Aa]ctual\|val.*[Pp]eriod.*[Pp]redicted" "$COLOR_KT" 2>/dev/null; then
    echo -e "${YELLOW}⚠${NC} Period-tracker color potentially missing: $COLOR"
    PERIOD_MISSING=$((PERIOD_MISSING + 1))
  fi
done

if [ $PERIOD_MISSING -gt 0 ]; then
  WARNINGS=$((WARNINGS + PERIOD_MISSING))
fi

# 6. Check that fonts are properly configured
echo ""
echo "6. Checking font configuration..."
TYPE_KT="$APP_SRC/ui/theme/Type.kt"

if grep -q "GeistSans" "$TYPE_KT"; then
  echo -e "${GREEN}✓${NC} Geist Sans font configured"
else
  echo -e "${RED}✗${NC} Geist Sans font not found"
  ERRORS=$((ERRORS + 1))
fi

if grep -q "GeistMono" "$TYPE_KT"; then
  echo -e "${GREEN}✓${NC} Geist Mono font configured"
else
  echo -e "${RED}✗${NC} Geist Mono font not found"
  ERRORS=$((ERRORS + 1))
fi

# 7. Check for invalid Color() usage
echo ""
echo "7. Checking for invalid Color() usage patterns..."
INVALID_COLOR_USAGE=$(grep -r "Color\.Red\|Color\.Green\|Color\.Blue\|Color\.Yellow\|Color\.Gray\|Color\.Black\|Color\.DarkGray\|Color\.LightGray" \
  "$APP_SRC" --include="*.kt" | grep -v "Color\.Transparent\|Color\.White" || true)

if [ -z "$INVALID_COLOR_USAGE" ]; then
  echo -e "${GREEN}✓${NC} No invalid Color.* usage (Color.Red, Color.Green, etc.)"
else
  echo -e "${RED}✗${NC} Invalid Color.* usage found:"
  echo "$INVALID_COLOR_USAGE"
  ERRORS=$((ERRORS + 1))
fi

# Summary
echo ""
echo "=== Summary ==="
if [ $ERRORS -eq 0 ] && [ $WARNINGS -eq 0 ]; then
  echo -e "${GREEN}✓ All validations passed!${NC}"
  exit 0
elif [ $ERRORS -eq 0 ]; then
  echo -e "${YELLOW}⚠ Passed with $WARNINGS warning(s)${NC}"
  exit 0
else
  echo -e "${RED}✗ Validation failed with $ERRORS error(s) and $WARNINGS warning(s)${NC}"
  exit 1
fi
