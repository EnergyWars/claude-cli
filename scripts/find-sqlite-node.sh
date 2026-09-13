# Ermittelt eine Node-Binary mit "node:sqlite"-Unterstuetzung (>= 22.5). Wird sowohl von
# deploy-service.sh (per "source") als auch vom deploy.sh-generierten "cl"-Wrapper (per "cat"
# hineinkopiert, da dieser auf Zielrechnern selbstaendig laufen muss) verwendet - so bekommt jeder
# "cl"-Aufruf ein passendes Node, unabhaengig davon, welche Node-Version gerade interaktiv aktiv ist
# (z. B. ein aelterer nvm-Default ohne "node:sqlite").
supports_sqlite() {
  [ -x "$1" ] && "$1" -e 'require("node:sqlite")' >/dev/null 2>&1
}

find_sqlite_node() {
  local candidate="${CL_SERVICE_NODE:-$(command -v node || true)}"
  if supports_sqlite "$candidate"; then
    echo "$candidate"
    return 0
  fi
  while IFS= read -r candidate; do
    if supports_sqlite "$candidate"; then
      echo "$candidate"
      return 0
    fi
  done < <(find "$HOME/.nvm/versions/node" -mindepth 3 -maxdepth 3 -path '*/bin/node' 2>/dev/null | sort -Vr)
  echo "Fehler: Kein Node mit 'node:sqlite' gefunden (noetig: >= 22.5, empfohlen 24)." >&2
  echo "        Installieren (z. B. 'nvm install 24') oder CL_SERVICE_NODE=<pfad/zu/node> setzen." >&2
  return 1
}
