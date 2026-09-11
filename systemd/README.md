# systemd-Service: cl-server

`cl-server.service` startet `cl server` dauerhaft im Hintergrund - inkl. automatischem Neustart bei
Absturz (`Restart=always`) und automatischem Start beim Booten (`WantedBy=multi-user.target`). Gedacht
zur Installation auf einem dedizierten Ubuntu-26.04-Server. Diese Datei wird **nicht** automatisch
installiert oder gestartet - das ist bewusst ein manueller Schritt auf dem Zielserver.

## Voraussetzungen auf dem Zielserver

1. **Node.js >= 20 systemweit installiert**, nicht nur per `nvm` im Nutzerprofil - systemd-Services
   laden kein `.bashrc`/`.zshrc`, ein reiner `nvm`-Pfad im Nutzerprofil ist fuer den Service unsichtbar
   und `cl` (Shebang `#!/usr/bin/env node`) wuerde beim Start mit "node: command not found" scheitern.
   Empfohlen: Ubuntu-Paket (`apt install nodejs`) oder das NodeSource-Repo.
2. Dieses Repository unter `/home/simon/IdeaProjects/claude-cli` (Pfade im Unit-File anpassen, falls
   Nutzername/Verzeichnis auf dem Zielserver abweichen: `User=`, `Group=`, `WorkingDirectory=`,
   `Environment=CL_ROOT_DIR=...`, `Environment=PATH=...`, `ExecStart=...`).
3. Einmalig `npm ci && npm run release` ausgefuehrt (baut `dist/` und deployt das gebuendelte Binary
   nach `~/.local/bin/cl`, siehe `scripts/deploy.sh`).
4. `config.json` (inkl. `databaseDirectory`, `paths[].path`, `contentPath`) passt zu den tatsaechlichen
   Verzeichnissen auf diesem Server.
5. Falls Pfad-Commands/Hooks/`cl inst`/`cl instr` genutzt werden: die dafuer noetigen Tools (Java fuer
   Gradle, `adb` fuer Android-Installs, ...) muessen auf dem Server installiert und ueber den `PATH=`
   im Unit-File erreichbar sein - sonst schlagen nur diese einzelnen Commands fehl, der Server selbst
   laeuft trotzdem weiter.

## Installation (auf dem Zielserver, als root/sudo)

    sudo cp systemd/cl-server.service /etc/systemd/system/cl-server.service
    sudo systemctl daemon-reload
    sudo systemctl enable --now cl-server

## Verwaltung

    sudo systemctl status cl-server
    sudo systemctl restart cl-server     # z. B. nach "npm run release" fuer eine neue Version
    sudo systemctl stop cl-server
    sudo journalctl -u cl-server -f      # Live-Logs (stdout/stderr laufen ins journal)

## Nach Code-Aenderungen

Der Service startet ausschliesslich das bereits gebuendelte `~/.local/bin/cl` - nach jeder Code-Aenderung
muss neu deployed und der Service neu gestartet werden:

    npm run release
    sudo systemctl restart cl-server

`config.json`/`contexts/*.md`/`scheduler/*.md` werden dagegen dank `CL_ROOT_DIR` live aus dem Repo
gelesen (kein Rebuild fuer reine Prompt-/Scheduler-Textaenderungen noetig) - **mit einer Ausnahme**:
Aenderungen an `config.json` wirken sich nach dem allerersten Start **nicht** mehr automatisch aus, da
danach die Datenbank die alleinige Quelle der aktiven Config ist (siehe `context.md`, Abschnitt
"Config/Context-System"). Neue `config.json`-Werte muessen ueber `PUT /config` eingespielt werden,
sonst reicht ein einfacher `systemctl restart`.

## Neustart und laufende Commands

Ein `systemctl restart`/`stop` beendet den `cl server`-Prozess und damit i. d. R. auch dessen laufende
`claude`-Subprozesse. Seit der PID-Persistenz in `t_commands` (siehe `context.md`) erkennt der Server
beim naechsten Start automatisch alle durch den Neustart verwaisten "running"-Eintraege (Prozess laut
gespeicherter PID nicht mehr vorhanden) und setzt sie auf `stopped` - sie bleiben also nicht dauerhaft
faelschlich als laufend stehen.
