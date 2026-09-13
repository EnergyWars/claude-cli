#!/bin/bash
set -e

git add .

if git diff --cached --quiet; then
  echo "Keine Aenderungen - kein Commit noetig."
  exit 0
fi

git commit -m "Auto commit $(date '+%Y-%m-%d %H:%M:%S')"
git push
