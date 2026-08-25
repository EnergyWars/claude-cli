# Features

## CLI-Grundgerüst

Einstiegspunkt (`src/index.ts`) mit `commander` als Argument-Parser. Bietet:

- `--help` (**ohne** `-h`-Kurzform, siehe "Headless-Modus") – zeigt Nutzung, Optionen, das `[agent]`-Argument, die Model-Subcommands sowie dynamisch aus `config.json` gelesene Listen aller Agents und Tasks (siehe "Dynamisches --help"). Auch `cl task --help`, `cl <model> --help` und `cl server --help` listen jeweils alle passenden Moeglichkeiten aus `config.json` auf.
- `-v, --version` – zeigt die aktuelle Version (aus `package.json`, zur Build-Zeit generiert).
- `[agent]` (optionales positionales Argument) – siehe "Agent-Start".
- `haiku|h`, `sonnet|s`, `opus|o`, `fable|f` (Subcommands, je mit optionalem `[agent]`) – siehe "Model-Override".
- `-h, --headless [prompt]` (auf Root-Command und allen Model-Subcommands) – siehe "Headless-Modus".
- `server` (Subcommand) – siehe "HTTP-Server (cl server)".

## Agent-Start (`cl` / `cl <name>`)

`cl` startet `claude` (Claude Code) voll interaktiv im Vordergrund (`stdio: 'inherit'`, keine Pipe/kein `--print`) – als würde man `claude` direkt aufrufen, nur vorkonfiguriert mit einem Agent aus `config.json`:

- **`cl`** (ohne Argument) → der `main`-Eintrag in `config.json`.
- **`cl <name>`** → der Eintrag mit `name === "<name>"` aus dem `agents`-Array in `config.json`. Namen sind frei wählbar (z. B. `name: "iwan"` → `cl iwan`); unbekannte Namen brechen mit Fehlermeldung ab.

Für den aufgelösten Agent gilt:

- **Model:** dessen `model` (aktuell z. B. `"sonnet"`) → `--model`.
- **System-Prompt:** Inhalt aller Dateien, die in dessen `contexts`-Array referenziert sind, verkettet → `--append-system-prompt`.
- **Permissions:** `--permission-mode auto` – jede von `cl` gestartete `claude`-Session (Agent, Model-Override, Headless über `cl server`, Task, Fix-Agent) läuft ausnahmslos im Auto-Mode, ohne Rückfragen. Zusätzlich: dessen optionales `permissions`-Array aus `config.json` (siehe "Default-Permissions (`permissions`-Feld)").

Implementiert in `src/launch.ts` (`launchAgent(name)`), aufgerufen aus dem `commander`-Action-Handler in `src/index.ts` mit dem optionalen `[agent]`-Argument.

## Default-Permissions (`permissions`-Feld)

Sowohl Agents (`main`, `agents[]`) als auch Tasks (`tasks[]`) können in `config.json` optional ein `permissions`-Feld tragen – ein Array von Permission-Regeln in der gleichen Syntax wie Claude Codes `settings.json` (`permissions.allow`), z. B.:

```json
{ "name": "review-local", "permissions": ["Bash(gradle *)", "Bash(./gradlew *)", "Bash(gradlew *)"] }
```

Ist `permissions` gesetzt, wird es beim Start der `claude`-Session als `--allowedTools <regel1> <regel2> ...` an `claude` angehängt (ganz am Ende der Argument-Liste, nach `--print <prompt>` bzw. dem positionalen `interactivePrompt`). `--allowedTools` ist additiv zu den `permissions.allow`/`ask`/`deny`-Regeln, die im Zielprojekt selbst (dessen `.claude/settings.json`) gelten – es ersetzt oder umgeht diese nie, sondern erlaubt zusätzlich genau die angegebenen Muster für diesen einen `claude`-Lauf. Fehlt `permissions` oder ist es ein leeres Array, wird kein `--allowedTools` angehängt (Verhalten unverändert wie zuvor).

**Nur für Agents (nicht für Tasks) kann `POST /` bzw. `POST /<agent>` das konfigurierte `permissions` pro Aufruf per Request-Body-Feld `permissions` vollständig überschreiben** (kein Merge mit den config.json-Default-Permissions des Agents) – siehe "HTTP-Server (cl server)" → Request-Body. Tasks laufen ausschließlich CLI-interaktiv und verwenden immer nur ihr eigenes `permissions` aus `config.json`, ohne Override-Möglichkeit.

Implementiert über den fünften, optionalen Parameter `permissions?: string[]` von `buildClaudeArgs()` in `src/launch.ts`, durchgereicht von `launchAgent()`, `runHeadlessCommand()` und `runTask()`. Typen/Validierung (`permissions?: string[]`, jeder Eintrag ein String) in `AgentDefinition`/`TaskDefinition` in `src/config.ts`.

## Model-Override (`cl <model>` / `cl <model> <agent>`)

Vier feste Subcommands überschreiben gezielt nur das `model` des sonst wie gewohnt aufgelösten Agents (Contexts, Auto-Mode-Permissions, `permissions`-Default bleiben gleich):

| Command     | Kurzform | Model    |
| ----------- | -------- | -------- |
| `cl haiku`  | `cl h`   | `haiku`  |
| `cl sonnet` | `cl s`   | `sonnet` |
| `cl opus`   | `cl o`   | `opus`   |
| `cl fable`  | `cl f`   | `fable`  |

- **`cl <model>`** (ohne Agent-Name) → `main`-Agent, aber mit `<model>` statt dessen `model` aus `config.json`.
- **`cl <model> <agent>`** → benannter Agent aus `agents[]`, ebenfalls mit `<model>` statt dessen `model`.

Beispiele: `cl opus` (main-Agent mit Opus), `cl s mainagent` (`mainagent` mit Sonnet), `cl h iwan` (Agent `iwan` mit Haiku).

Implementiert als vier `program.command(...)` in `src/index.ts` (parallel zum Root-Command mit dem `[agent]`-Argument – `commander` dispatcht anhand des ersten Positional-Tokens automatisch an den passenden Subcommand oder faellt auf die Root-Action zurueck). `launchAgent(name, modelOverride)` in `src/launch.ts` verwendet `modelOverride ?? agent.model`.

**Reservierte Namen:** `haiku`, `sonnet`, `opus`, `fable`, deren Kurzformen `h`, `s`, `o`, `f` sowie `server` sind für Commands reserviert. Enthält `config.json` einen `agents[].name`, der einem dieser Namen entspricht, **bricht die CLI beim Start sofort mit einer Fehlermeldung ab** – unabhängig davon, ob `cl`, `cl --help`, `cl --version` oder ein Subcommand aufgerufen wird (`assertNoReservedAgentNames()` in `src/config.ts`, eager geprüft in `src/index.ts` vor jedem Kommando).

## Headless-Modus (`-h, --headless [prompt]`)

Jeder Aufruf – `cl [agent]` und `cl <model> [agent]` – kann headless statt interaktiv laufen:

- **`cl <model> <agent> -h "<prompt>"`** (z. B. `cl sonnet mainagent -h 'mache irgendwas cooles'`) → `claude` läuft mit `--print "<prompt>"` (Claude Codes Non-Interactive-Modus): Antwort wird ausgegeben, dann beendet sich der Prozess. Modell, System-Prompt und Permissions sind identisch zum interaktiven Fall.
- **`cl <model> <agent> -h`** (Flag ohne Wert) → die CLI fragt selbst interaktiv über die Kommandozeile `Prompt: ` ab; nach Eingabe + Enter wird genau dieser Text als `<prompt>` headless an `claude` weitergereicht.
- **`cl -h "<prompt>"`** / **`cl -h`** (ohne Model-Subcommand, ohne Agent) → gleiches Verhalten für den `main`-Agent mit dessen Default-Model.
- Ohne `-h` (Default) bleibt jeder Aufruf wie bisher voll interaktiv im Vordergrund.

`-h` ist wegen dieser Funktion **nicht** mehr die Kurzform von `--help` (Kollision) – Hilfe ist nur noch über das lange `--help` erreichbar.

Implementiert über `resolveHeadlessPrompt()` in `src/index.ts` (nutzt `node:readline/promises` für die interaktive Abfrage) und `launchAgent(name, modelOverride?, headlessPrompt?)` in `src/launch.ts` (hängt bei gesetztem Prompt `--print <prompt>` an die `claude`-Argumente an). Die Option ist auf dem Root-Command und jedem der vier Model-Subcommands separat registriert; `program.enablePositionalOptions()` sorgt dafür, dass z. B. bei `cl sonnet mainagent -h 'text'` der Wert tatsächlich beim `sonnet`-Subcommand ankommt statt in der Root-Options-Verarbeitung verlorenzugehen.

## Task-Ausführung (`cl task <name>`)

Tasks sind wie Agents konfiguriert (`config.json`s `tasks[]`: `{ name, description, contexts, model }`), zusätzlich mit einem verpflichtenden `startCommand` (String) und optional `permissions` (siehe "Default-Permissions (`permissions`-Feld)"). `cl task <name>` startet damit eine ganz normale interaktive `claude`-Session – Model und System-Prompt (aus `contexts`) wie bei Agents – und übergibt `startCommand` als positionales Argument **ohne** `--print`: `claude` startet dadurch interaktiv und schickt `startCommand` automatisch als erste Nachricht ab, so als hätte man ihn selbst eingetippt und abgeschickt. Danach läuft die Session normal weiter (`stdio: 'inherit'`, Terminal-Eingabe geht direkt an `claude`).

Der Command kennt **keine Optionen** – nur `cl task <name>`, kein `-d`/`--detached`, kein `-h`/`--headless`, kein sonstiger Modus. Tasks sind ausnahmslos interaktiv.

**Tasks starten wie jede andere `claude`-Session von `cl` immer im Auto-Mode** (`claude --permission-mode auto`) – Tasks laufen typischerweise unbeaufsichtigt (`startCommand` läuft sofort los, ohne dass man erst manuell freigibt).

**Tasks sind nie über die API erreichbar** – kein `POST /task/:name`-Endpunkt, keine Erwähnung in `GET /manifest` (siehe "HTTP-Server (cl server)" und "Manifest (GET /manifest)"). Grund: HTTP-Requests haben kein TTY, eine interaktive Session ist darüber nicht sinnvoll nutzbar; Tasks sind bewusst CLI-only.

Implementiert über `buildClaudeArgs(model, systemPrompt, headlessPrompt?, interactivePrompt?, permissions?)` in `src/launch.ts` – der Auto-Mode ist fest in `buildClaudeArgs()` verdrahtet (`--permission-mode auto`, kein Parameter, keine Überschreibungsmöglichkeit), gilt also für alle Aufrufer gleichermaßen; `permissions` (aus `task.permissions`) ist dagegen optional und pro Task konfigurierbar (siehe "Default-Permissions (`permissions`-Feld)"). `interactivePrompt` (hier immer `startCommand`) wird als reines positionales Argument angehängt, sofern kein `headlessPrompt` gesetzt ist (der bleibt reserviert für den bestehenden `-h, --headless`-Mechanismus von Agents). `runTask(task)` spawnt `claude` mit `stdio: 'inherit'` im Vordergrund. Die CLI-Registrierung (`program.command('task')`, ein `<name>`-Argument, keine Optionen) liegt in `src/index.ts`.

## HTTP-Server (`cl server`)

`cl server` startet einen langlebigen HTTP-Server (`node:http`, Default-Port `8787`, überschreibbar mit `-p, --port`), der alle Agents aus `config.json` als headless Endpunkte exposed. Alle Aufrufe sind **immer headless** (kein interaktiver Modus über HTTP). Spezifikation: `openapi.json`.

**Alle Endpunkte sind per Google Authenticator (TOTP) geschützt** – Ausnahme sind ausschließlich `GET /health`, `GET /status`, die beiden Setup-Endpunkte (`POST /auth/setup`, `POST /auth/setup/confirm`) und `GET /auth/status`. Details siehe Abschnitte "Google-Authenticator-Schutz (TOTP)", "Erreichbarkeits-Check (`GET /health`)" und "Discovery-Check (`GET /status`)" weiter unten.

Mit `-P, --paths-file <file>` kann beim Start eine JSON-Datei angegeben werden, die nur den `paths`-Teil enthält (gleiche Form wie `config.json`s `paths`-Feld, z. B. `{ "paths": [{ "name": "myapp", "path": "/my/path" }] }`) und `config.json`s `paths` für diesen Serverlauf vollständig ersetzt – so lassen sich Arbeitsverzeichnisse überschreiben, ohne `config.json` selbst anzufassen (z. B. pro Umgebung/Deployment). Ohne die Option gilt weiterhin `config.json`s `paths` unverändert.

Der Server läuft im **Vordergrund** (blockiert das Terminal, bis `Ctrl+C`/`SIGTERM`). Beim Start werden zuerst **alle Endpunkte** auf der Konsole ausgegeben:

```
cl server laeuft auf http://localhost:8787
Endpunkte:
  POST http://localhost:8787/
  POST http://localhost:8787/dev
  GET  http://localhost:8787/state/:id
```

Danach wird **jeder eingehende Request** live mitgeloggt (zusätzlich zum SQLite-Eintrag in `t_access_log`):

```
2026-07-19T16:42:06.320Z POST / -> 202
2026-07-19T16:42:06.331Z GET /state/doesnotexist -> 404
```

**Endpunkte:**

- **`GET /health`** – kein Auth nötig, prüft nur Erreichbarkeit (`{ status: "ok", version }`). Siehe Abschnitt "Erreichbarkeits-Check (GET /health)".
- **`GET /status`** – kein Auth nötig, antwortet immer mit `204` und leerem Body. Siehe Abschnitt "Discovery-Check (GET /status)".
- **`GET /auth/status`** – nur aus dem lokalen Netz (sonst 404), liefert `{ active, pending }`. Siehe Abschnitt "Google-Authenticator-Schutz (TOTP)".
- **`POST /`** – startet einen Command auf dem `main`-Agent.
- **`POST /<agent>`** – startet einen Command auf dem benannten Agent aus `agents[]` (404, falls unbekannt).
- **`GET /state/<id>`** – liefert Status und (live aktualisierten) Output eines Commands (404, falls unbekannt).
- **`GET /paths`** – liefert nur die Namen (`paths[].name`) aller in `config.json` konfigurierten Pfade, ohne die zugehörigen Dateisystem-Pfade.
- **`GET /manifest`** – liefert Agents und Pfade (inkl. deren Commands/Hosted-Einträgen) gebündelt in einem Aufruf; enthält **keine** Tasks (siehe "Task-Ausführung (cl task <name>)"). Siehe Abschnitt "Manifest (GET /manifest)".
- **`GET /commands/<pathName>`** – liefert den vollständigen Command-Verlauf (Agent-Läufe **und** Pfad-Commands, gemeinsame `t_commands`-Tabelle) dieses Pfads, neueste zuerst, inkl. vollständigem/live aktualisiertem Output (404, falls `pathName` unbekannt). Siehe Abschnitt "Command-Verlauf (GET /commands/<pathName>)".
- **`GET /files/<pathName>`** – liefert nur die Namen (`paths[].hosted[].name`) aller hosted-Einträge des Pfads (404, falls `pathName` unbekannt).
- **`GET /files/<pathName>/<hostedName>`** – bei `type: "file"` lädt die Datei direkt herunter; bei `type: "path"` liefert eine Liste der Dateinamen, die direkt (nicht rekursiv) im Verzeichnis liegen (404, falls `pathName`/`hostedName` unbekannt oder die Datei/das Verzeichnis nicht mehr existiert).
- **`GET /files/<pathName>/<hostedName>/<fileName>`** – lädt eine einzelne Datei aus einem `type: "path"`-Verzeichnis herunter (404, falls `hostedName` vom `type: "file"` ist oder `fileName` nicht existiert; 400 bei ungültigem Dateinamen).
- **`GET /paths/<pathName>/commands`** – liefert alle konfigurierten Commands (`paths[].commands[]`) dieses Pfads (404, falls `pathName` unbekannt). Siehe Abschnitt "Pfad-Commands (paths[].commands)".
- **`POST /paths/<pathName>/commands/<key>`** – führt den Command aus, im Dateisystem-Verzeichnis des Pfad-Eintrags als `cwd` (404, falls `pathName`/`key` unbekannt). Siehe Abschnitt "Pfad-Commands (paths[].commands)".

**Es gibt bewusst keinen `POST /task/<name>`-Endpunkt und keine sonstige API-Route für Tasks** – Tasks sind ausschließlich über `cl task <name>` (immer interaktiv, siehe "Task-Ausführung (cl task <name>)") nutzbar.

**Request-Body (beide POST-Routen):**

```json
{
  "command": "mache irgendwas cooles",
  "path": "myapp",
  "model": "opus",
  "permissions": ["Bash(gradle *)", "Bash(./gradlew *)"]
}
```

- `command` (String, Pflicht) – der Prompt.
- `path` (String, Pflicht) – Name eines Eintrags aus `config.json`s `paths`-Array (`paths[].name`, z. B. `{ "name": "myapp", "path": "/my/path" }`). Der zugehörige Dateisystem-Pfad wird serverseitig aufgelöst und als Arbeitsverzeichnis (`cwd`) für den `claude`-Prozess verwendet – der tatsächliche Pfad wird nie direkt im Request angegeben, nur der Name. 404, falls kein Eintrag mit diesem Namen existiert.
- `model` (String, optional) – überschreibt das `model` aus `config.json` für diesen einen Aufruf, sonst wird `agent.model` verwendet.
- `permissions` (Array von Strings, optional) – überschreibt **vollständig** (kein Merge) das `permissions`-Array aus `config.json` für diesen einen Aufruf (400, falls kein Array von Strings), sonst wird `agent.permissions` verwendet. Siehe "Default-Permissions (`permissions`-Feld)" für Syntax und additive Semantik gegenüber den Projekt-Permissions.

**Ablauf eines POST-Requests:**

1. Body wird validiert (400 bei fehlendem/leerem `command`/`path` oder ungültigem JSON), Agent wird aufgelöst (404, falls unbekannt), `path`-Name wird gegen `config.json`s `paths[]` aufgelöst (404, falls unbekannt).
2. Eine `id` (`crypto.randomUUID()`) wird generiert, sofort eine Zeile in `t_commands` mit `status: "running"` angelegt (inkl. des aufgelösten Dateisystem-Pfads).
3. Response **sofort**: `202 { "id": "<uuid>" }` – der Request wartet nicht auf das Ergebnis.
4. `claude --model <model> --append-system-prompt <contexts> --permission-mode auto --print "<command>" [--allowedTools <permissions...>]` läuft im Hintergrund mit dem aufgelösten Pfad als Arbeitsverzeichnis (`cwd`); `--allowedTools` wird nur angehängt, falls effektive Permissions (Body-`permissions` oder sonst `agent.permissions`) vorhanden sind. Jeder Output-Chunk (stdout **und** stderr) wird sofort in `t_commands.output` geschrieben (live, nicht erst am Ende).
5. Nach Prozessende: `status` wird `"completed"` (Exit-Code 0) oder `"failed"`, `exit_code` wird gespeichert. Bei Spawn-Fehler (`claude` nicht gefunden): `status: "failed"` mit Fehlermeldung als `output`.

**`GET /state/<id>`** liefert dann:

```json
{
  "id": "...",
  "agent": "main",
  "model": "sonnet",
  "command": "...",
  "path": "/my/path",
  "status": "running",
  "output": "... bisheriger Output ...",
  "exitCode": null,
  "createdAt": "...",
  "updatedAt": "..."
}
```

**`GET /paths`** liefert:

```json
{ "paths": ["myapp", "otherapp"] }
```

**Hosted-Dateien (`paths[].hosted`):** Jeder Eintrag in `paths[]` kann zusätzlich ein `hosted`-Array (`{ name, path, type: "path" | "file" }`) definieren – benannte Datei- oder Verzeichnis-Freigaben innerhalb dieses Pfad-Eintrags, herunterladbar über `GET /files/...`. `hosted[].path` ist **relativ zum `path` des umgebenden Eintrags** (wird per `path.join()` zusammengesetzt, nicht als eigenständiger absoluter Pfad):

```json
{
  "name": "myapp",
  "path": "/my/path",
  "hosted": [
    { "name": "readme", "path": "README.md", "type": "file" },
    { "name": "reports", "path": "reports", "type": "path" }
  ]
}
```

Hier löst `readme` zu `/my/path/README.md` und `reports` zu `/my/path/reports` auf.

- `type: "file"` – `GET /files/myapp/readme` lädt `README.md` direkt herunter.
- `type: "path"` – `GET /files/myapp/reports` liefert `{ "files": ["a.pdf", "b.pdf"] }` (nur Dateien, die direkt im Verzeichnis liegen, nicht rekursiv); jede davon ist dann über `GET /files/myapp/reports/a.pdf` einzeln herunterladbar.

Der Download setzt `Content-Type` anhand der Dateiendung (kleine eingebaute MIME-Tabelle, Fallback `application/octet-stream`) sowie `Content-Disposition: attachment`. Bei `GET /files/<pathName>/<hostedName>/<fileName>` wird der aufgelöste Dateipfad zusätzlich gegen das Verzeichnis des hosted-Eintrags geprüft (muss darin liegen), um Pfad-Traversal zu verhindern.

**Protokollierung (SQLite):** Jeder Zugriff auf jeden Endpunkt (Erfolg wie Fehler, GET wie POST) wird in `t_access_log` geloggt (Zeitpunkt, Methode, Pfad, finaler Status-Code, bei POST der rohe Request-Body). Das ist eine **eigene** Tabelle, getrennt von `t_commands` (die ausschließlich den Command-Lifecycle inkl. Live-Output trackt). Die Datenbank-Datei (`commands.db`, WAL-Modus) liegt im Verzeichnis aus `config.json`s `databaseDirectory` (aktuell `/home/simon/commands`), wird beim Serverstart automatisch angelegt, falls nicht vorhanden.

Implementiert in `src/server.ts` (Routing, Body-Parsing mit 1-MB-Limit, JSON-Responses) und `src/db.ts` (SQLite-Zugriff über Node's eingebautes `node:sqlite`). `runHeadlessCommand()` in `src/launch.ts` ist die Server-Variante von `launchAgent()`: `stdio: ['ignore', 'pipe', 'pipe']` statt `'inherit'`, Output wird eingesammelt statt direkt ans Terminal durchgereicht, kein `process.exit()` (der Server läuft weiter).

`-P, --paths-file <file>` (Option auf dem `server`-Subcommand in `src/index.ts`) liest die angegebene Datei über `loadPathsOverride(filePath)` (`src/config.ts`, validiert per `parsePathsOverride()`) und ersetzt via `applyPathsOverride(config, paths)` das `paths`-Array der geladenen `config.json` vollständig, bevor `startServer()` aufgerufen wird. Ungültige/fehlende Datei bricht den Start mit Fehlermeldung und Exit-Code 1 ab.

## Erreichbarkeits-Check (`GET /health`)

Kein `X-TOTP-Code` und keine Lokalnetz-Beschränkung nötig – die Route gibt nichts Sensibles preis, ein laufender `cl server` ist über den `401`-Statuscode auf jeder geschützten Route ohnehin schon erkennbar. Antwort: `{ "status": "ok", "version": "0.1.0" }`. Gedacht für Clients (z. B. eine App), die vor jedem Verbindungsaufbau bzw. unabhängig vom TOTP-Pairing-Status prüfen wollen, ob unter der eingetragenen Adresse überhaupt ein `cl server` läuft.

Implementiert in `src/server.ts` (`handleGetHealth`), Version aus `src/version.ts`.

## Discovery-Check (`GET /status`)

Kein `X-TOTP-Code` und keine Lokalnetz-Beschränkung nötig. Antwortet **immer** mit `204` und leerem Body (kein JSON). Anders als `GET /health` gibt es keinerlei Payload zurück – bewusst minimal, damit ein Client damit zügig viele Adressen abfragen kann (z. B. beim Scannen des lokalen `/24`-Subnetzes nach einem erreichbaren `cl server`, siehe `commander`-App: Verbindungsaufbau → Automatische Server-Suche). `GET /health` bleibt der Endpunkt für einen bewussten, informativen Erreichbarkeits-Check inkl. Versionsnummer.

Implementiert in `src/server.ts` (`handleGetStatus`).

## Google-Authenticator-Schutz (TOTP)

Alle `cl server`-Endpunkte verlangen einen gültigen TOTP-Code (Google Authenticator, RFC 6238, 6-stellig, 30-Sekunden-Schritt) im Header `X-TOTP-Code` – **mit Ausnahme** von `GET /health`, `GET /status` und den drei `/auth/*`-Endpunkten. Es kann jeweils nur **ein** Authenticator gleichzeitig aktiv sein.

**Einrichtung + Statusabfrage (nur aus dem lokalen Netz erreichbar, sonst `404`):**

1. **`POST /auth/setup`** – erzeugt ein neues, noch unbestätigtes Secret und liefert `{ "secret": "...", "otpauthUrl": "otpauth://totp/..." }`. `secret` kann manuell in Google Authenticator eingegeben werden, `otpauthUrl` eignet sich zum Erzeugen eines QR-Codes für den Scan-Import. Schlägt mit `409` fehl, solange bereits ein Authenticator **aktiv** ist (dann muss dieser zuerst per CLI entfernt werden, siehe unten). Ein erneuter Aufruf, solange das Setup noch nicht bestätigt ist, ersetzt das vorherige unbestätigte Secret durch ein neues.
2. **`POST /auth/setup/confirm`** – Body `{ "code": "123456" }`, der aktuelle Code aus Google Authenticator für das per Schritt 1 erzeugte Secret. Bei korrektem Code (`200`) wird der Authenticator aktiv geschaltet; bei falschem Code `401` (Secret bleibt unbestätigt, ein weiterer Versuch ist möglich). `409`, falls bereits ein Authenticator aktiv ist.
3. **`GET /auth/status`** – liefert `{ "active": boolean, "pending": boolean }`, niemals das Secret selbst. `active` ist `true`, sobald ein Authenticator bestätigt ist; `pending` ist `true`, solange ein per Schritt 1 erzeugtes Secret noch nicht bestätigt wurde. Erlaubt einem Client, den Pairing-Zustand vorab abzufragen, statt sich beim ersten `POST /auth/setup`-Versuch nur auf den `409`-Statuscode zu verlassen.

Alle drei Endpunkte prüfen die Herkunft der Anfrage über `req.socket.remoteAddress` (niemals über spoofbare Header wie `X-Forwarded-For`) gegen private/loopback-Adressbereiche (`127.0.0.0/8`, `::1`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, IPv6-ULA `fc00::/7`, Link-Local `fe80::/10`) – außerhalb dieser Bereiche liefern alle drei Routen `404`, identisch zu einer unbekannten Route (kein Hinweis auf deren Existenz).

**Schutz aller übrigen Endpunkte:** Jeder Request ohne oder mit ungültigem `X-TOTP-Code`-Header erhält `401`. Es gibt bewusst **keine Replay-Sperre** pro Code – derselbe (noch gültige) Code kann für mehrere Requests innerhalb desselben 30-Sekunden-Fensters wiederverwendet werden, da TOTP hier als reine Zugriffsschranke für ein persönliches Tool dient und nicht als Einmal-Login; eine Replay-Sperre würde legitime, schnell aufeinanderfolgende API-Aufrufe mit demselben Code sonst blockieren. Zur Absicherung gegen Uhr-Drift wird zusätzlich zum aktuellen Zeitfenster je ein Schritt davor/danach akzeptiert.

**Entfernen (ausschließlich per CLI, kein Server-Endpunkt):** `cl totp remove` löscht das aktive/ausstehende Secret aus der Datenbank. Absicht: Ein Angreifer, der bereits vollen HTTP-Zugriff auf den Server hätte, könnte sonst den eigenen Authenticator entfernen und einen neuen einrichten. Da der Removal-Command nie als HTTP-Route exposed ist, erfordert das Entfernen zwingend Shell-Zugriff auf die Maschine, auf der `cl server` läuft.

Implementiert in `src/totp.ts` (Base32-En-/Decoding, `generateSecret`, `generateTotp`, `verifyTotp` mit Zeitfenster-Toleranz, `buildOtpAuthUrl`), `src/network.ts` (`isLocalNetworkAddress`), `src/db.ts` (Tabelle `t_totp`, Single-Row via `CHECK (id = 1)`: `getTotpSecret`/`setPendingTotpSecret`/`confirmTotpSecret`/`deleteTotpSecret`) und `src/server.ts` (Routing, `isRequestAuthorized()`, `handleGetAuthStatus()`). CLI-Command `cl totp remove` in `src/index.ts`.

## Pfad-Commands (`paths[].commands`)

Jeder Eintrag in `config.json`s `paths[]` kann zusätzlich ein `commands`-Array definieren – vordefinierte Shell-Befehle, die über die HTTP-API ausgelöst werden können:

```json
{
  "name": "myapp",
  "path": "/my/path",
  "commands": [
    {
      "key": "build",
      "command": "npm run build",
      "displayName": "Build",
      "description": "Baut das Projekt für Produktion"
    }
  ]
}
```

- `key` – eindeutiger Bezeichner innerhalb des Pfads, Teil der URL (`POST /paths/<pathName>/commands/<key>`).
- `command` – der auszuführende Shell-Befehl (`spawn(command, { shell: true, cwd })`), läuft **im Dateisystem-Verzeichnis des Pfad-Eintrags** (`paths[].path`, nicht ein `hosted`-Unterpfad).
- `displayName`, `description` – Anzeigename/Beschreibung, z. B. für eine spätere UI; werden 1:1 über `GET /paths/<pathName>/commands` mit ausgeliefert.

**`GET /paths/<pathName>/commands`** liefert `{ "commands": [{ "key", "command", "displayName", "description" }, ...] }` (404, falls `pathName` unbekannt).

**`POST /paths/<pathName>/commands/<key>`** startet den Command headless im Hintergrund (404, falls `pathName`/`key` unbekannt), analog zu den Agent-Commands: sofortige Antwort `202 { "id": "<uuid>" }`, Live-Output + Status über das bestehende `GET /state/<id>` (gemeinsame `t_commands`-Tabelle; `model` ist bei Pfad-Commands `"-"`, da kein LLM beteiligt ist).

Implementiert in `src/config.ts` (`PathCommandEntry`, `listPathCommands`, `resolvePathCommand`), `src/launch.ts` (`runShellCommand`) und `src/server.ts` (Routing, Wiederverwendung von `t_commands` für Status-Tracking).

## Manifest (`GET /manifest`)

Liefert die gesamte per `config.json` gesteuerte Oberfläche in einem einzigen Aufruf – gedacht als Grundlage für eine spätere, voll dynamische Remote-Steuerung (z. B. per App), ohne dass diese die Struktur von `config.json` kennen oder mehrere Endpunkte kombinieren muss:

```json
{
  "agents": [
    { "command": "cl", "description": "..." },
    { "command": "cl dev", "description": "..." }
  ],
  "paths": [
    {
      "name": "myapp",
      "commands": [
        { "key": "build", "command": "npm run build", "displayName": "Build", "description": "..." }
      ],
      "hosted": [
        { "name": "readme", "type": "file" },
        { "name": "reports", "type": "path" }
      ]
    }
  ]
}
```

- `agents` – identisch zu der Liste aus dem `--help`-Text (`listAgents()`), inkl. `main`-Agent als `"cl"`.
- `paths` – pro Pfad-Eintrag der Name, die vollständigen `commands[]` (wie `GET /paths/<pathName>/commands`) sowie `hosted[]` als `{ name, type }` (wie `GET /files/<pathName>`, aber zusätzlich mit `type`) – **nie** die zugrundeliegenden Dateisystem-Pfade.

Enthält **keine** Tasks – Tasks sind CLI-only (siehe "Task-Ausführung (cl task <name>)") und tauchen bewusst in keiner API-Antwort auf.

Kein Ersatz für die bestehenden Detail-Endpunkte, sondern eine zusätzliche, gebündelte Sicht für eine UI, die alle verfügbaren Befehle, Funktionen (Agents/Tasks) und Dateien dynamisch anzeigen will, ohne für jede neue `config.json`-Ergänzung angepasst werden zu müssen.

Implementiert in `src/config.ts` (`listHostedSummaries`) und `src/server.ts` (`handleGetManifest`).

## Command-Verlauf (`GET /commands/<pathName>`)

Liefert alle bisher für diesen Pfad ausgeführten Commands (sowohl Agent-Läufe aus `POST /`/`POST /<agent>` als auch Pfad-Commands aus `POST /paths/<pathName>/commands/<key>` – beide landen in derselben `t_commands`-Tabelle) als `{ commands: CommandRow[] }`, neueste zuerst. Jeder Eintrag ist identisch zum `GET /state/<id>`-Format (inkl. vollständigem, live aktualisiertem `output` und aktuellem `status`) – kein separates Schema. 404, falls `pathName` unbekannt.

Gedacht für eine App (siehe `commander/FEATURES.md`, "Verlauf"), die den kompletten Verlauf abgeschickter Befehle inkl. Output anzeigen will, statt nur den zuletzt gestarteten Command zu kennen.

Implementiert in `src/db.ts` (`listCommands(db, path)` – `SELECT ... WHERE path = ? ORDER BY created_at DESC, rowid DESC`, `rowid` als Tiebreaker bei identischem Millisekunden-Timestamp) und `src/server.ts` (`handleGetCommands`).

## Dynamisches `--help`

`cl --help` zeigt zusaetzlich zur `commander`-Standardausgabe eine Liste aller in `config.json` definierten Agents inklusive ihrer `description`:

```
Agents (aus config.json):
  cl            Standard-Agent, gestartet mit `cl` ohne Argument.
  cl mainagent  Gleicher Agent wie der Standard-Agent, aufrufbar per Name.
```

Diese Liste wird bei jedem `--help`-Aufruf frisch aus der aktuellen `config.json` gelesen (`formatAgentsHelp()` in `src/index.ts`, nutzt `listAgents()` aus `src/config.ts`) – ein neuer Agent in `config.json` erscheint automatisch, ohne Code-Aenderung. Bei ungueltiger `config.json` wird statt eines Crashs eine Fehlermeldung im Hilfetext angezeigt.

Zusaetzlich listet `cl --help` alle Tasks (`Tasks (aus config.json):` mit `cl task <name>` + `description`). Auch jeder Subcommand zeigt bei `--help` alle fuer ihn moeglichen Werte aus `config.json`:

- `cl task --help` → alle Tasks (`cl task <name>` + `description`); ohne konfigurierte Tasks der Hinweis `Keine Tasks konfiguriert.`
- `cl haiku|sonnet|opus|fable --help` (bzw. Kurzformen) → alle Agents wie bei `cl --help`.
- `cl server --help` → alle Agent-Endpunkte (`POST /`, `POST /<agent>` + `description`) sowie alle Pfade aus `paths[]` mit ihren Commands (`POST /paths/<pfad>/commands/<key>  <displayName>: <description>`) und Hosted-Eintraegen (`GET /files/<pfad>/<hosted>  (file|path)`).

Alle Listen werden bei jedem Aufruf frisch gelesen; bei ungueltiger `config.json` erscheint statt eines Crashs eine Fehlermeldung im jeweiligen Abschnitt.

## Deployment als `cl`

Das Tool wird als eigenständige, ausführbare Datei nach `~/.local/bin/cl` deployed:

- `npm run build` – TypeScript-Compile (`tsc`) mit vollem Type-Checking, Ausgabe nach `dist/`.
- `npm run deploy` – bündelt `dist/index.js` per `esbuild` (alle Dependencies wie `commander` werden eingebettet, da am Zielort kein `node_modules` existiert) und kopiert das Ergebnis nach `~/.local/bin/cl` (ausführbar).
- `npm run release` – führt `build` und `deploy` nacheinander aus.
- `npm run dev` – führt `src/index.ts` direkt über `tsx` aus, ohne vorherigen Compile-Schritt.

## Config/Context-System

`config.json` (Projekt-Root) hat sechs Felder:

- `main` – ein Objekt `{ description: string, contexts: string[], model: string }`, der Default-Agent für `cl` ohne Argument.
- `agents` – ein Array benannter Objekte `{ name: string, description: string, contexts: string[], model: string }`, erreichbar über `cl <name>`.
- `databaseDirectory` – Verzeichnis für die SQLite-Datenbank von `cl server` (siehe "HTTP-Server (cl server)"), aktuell `/home/simon/commands`.
- `paths` – ein Array benannter Arbeitsverzeichnisse `{ name: string, path: string, hosted?: { name: string, path: string, type: "path" | "file" }[], commands?: { key: string, command: string, displayName: string, description: string }[] }` (z. B. `{ "name": "myapp", "path": "/my/path" }`), aus dem `cl server`s POST-Routen über den `path`-Namen im Request-Body das Arbeitsverzeichnis (`cwd`) für den `claude`-Prozess auflösen (siehe "HTTP-Server (cl server)"). Das optionale `hosted`-Array definiert benannte Datei-/Verzeichnis-Freigaben, herunterladbar über `GET /files/...` (siehe "HTTP-Server (cl server)") – `hosted[].path` ist relativ zum `path` des Eintrags, nicht absolut. Das optionale `commands`-Array definiert vordefinierte Shell-Befehle, auslösbar über `POST /paths/<pathName>/commands/<key>` (siehe "Pfad-Commands (paths[].commands)").
- `tasks` – ein Array benannter Objekte `{ name: string, description: string, contexts: string[], model: string, startCommand: string }`, erreichbar ausschließlich über `cl task <name>` (immer interaktiv, siehe "Task-Ausführung (cl task <name>)") – nie über `cl server`.
- `ticketAgent` – ein Objekt `{ model: string, task: string }` für den Ticket-Erstellungs-Agent (siehe "Ticket-System (cl ticket)"). `task` ist die frei editierbare Aufgabenbeschreibung ("interpretiere den Text im Projektkontext, erstelle daraus ein Ticket"); `model` dessen Model (Default `"haiku"`).

`description` wird im `--help`-Text pro Agent angezeigt (siehe "Dynamisches --help").

`contexts` referenziert Markdown-Dateien unter `contexts/`:

- `"main"` → `contexts/main.md`
- `"dev/tools"` → `contexts/dev/tools.md` (Unterordner werden 1:1 gespiegelt)

Aufgelöst über `src/config.ts` (`loadConfig()`, `resolveAgent(name)`, `resolveContext(name)`):

- Bevorzugt werden die lokal liegenden Dateien (`config.json`, `contexts/*.md`) gelesen – relevant für `npm run dev`.
- Sind keine lokalen Dateien vorhanden (z. B. beim deployten `cl`-Binary in `~/.local/bin`, wo keine `config.json`/`contexts/` danebenliegen), wird auf eine zur Build-Zeit eingebettete Kopie zurückgegriffen. So funktioniert das Tool unabhängig vom Ausführungsort.
- Bei jedem `dev`/`build` wird die eingebettete Kopie (`src/generated/embedded-context.ts`) frisch aus dem aktuellen Stand von `config.json` + `contexts/**/*.md` generiert.

Wird vom Agent-Start (`cl` / `cl <name>`) genutzt, um Model und System-Prompt des jeweiligen Agents aufzulösen.

## Android-Build+Install (`cl inst` / `cl instr`)

Zwei fest verdrahtete Commands, die **weder in `config.json` konfigurierbar noch über `cl server`/die HTTP-API erreichbar** sind – reine CLI-Shortcuts für den lokalen Android-Workflow im jeweils aktuellen Arbeitsverzeichnis (`process.cwd()`, nicht ein `paths[]`-Eintrag):

- **`cl inst`** – baut das Android-Projekt im aktuellen Verzeichnis per Gradle im **Debug**-Modus (`./gradlew assembleDebug`) und installiert anschließend die resultierende APK auf **allen** aktuell per `adb devices` gefundenen Geräten.
- **`cl instr`** – identisch, aber im **Release**-Modus (`./gradlew assembleRelease`).

Ablauf beider Commands (Build-Fix-Schleife + Install):

1. `./gradlew <assembleDebug|assembleRelease>` läuft im aktuellen Verzeichnis; stdout/stderr werden live durchgereicht **und** zur Auswertung gesammelt.
2. **Schlägt der Build fehl (Exit-Code ≠ 0) oder enthält die Build-Ausgabe Warnings** (Substring `warning` case-insensitive, oder Kotlinc-Zeilen der Form `w: <file>: <message>`), wird **kein** Install versucht. Stattdessen startet ein `claude`-Prozess mit `--model sonnet`, `--permission-mode auto` und `--print` (Fix-Agent, headless/autonom, `stdio: 'inherit'`) – als Prompt bekommt er die komplette Build-Ausgabe plus einen kurzen Hinweis, ob es sich um einen Fehler oder um Warnings handelte. Nach Abschluss des Fix-Agents wird der Build **erneut** gestartet (zurück zu Schritt 1) – das wiederholt sich **so oft, bis ein Build ohne Fehler und ohne Warnings durchläuft** (keine Obergrenze für die Anzahl der Versuche).
3. Erst nach einem fehler- und warnungsfreien Build wird die APK unterhalb von `**/build/outputs/apk/<debug|release>/*.apk` gesucht (rekursiv ab dem aktuellen Verzeichnis, versteckte Ordner werden übersprungen) – kein Treffer wirft einen Fehler.
4. `adb devices` listet alle aktuell verbundenen Geräte-Serials.
5. Für jedes gefundene Gerät wird zuerst der Gerätename per `adb -s <serial> shell getprop ro.product.model` ermittelt (Fallback: die Serial), dann `adb -s <serial> install -r <apk>` einzeln ausgeführt – **schlägt die Installation auf einem Gerät fehl, wird der Fehler abgefangen und geloggt (`Installation auf <Name> (<Serial>) fehlgeschlagen: …`), die restlichen Geräte werden trotzdem weiter versucht** (kein Abbruch der gesamten Schleife). Erfolg wird als `Installiert auf <Name> (<Serial>).` geloggt.
6. Am Ende wird eine Zusammenfassung ausgegeben: `Installiert auf <n> Geraet(en): <Name> (<Serial>), …` – nur erfolgreich installierte Geräte werden gezählt; ohne Erfolg `Auf keinem Geraet installiert.`

Ist `claude` (für den Fix-Agent) nicht im `PATH` bzw. schlägt sein Start fehl, bricht der gesamte Command mit Fehler ab (kein Install-Versuch).

`inst`/`instr` sind reservierte Command-Namen (wie `server`/`task`/`totp`/`ticket`) – ein `agents[].name` in `config.json`, der damit kollidiert, lässt die CLI beim Start sofort mit Fehler abbrechen.

Implementiert in `src/gradle-install.ts` (`buildAndInstall`, `findApk`, `parseAdbDevices`, `formatInstallSummary`, intern `runGradleBuild`/`runFixAgent`/`hasWarnings`/`listAdbDevices`/`readDeviceName`/`installApk`), registriert als zwei `program.command(...)` in `src/index.ts`. Getestet über echte temporäre Verzeichnisse (`findApk`), ein Fake-`adb`-Shellscript fürs `PATH` (`src/test-support/mock-adb.ts`), ein skriptbares Fake-`gradlew`-Shellscript im jeweiligen Test-Arbeitsverzeichnis (`src/test-support/mock-gradlew.ts`, liefert pro Aufruf ein anderes Ergebnis aus einer vorgegebenen Schritt-Sequenz) sowie das Fake-`claude`-Binary (`src/test-support/mock-claude.ts`, optionales `logFile` protokolliert Aufrufe auch bei `stdio: 'inherit'`) – sowohl auf Funktions- als auch auf voller CLI-Subprozess-Ebene (`src/gradle-install.test.ts`, `src/index.test.ts`).

## Ticket-System (`cl ticket`, `/tickets/...`)

Ein leichtgewichtiger, pro Pfad-Eintrag (`config.json`s `paths[].name`) gefuehrter Ticket-Tracker, zusaetzlich global (ueber alle Pfade hinweg) abrufbar. Ein Ticket hat `id` (fortlaufend, automatisch vergeben, **global eindeutig ueber alle Pfade hinweg** – keine separate Zaehlung pro Pfad), `pathName` (der Pfad, zu dem das Ticket gehoert – nicht editierbar), `originalRequest` (die urspruengliche, unveraenderte Anweisung des Users), `summary` (kurze Beschreibung von Ziel und aktuellem Ist-Zustand), `claudeInstruction` (konkrete Anweisung, wie man sie Claude spaeter geben wuerde, um das Feature/den Fix umzusetzen), `category` (freies Schlagwort zur Gruppierung zusammenhaengender Tickets), `status` (`"open"` | `"in progress"` | `"done"` | `"rejected"`, initial immer `"open"`), `ipAddress` (IP-Adresse des Clients, der `POST /tickets/<pathName>` aufgerufen hat, aus `req.socket.remoteAddress` – wie `id`/`pathName` nicht editierbar, `null` bei Tickets aus der Zeit vor diesem Feld), `createdAt`, `updatedAt`.

**Anlegen per Agent (`cl ticket from <path> <text>` / `POST /tickets/<pathName>`):** `text` wird **unveraendert** als `originalRequest` gespeichert. Zusaetzlich laeuft ein Agent (Model + Aufgabenbeschreibung aus `config.json`s `ticketAgent: { model, task }`, Default-Model `"haiku"`) im Dateisystem-Verzeichnis des Pfad-Eintrags (`cwd`) und bekommt `text` als Prompt. Er kann das Projekt dort explorieren (Dateien lesen, README, Code), um zu verstehen, was `text` im Projektkontext bedeuten koennte, und liefert daraus `summary`/`claudeInstruction`/`category` fuer das neue Ticket. **`ticketAgent.task` ist frei ueber `config.json` editierbar** (z. B. um die Aufgabenbeschreibung spaeter anzupassen, ohne Code zu aendern) – der Teil, der das Agent-Ergebnis in ein striktes JSON-Format zwingt (`{"summary","claudeInstruction","category"}`, keine Prosa/Markdown drumherum), ist dagegen fest im Code verdrahtet (`buildTicketAgentSystemPrompt()` in `src/ticket.ts`) und nicht ueber `config.json` aenderbar, damit ein editierter `task`-Text das Parsen der Antwort nicht versehentlich brechen kann.

Der Agent laeuft **synchron** (anders als `POST /`/`POST /paths/.../commands/...`, die sofort `202` liefern und im Hintergrund weiterlaufen) – `POST /tickets/<pathName>` wartet, bis der Agent fertig ist, und antwortet dann direkt mit `201` + dem fertigen Ticket (oder `502`, falls der Agent fehlschlaegt oder seine Antwort kein gueltiges Ticket-JSON enthaelt). Das Parsen der rohen Agent-Antwort (`parseTicketAgentOutput()`/`extractJsonObjects()` in `src/ticket.ts`) ist robust gegen Prosa vor/nach dem JSON, Markdown-Codebloecke (` ```json ... ``` `) und Braces innerhalb von String-Werten (brace-tiefenbasierter, String-aware Scan) – nimmt bei mehreren gefundenen JSON-Objekten das letzte, das dem Schema entspricht.

**Endpunkte (`src/server.ts`, alle ausser den globalen Ausnahmen mit `X-TOTP-Code` geschuetzt):**

- **`GET /tickets`** – listet Tickets ueber alle Pfade hinweg, optionaler Query-Parameter `?status=<open|in progress|done|rejected>` (ohne Filter: alle Status). 400 bei ungueltigem `status`-Wert.
- **`GET /tickets/<pathName>`** – listet Tickets dieses Pfads, optionaler Query-Parameter `?status=`. 404 bei unbekanntem `pathName`, 400 bei ungueltigem `status`-Wert.
- **`POST /tickets/<pathName>`** – Body `{ "text": "..." }`, siehe oben. 400 bei fehlendem/leerem `text`, 404 bei unbekanntem `pathName`, 502 bei Agent-Fehlschlag/unparsebarer Antwort.
- **`GET /tickets/<pathName>/<id>`** – ein einzelnes Ticket. 404, falls `id` unbekannt **oder** zu einem anderen `pathName` gehoert (IDs sind global eindeutig, aber nur innerhalb ihres eigenen Pfads abrufbar – kein Erraten/Leaken von Tickets anderer Projekte ueber die ID).
- **`PATCH /tickets/<pathName>/<id>`** – Body mit einem oder mehreren der Felder `originalRequest`/`summary`/`claudeInstruction`/`category`/`status` (mindestens eines erforderlich, sonst `400`; `pathName`/`id`/`ipAddress` sind **nicht** editierbar). Liefert das aktualisierte Ticket.
- **`DELETE /tickets/<pathName>/<id>`** – loescht das Ticket unwiderruflich, liefert `{ "message": "..." }`.

**CLI (`cl ticket ...`, `"ticket"` ist reservierter Command-Name):**

- **`cl ticket from <path> <text>`** – wie `POST /tickets/<pathName>`, gibt das erstellte Ticket als JSON aus.
- **`cl ticket get <path> [id]`** – ohne `id`: listet nur Tickets mit Status `"open"` (bewusst anders als der API-Default "alle Status", siehe oben – deckt den haeufigsten CLI-Anwendungsfall "was ist gerade offen?" direkt ab). Mit `id`: liefert genau dieses eine Ticket, unabhaengig vom Status.
- **`cl ticket list <path> [-s, --status <status>]`** – listet alle Tickets eines Pfads, optional nach Status gefiltert (ohne `--status`: alle Status, im Gegensatz zu `get` ohne `id`).
- **`cl ticket list-all [-s, --status <status>]`** – wie `list`, aber ueber alle Pfade hinweg (entspricht `GET /tickets`).
- **`cl ticket update <path> <id> [--original-request] [--summary] [--instruction] [--category] [--status]`** – bearbeitet ein Ticket (mindestens eine Option erforderlich).
- **`cl ticket delete <path> <id>`** – loescht ein Ticket.

Persistenz: `t_tickets`-Tabelle in derselben SQLite-Datenbank wie `t_commands`/`t_totp` (`config.json`s `databaseDirectory`) – `insertTicket`/`getTicket`/`listTickets`/`listAllTickets`/`updateTicket`/`deleteTicket` in `src/db.ts`, `TICKET_STATUSES` als Single-Source-of-Truth fuer gueltige Status-Werte (von CLI und Server importiert). `migrateLegacyTicketColumns()` hebt beim Start (`openDatabase()`) eine DB im fruehen Schema (Spalten `title`/`description`/`task`, Status `"closed"`) auf das aktuelle Schema und droppt danach die alten Spalten (sie waren `NOT NULL` ohne `DEFAULT` und liessen sonst jedes neue `INSERT` mit "NOT NULL constraint failed" scheitern).

Implementiert in `src/ticket.ts` (Agent-Ausfuehrung + Antwort-Parsing), `src/db.ts` (`t_tickets`), `src/server.ts` (Routing/Validierung), `src/index.ts` (`ticket`-Subcommand-Gruppe), `openapi.json`. Getestet in `src/ticket.test.ts` (reine Funktionen `extractJsonObjects`/`parseTicketAgentOutput` inkl. vieler Edge-Cases, `runTicketAgent` gegen das Fake-`claude`-Binary), `src/db.test.ts` (CRUD, `listAllTickets`, Legacy-Migration), `src/server.test.ts` (alle Endpunkte inkl. Fehlerfaelle) und `src/index.test.ts` (alle Subcommands als CLI-Subprozess).

## Collection-System (`cl collect`, `POST /collect`, `GET /collections`, `GET /collections/get/<name>`)

`config.json` bekommt zwei zusätzliche Top-Level-Felder: `contentPath` (String, Zielverzeichnis) und `collection` (Array von `{ sourcePath, targetName }`) – definiert, welche Dateien (typischerweise APKs) eingesammelt werden sollen und unter welchem Namen sie im Zielverzeichnis landen.

- **`cl collect [targetName]`** – ohne Argument werden alle `collection`-Einträge kopiert, mit Argument nur der Eintrag mit passendem `targetName` (Fehler + Exit-Code 1, falls unbekannt). Jede Datei wird als `<targetName><Original-Endung der sourcePath>` unter `contentPath` abgelegt (keine doppelte Endung, falls `targetName` sie schon trägt). Gibt die Zusammenfassung (`{ results, errors }`) als JSON aus.
- **`POST /collect`** (authentifiziert, `Authorization: Bearer <jwt>`) – identisches Verhalten über HTTP, optionaler Body `{ "targetName"?: string }`. Sammelt synchron (kein 202/Polling – Datei-Kopien sind schnell), Fehler pro Eintrag (z. B. fehlende `sourcePath`) werden gesammelt statt den ganzen Lauf abzubrechen; unbekannter `targetName` liefert `404`.
- **`GET /collections`** – **komplett unauthentifiziert**, listet alle Dateien direkt unter `contentPath` (nicht rekursiv) als `[{ name, timestamp }]`, neueste zuerst.
- **`GET /collections/get/<name>`** – **komplett unauthentifiziert**, lädt eine Datei per exaktem Namen herunter (Pfad-Traversal-Schutz wie bei den bestehenden `hosted`-Datei-Downloads, `Content-Type` inkl. `.apk`-MIME-Type).

Bewusst unauthentifiziert (`/collections`, `/collections/get/*`), damit ein separates, schlankes Gerät ohne Login/TOTP-Pairing (siehe `app-getter/` unten) im lokalen Netz gesammelte APKs finden und installieren kann.

Implementiert in `src/collect.ts` (`collectAll`/`collectOne`/`listCollectedFiles`/`resolveCollectedFilePath`, reine Logik, von CLI **und** Server gemeinsam genutzt), `src/config.ts` (`CollectionEntry`, `contentPath`/`collection`-Validierung, `"collect"` als reservierter Command-Name), `src/server.ts` (Routing), `src/index.ts` (`cl collect`), `openapi.json`.

## Feedback-System (`POST /feedback`, `GET/PATCH/DELETE /feedback(/<id>)`)

Ein leichtgewichtiger, komplett serverweiter (nicht pfadgebundener) Feedback-Kasten:

- **`POST /feedback`** – **komplett unauthentifiziert**, Body `{ "text": string }`, legt einen Eintrag in `t_feedback` an (`201` mit dem Datensatz). Gedacht für beliebige Absender im lokalen Netz, ohne Login (z. B. eine eigene kleine Feedback-Quelle).
- **`GET /feedback`** (authentifiziert) – listet alle Einträge, neueste zuerst.
- **`PATCH /feedback/<id>`** (authentifiziert) – Body `{ "text": string }` (Pflicht), `404` bei unbekannter ID.
- **`DELETE /feedback/<id>`** (authentifiziert) – löscht den Eintrag unwiderruflich, `404` bei unbekannter ID.

Der `commander` verwaltet Feedback (siehe `commander/FEATURES.md`): ansehen, bearbeiten, löschen, oder per Klick in ein Ticket umwandeln (ruft clientseitig `POST /tickets/<pathName>` mit dem Feedback-Text und danach `DELETE /feedback/<id>` auf – kein eigener Server-Endpunkt für die Umwandlung nötig). Es gibt bewusst keine Möglichkeit, Feedback aus dem `commander` heraus **anzulegen** – `POST /feedback` ist für externe Absender gedacht.

Implementiert in `src/db.ts` (`t_feedback`-Tabelle, `insertFeedback`/`listFeedback`/`getFeedback`/`updateFeedback`/`deleteFeedback`), `src/server.ts` (Routing), `openapi.json`.

## Tests (`npm test`)

`npm test` (= `tsx --test 'src/**/*.test.ts'`) führt die komplette Test-Suite aus – 324 Tests über 12 Dateien, ein File pro Feature-Bereich:

- **`src/config.test.ts`** – Validierung (`parseConfig`: gültige/ungültige Configs, reservierte Agent-/Command-Namen, `hosted`-/`commands`-Einträge, optionales `permissions`-Feld bei Agents/Tasks: akzeptiert/verwirft), `listAgents`, `listHostedNames`/`resolveHostedEntry`, `listPathCommands`/`resolvePathCommand`, sowie `loadConfig`/`resolveAgent`/`resolveContext`/`resolveTask` gegen echte temporäre Fixtures (sowohl "lokale Dateien vorhanden" als auch "keine lokalen Dateien → Embedded-Fallback").
- **`src/launch.test.ts`** – `buildClaudeArgs`/`buildSystemPrompt` (reine Funktionen, inkl. `--allowedTools` bei gesetzten/leeren `permissions`) sowie `runHeadlessCommand`/`runShellCommand` gegen ein Fake-`claude`-Binary bzw. echte Shell-Commands (Output-Streaming, Exit-Codes, Verhalten wenn `claude` fehlt, Weitergabe von `permissions` als `--allowedTools`).
- **`src/db.test.ts`** – SQLite-Operationen (`openDatabase`, `insertCommand`/`getCommand`, `updateCommandOutput`, `completeCommand`, `listCommands` (Pfad-Filter, neueste zuerst), `logAccess`, `getTotpSecret`/`setPendingTotpSecret`/`confirmTotpSecret`/`deleteTotpSecret`, `insertTicket`/`getTicket`/`listTickets`/`listAllTickets`/`updateTicket`/`deleteTicket` inkl. Status-Filter und pfaduebergreifender ID-Eindeutigkeit, sowie `migrateLegacyTicketColumns` gegen eine handgebaute Alt-Schema-DB) gegen echte temporäre `.db`-Dateien.
- **`src/ticket.test.ts`** – `extractJsonObjects`/`parseTicketAgentOutput` (reine Funktionen: sauberes JSON, Markdown-Codebloecke, Prosa davor/danach, verschachtelte/String-interne Braces, escapte Anfuehrungszeichen, mehrere Objekte, fehlende/leere/falsch typisierte Felder, kaputtes JSON) sowie `runTicketAgent` gegen ein Fake-`claude`-Binary (Erfolg, nicht-null Exit-Code, unparsebare Antwort trotz Exit-Code 0, `claude` fehlt im `PATH`).
- **`src/totp.test.ts`** – Base32-En-/Decoding-Rundreise, `generateTotp`/`verifyTotp` (gültiger Code, Zeitfenster-Toleranz, falsches Secret/Format), `buildOtpAuthUrl`.
- **`src/network.test.ts`** – `isLocalNetworkAddress` gegen Loopback, RFC1918-Bereiche, IPv6-ULA/Link-Local, öffentliche Adressen, IPv4-mapped IPv6.
- **`src/server.test.ts`** – `cl server`s HTTP-Endpunkte per echtem `fetch()` gegen einen in-process gestarteten Server (Erfolg, Validierungsfehler, 404s, 401 ohne/mit falschem `X-TOTP-Code`, Live-Status `running` → `completed`, Model-Override, `permissions`-Default aus `config.json` sowie Override + Validierung (400 bei falschem Typ) per Request-Body, hosted-Datei-Download, hosted-Verzeichnis-Listing, Pfad-Commands, `GET /commands/<pathName>` (Pfad-Filter, neueste zuerst, 404 bei unbekanntem Pfad, 401 ohne Auth), `GET /health` ohne Auth, `GET /tickets` (global) sowie alle `/tickets/<pathName>/...`-Endpunkte inkl. Status-Filter, 502 bei Agent-Fehlschlag und 404 bei pfadfremder Ticket-ID).
- **`src/server-auth.test.ts`** – die vollständige TOTP-Setup-Lebensdauer (unbestätigt → 401, Setup → Confirm → aktiv, `409` bei erneutem Setup-Versuch, Code-Wiederverwendbarkeit im selben Zeitfenster) inkl. `GET /auth/status` bei jedem Zwischenschritt (`{active:false,pending:false}` → `{active:false,pending:true}` → `{active:true,pending:false}`).
- **`src/index.test.ts`** – die komplette CLI als Subprozess (`--help`, `--version`, Agent-Start, Model-Override + Headless mit/ohne Prompt-Wert, Agent-`permissions` haengen `--allowedTools` an, unbekannter Agent, Startup-Crash bei reserviertem Namen, `cl server`/`cl task` (inkl. Task-`permissions` haengen `--allowedTools` an)/`cl totp remove`/`cl inst`/`cl instr`/`cl ticket from|get|list|list-all|update|delete` End-to-End inkl. `SIGTERM`-Shutdown).
- **`src/gradle-install.test.ts`** – `parseAdbDevices` (reine Funktion), `findApk` (echte temporäre Verzeichnisstrukturen), `formatInstallSummary` (Singular/Plural/leer), `buildAndInstall` End-to-End gegen ein skriptbares Fake-`gradlew`-Shellscript (`steps`-Sequenz) + ein Fake-`adb`-Shellscript im `PATH` (Erfolg auf mehreren Geräten, Installationsfehler auf einem Gerät bricht die anderen nicht ab, keine Geräte gefunden, Gerätenamen + Abschluss-Zusammenfassung) sowie der Fix-Agent-Kreislauf (Build-Fehler bzw. Warnings starten den Fake-`claude`-Fix-Agent im Auto-Mode, danach erneuter Build; mehrfache Wiederholung bis fehlerfrei; Abbruch, wenn `claude` für den Fix-Agent nicht gefunden wird).
- **`src/collect.test.ts`** – `collectAll`/`collectOne`/`listCollectedFiles`/`resolveCollectedFilePath` (reine fs-Logik gegen echte temporäre Verzeichnisse: Extension-Anhängen, keine doppelte Extension, fehlende `sourcePath` landet in `errors` statt Abbruch, unbekannter `targetName` wirft, Pfad-Traversal wird abgelehnt).

Kein echter `claude`-Aufruf in den Tests: `src/test-support/mock-claude.ts` erzeugt ein ausführbares Fake-`claude`-Script, das seine Argumente als JSON zurückmeldet und konfigurierbare Output/Exit-Codes liefert. `src/test-support/fixture-config.ts` erzeugt temporäre `config.json`+`contexts/`-Verzeichnisse; `src/config.ts`s `getRootDir()` liest dafür `process.env.CL_ROOT_DIR` (nur für Tests relevant, im Normalbetrieb ungesetzt). `src/test-support/run-cli.ts` spawnt die CLI für Subprozess-Tests.

## Companion-App (`commander/`)

Natives Android-Gegenstück zu diesem Server: eigenständiges Gradle-Projekt im Unterordner `commander/`, siehe `commander/FEATURES.md` für die volle Beschreibung. Kurzfassung: IP/Port eintragen, richtet den Google Authenticator selbst ein (kein externes Authenticator-App nötig – die App berechnet den TOTP-Code selbst aus dem Secret), zeigt danach alle über `GET /manifest` gemeldeten Agents/Pfade/Commands/Hosted-Dateien dynamisch an, kann Agents/Pfad-Commands starten und deren Status/Output live verfolgen sowie Hosted-Dateien (z. B. von `paths[].commands` gebaute APKs) herunterladen und öffnen/teilen. Ein "Verlauf"-Einstieg pro Projekt zeigt den kompletten, live per `GET /commands/<pathName>` gepollten Command-Verlauf. Zusätzlich ein eigener "Tickets"-Einstieg auf der Startseite (Liste über alle Pfade hinweg, Status-Filter, Anlegen per Text mit Projekt-Auswahl, komplett editierbar über `/tickets/...`) sowie derselbe Ticket-Tracker gefiltert pro Pfad-Detailseite – jedes Ticket lässt sich per Play-Button (mit Bestätigung + Agentenwahl) direkt ausführen: schickt `claudeInstruction` an `POST /<agent>` und schließt das Ticket. Zwei weitere Einträge im Projekt-Hub: "Feedback" (serverweite Feedback-Liste ansehen/bearbeiten/löschen/in ein Ticket umwandeln) und "Sammlung" (löst `POST /collect` aus, alle oder ein einzelner `targetName`).

## Installer-App (`app-getter/`)

Eine zweite, komplett eigenständige Android-App (Gradle-Projekt im Unterordner `app-getter/`, siehe `app-getter/FEATURES.md`) mit genau einer Aufgabe: einen `cl server` im lokalen Netz automatisch finden (gleiche Discovery-Technik wie `commander`), alle über `GET /collections` gemeldeten Dateien auflisten und per Tap herunterladen + automatisch installieren. Komplett ohne Login/TOTP – nutzt ausschließlich die unauthentifizierten `GET /status`/`GET /collections`/`GET /collections/get/<name>`-Endpunkte. Gedacht für Geräte, auf denen APKs installiert werden sollen, ohne dass sie vollen Fernsteuerungs-Zugriff (wie `commander`) bräuchten oder ein Google-Authenticator-Pairing durchlaufen müssten.
