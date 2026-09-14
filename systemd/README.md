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
- `config.json` (`paths[].path`, `contentPath`) und die `.env`-Datei (`CL_DATABASE_DIR`, siehe
  "Umgebungskonfiguration (.env)" in `FEATURES.md`) muessen zu den Verzeichnissen der Maschine
  passen – falsche Pfade lassen den Service in einer Restart-Schleife laufen
  (`EACCES: permission denied, mkdir ...` im Journal). Ohne lokale `.env` neben dem deployten
  `cl`-Binary greift die beim letzten `npm run build`/`deploy-service` eingebettete `.env`.

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
- `MemoryHigh=8G` / `MemoryMax=10G` – deckelt den eigenen Cgroup (Server + alle darin gespawnten
  `claude`- und `gradlew`-Prozesse, siehe unten). Verhindert, dass ein einzelner haengender Build
  oder eine haengende Session den gesamten Arbeitsspeicher der Maschine aufbraucht und dadurch die
  komplette Desktop-Session (nicht nur `cl-server`) vom Kernel-OOM-Killer weggeraeumt wird.
- `LimitNOFILE=65536` – genug Filedeskriptoren fuer viele parallele SSE-Verbindungen.

### Speicher-Vorfall 2026-09-14 und Watchdog

In der Nacht auf den 14.09.2026 haben ueber `cl server` ausgeloeste Android-Builds (`install-debug`
fuer mehrere Projekte) Gradle-Daemons erzeugt, die als Kindprozesse im selben Cgroup wie
`cl-server.service` verblieben sind und mangels Idle-Timeout (Gradle-Default: 3 Stunden) stundenlang
mehrere GB Speicher pro Daemon belegt haben. Zusammen mit mehreren parallel laufenden `claude`-Sessions
hat das RAM + Swap komplett gefuellt; der Kernel-OOM-Killer hat daraufhin ueber Stunden Prozesse
weggeraeumt und schliesslich um 07:19 Uhr die komplette `user-1000.slice` (GNOME-Session, alle
Terminals/IDE/`claude`-Prozesse) getoetet. `cl-server.service` selbst ist dank `OOMScoreAdjust=-500`
nicht gestorben, hat den Ausfall der Desktop-Session aber nicht verhindert.

Gegenmassnahmen:

1. **Ursache behoben**: `org.gradle.daemon.idletimeout=900000` (15 statt 180 Minuten) in
   `~/.gradle/gradle.properties` – ueber `cl server` angestossene Builds geben ihren Speicher jetzt
   zeitnah wieder frei, statt stundenlang zu idlen.
2. **Blastradius begrenzt**: `MemoryHigh`/`MemoryMax` oben – selbst wenn wieder ein Build/eine Session
   ausufert, trifft es nur noch den Cgroup von `cl-server.service` (der Dienst startet dank
   `Restart=always` sofort neu), nicht mehr die gesamte Maschine.
3. **Sicherheitsnetz**: `cl-server-watchdog.service`/`.timer` prueft alle 30 Minuten `GET /health`
   (3 Versuche im Abstand von 5s, gegen einzelne kurzzeitige Aussetzer). Antwortet keiner der
   Versuche, prueft das Skript zusaetzlich `systemctl is-active cl-server.service`: ist der Dienst
   laut systemd nicht `active` (abgestuerzt/gestoppt), macht der Watchdog nichts – dann kuemmert sich
   bereits `Restart=always` selbst. Meldet systemd dagegen `active`, obwohl `/health` nicht antwortet,
   haengt/deadlockt der Prozess trotz laufendem PID – erst dann startet der Watchdog per
   `sudo systemctl restart` manuell neu.

Installation des Watchdogs (einmalig, braucht Root – kann nicht aus einer unprivilegierten Shell
heraus automatisiert werden):

    make deploy-watchdog

Das macht (`scripts/deploy-watchdog.sh`, `npm run deploy-watchdog`):

1. `sudo install` von `systemd/cl-server-watchdog.service`/`.timer` nach `/etc/systemd/system/`,
2. `systemctl daemon-reload`, `systemctl enable --now cl-server-watchdog.timer`,
3. Pruefung per `systemctl is-active`, dass der Timer wirklich laeuft (sonst Abbruch mit Exit-Code 1),
4. nicht-destruktive Pruefung per `sudo -n -l systemctl restart cl-server.service`, ob der Watchdog
   `cl-server.service` spaeter ohne Passwortabfrage neu starten darf (nur eine Berechtigungspruefung,
   kein tatsaechlicher Neustart) – fehlt die Berechtigung, gibt das Skript eine Warnung aus, bricht
   aber nicht ab (der Timer selbst laeuft dann trotzdem, wuerde im Ernstfall aber leer laufen).

Voraussetzung fuer Punkt 4: `sudo -n systemctl restart cl-server.service` muss ohne Passwortabfrage
funktionieren (dieselbe Rechte-Voraussetzung, die `scripts/deploy-service.sh` bzw. der Self-Update-Endpoint
`POST /paths/claude-cli/commands/update` bereits benoetigen). Fehlt sie, zuerst eine `NOPASSWD`-Sudoers-Regel
ergaenzen (z. B. `sudo visudo -f /etc/sudoers.d/cl-server`) und `make deploy-watchdog` erneut ausfuehren.

Status/Logs des Watchdogs:

    systemctl list-timers cl-server-watchdog.timer
    journalctl -u cl-server-watchdog.service -n 50

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
