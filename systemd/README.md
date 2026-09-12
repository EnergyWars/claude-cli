# systemd-Service: cl-server

`cl-server.service` startet `cl server` dauerhaft als **System-Service** (nicht als User-Service):
laeuft unabhaengig davon, ob der Nutzer eingeloggt ist, startet bei jedem Boot automatisch und wird
nach jedem Absturz sofort neu gestartet.

## Quelle der Unit-Datei

Die Unit-Datei wird **nicht** von Hand gepflegt, sondern aus `src/service-unit.ts`
(`renderServiceUnit()`) erzeugt – dadurch sind Nutzer, Pfade, Node-Verzeichnis und Port immer die
der Maschine, auf der deployed wird, und der Inhalt ist durch `src/service-unit.test.ts` abgedeckt.
`scripts/render-service-unit.ts` schreibt sie nach `dist/cl-server.service`,
`scripts/deploy-service.sh` installiert sie nach `/etc/systemd/system/cl-server.service`.

## Deployment

    make deploy-service

Das macht in dieser Reihenfolge:

1. Build + Bundle nach `~/.local/bin/cl` (identisch zu `make release`),
2. Ermittlung eines Node mit `node:sqlite` (siehe unten),
3. Rendern der Unit-Datei nach `dist/cl-server.service`,
4. Stoppen des laufenden Service (falls aktiv),
5. `sudo install` nach `/etc/systemd/system/`, `daemon-reload`, `enable`, `start` – der neue
   Prozess laeuft also garantiert mit dem gerade gebauten `~/.local/bin/cl`,
6. Pruefung, dass der Service laeuft (sonst Abbruch mit Exit-Code 1 und `systemctl status`),
7. Ausgabe von `systemctl status`.

`make release-service` ist ein Alias fuer `make deploy-service`.

## Voraussetzungen

- **Node >= 22.5, empfohlen 24** – `src/db.ts` nutzt `node:sqlite`. `scripts/deploy-service.sh`
  prueft das aktive `node` und faellt sonst automatisch auf die hoechste passende Version unter
  `~/.nvm/versions/node/*/bin/node` zurueck; mit `CL_SERVICE_NODE=/pfad/zu/node` laesst sich ein
  bestimmtes Binary erzwingen. Das gefundene Verzeichnis wird als erster Eintrag in `Environment=PATH=`
  der Unit eingetragen – systemd laedt kein `~/.bashrc`/`nvm`, ohne diesen Eintrag wuerde der Service
  das (zu alte) System-Node verwenden.
- `config.json` (`databaseDirectory`, `paths[].path`, `contentPath`) muss zu den Verzeichnissen der
  Maschine passen – falsche Pfade lassen den Service in einer Restart-Schleife laufen
  (`EACCES: permission denied, mkdir ...` im Journal).

## Port

Der Service startet mit `Environment=PORT=7765` (Default aus `DEFAULT_SERVICE_PORT` in
`src/service-unit.ts`). `cl server` liest `PORT` aus der Umgebung, `PORT=7765 cl server` verhaelt sich
also identisch. Anderer Port beim Deployen: `PORT=9000 make deploy-service`.
Prioritaet: `-p/--port` > `PORT` > `8787` (`src/server-port.ts`).

## Robustheit

- `Restart=always`, `RestartSec=1` (Backoff bis `RestartMaxDelaySec=15`) – Neustart nach jedem Ende,
  egal ob Absturz, `kill -9` oder Exit 0.
- `StartLimitIntervalSec=0` – kein Rate-Limit, systemd gibt niemals dauerhaft auf.
- `WantedBy=multi-user.target` + `After=network.target` (bewusst **nicht** `network-online.target`) –
  startet so frueh wie moeglich im Boot, ohne auf eine fertig konfigurierte Netzwerkverbindung zu warten.
- `OOMPolicy=continue` + `OOMScoreAdjust=-500` – der Kernel waehlt den Server als OOM-Opfer zuletzt;
  wird ein Kindprozess (z. B. `claude`) OOM-gekillt, stirbt der Service nicht mit.
- `LimitNOFILE=65536` – genug Filedeskriptoren fuer viele parallele SSE-Verbindungen.

## Verwaltung

    systemctl status cl-server
    sudo systemctl restart cl-server
    sudo systemctl stop cl-server
    journalctl -u cl-server -f

## Nach Code-Aenderungen

Der Service fuehrt das gebuendelte `~/.local/bin/cl` aus, nicht das Repo – nach Code-Aenderungen also
`make deploy-service`, das den Build selbst mitmacht.
`contexts/*.md` und `scheduler/*.md` werden dank `CL_ROOT_DIR` live aus dem Repo gelesen.
Aenderungen an `config.json` wirken nach dem allerersten Start nicht mehr automatisch, da danach die
Datenbank die aktive Config haelt (siehe `context.md`, "Config/Context-System") – dafuer `PUT /config`
nutzen.

## Neustart und laufende Commands

`systemctl restart`/`stop` beendet den `cl server`-Prozess und dessen `claude`-Subprozesse. Beim
naechsten Start erkennt der Server verwaiste "running"-Eintraege in `t_commands` anhand der
gespeicherten PID und setzt sie auf `stopped`.
