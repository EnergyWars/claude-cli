# Features

## CLI-Grundgerüst

Einstiegspunkt (`src/index.ts`) mit `commander` als Argument-Parser. Bietet:

- `--help` (**ohne** `-h`-Kurzform, siehe "Headless-Modus") – zeigt Nutzung, Optionen, das `[agent]`-Argument, die Model-Subcommands sowie eine dynamisch aus `config.json` gelesene Liste aller Agents (siehe "Dynamisches --help").
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
- **Permissions:** `--permission-mode acceptEdits` – Read ist in Claude Code ohnehin promptlos, `acceptEdits` erlaubt zusätzlich Write/Edit ohne Rückfrage (andere Tools wie Bash fragen weiterhin nach).

Implementiert in `src/launch.ts` (`launchAgent(name)`), aufgerufen aus dem `commander`-Action-Handler in `src/index.ts` mit dem optionalen `[agent]`-Argument.

## Model-Override (`cl <model>` / `cl <model> <agent>`)

Vier feste Subcommands überschreiben gezielt nur das `model` des sonst wie gewohnt aufgelösten Agents (Contexts, `acceptEdits`-Permissions bleiben gleich):

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

## HTTP-Server (`cl server`)

`cl server` startet einen langlebigen HTTP-Server (`node:http`, Default-Port `8787`, überschreibbar mit `-p, --port`), der alle Agents aus `config.json` als headless Endpunkte exposed. Alle Aufrufe sind **immer headless** (kein interaktiver Modus über HTTP). Spezifikation: `openapi.json`.

**Alle Endpunkte sind per Google Authenticator (TOTP) geschützt** – Ausnahme sind ausschließlich die beiden Setup-Endpunkte (`POST /auth/setup`, `POST /auth/setup/confirm`). Details siehe Abschnitt "Google-Authenticator-Schutz (TOTP)" weiter unten.

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

- **`POST /`** – startet einen Command auf dem `main`-Agent.
- **`POST /<agent>`** – startet einen Command auf dem benannten Agent aus `agents[]` (404, falls unbekannt).
- **`GET /state/<id>`** – liefert Status und (live aktualisierten) Output eines Commands (404, falls unbekannt).
- **`GET /paths`** – liefert nur die Namen (`paths[].name`) aller in `config.json` konfigurierten Pfade, ohne die zugehörigen Dateisystem-Pfade.
- **`GET /manifest`** – liefert Agents, Tasks und Pfade (inkl. deren Commands/Hosted-Einträgen) gebündelt in einem Aufruf. Siehe Abschnitt "Manifest (GET /manifest)".
- **`GET /files/<pathName>`** – liefert nur die Namen (`paths[].hosted[].name`) aller hosted-Einträge des Pfads (404, falls `pathName` unbekannt).
- **`GET /files/<pathName>/<hostedName>`** – bei `type: "file"` lädt die Datei direkt herunter; bei `type: "path"` liefert eine Liste der Dateinamen, die direkt (nicht rekursiv) im Verzeichnis liegen (404, falls `pathName`/`hostedName` unbekannt oder die Datei/das Verzeichnis nicht mehr existiert).
- **`GET /files/<pathName>/<hostedName>/<fileName>`** – lädt eine einzelne Datei aus einem `type: "path"`-Verzeichnis herunter (404, falls `hostedName` vom `type: "file"` ist oder `fileName` nicht existiert; 400 bei ungültigem Dateinamen).
- **`GET /paths/<pathName>/commands`** – liefert alle konfigurierten Commands (`paths[].commands[]`) dieses Pfads (404, falls `pathName` unbekannt). Siehe Abschnitt "Pfad-Commands (paths[].commands)".
- **`POST /paths/<pathName>/commands/<key>`** – führt den Command aus, im Dateisystem-Verzeichnis des Pfad-Eintrags als `cwd` (404, falls `pathName`/`key` unbekannt). Siehe Abschnitt "Pfad-Commands (paths[].commands)".

**Request-Body (beide POST-Routen):**

```json
{ "command": "mache irgendwas cooles", "path": "myapp", "model": "opus" }
```

- `command` (String, Pflicht) – der Prompt.
- `path` (String, Pflicht) – Name eines Eintrags aus `config.json`s `paths`-Array (`paths[].name`, z. B. `{ "name": "myapp", "path": "/my/path" }`). Der zugehörige Dateisystem-Pfad wird serverseitig aufgelöst und als Arbeitsverzeichnis (`cwd`) für den `claude`-Prozess verwendet – der tatsächliche Pfad wird nie direkt im Request angegeben, nur der Name. 404, falls kein Eintrag mit diesem Namen existiert.
- `model` (String, optional) – überschreibt das `model` aus `config.json` für diesen einen Aufruf, sonst wird `agent.model` verwendet.

**Ablauf eines POST-Requests:**

1. Body wird validiert (400 bei fehlendem/leerem `command`/`path` oder ungültigem JSON), Agent wird aufgelöst (404, falls unbekannt), `path`-Name wird gegen `config.json`s `paths[]` aufgelöst (404, falls unbekannt).
2. Eine `id` (`crypto.randomUUID()`) wird generiert, sofort eine Zeile in `t_commands` mit `status: "running"` angelegt (inkl. des aufgelösten Dateisystem-Pfads).
3. Response **sofort**: `202 { "id": "<uuid>" }` – der Request wartet nicht auf das Ergebnis.
4. `claude --model <model> --append-system-prompt <contexts> --permission-mode acceptEdits --print "<command>"` läuft im Hintergrund mit dem aufgelösten Pfad als Arbeitsverzeichnis (`cwd`); jeder Output-Chunk (stdout **und** stderr) wird sofort in `t_commands.output` geschrieben (live, nicht erst am Ende).
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

## Google-Authenticator-Schutz (TOTP)

Alle `cl server`-Endpunkte verlangen einen gültigen TOTP-Code (Google Authenticator, RFC 6238, 6-stellig, 30-Sekunden-Schritt) im Header `X-TOTP-Code` – **mit Ausnahme** der beiden Setup-Endpunkte. Es kann jeweils nur **ein** Authenticator gleichzeitig aktiv sein.

**Einrichtung (nur aus dem lokalen Netz erreichbar, sonst `404`):**

1. **`POST /auth/setup`** – erzeugt ein neues, noch unbestätigtes Secret und liefert `{ "secret": "...", "otpauthUrl": "otpauth://totp/..." }`. `secret` kann manuell in Google Authenticator eingegeben werden, `otpauthUrl` eignet sich zum Erzeugen eines QR-Codes für den Scan-Import. Schlägt mit `409` fehl, solange bereits ein Authenticator **aktiv** ist (dann muss dieser zuerst per CLI entfernt werden, siehe unten). Ein erneuter Aufruf, solange das Setup noch nicht bestätigt ist, ersetzt das vorherige unbestätigte Secret durch ein neues.
2. **`POST /auth/setup/confirm`** – Body `{ "code": "123456" }`, der aktuelle Code aus Google Authenticator für das per Schritt 1 erzeugte Secret. Bei korrektem Code (`200`) wird der Authenticator aktiv geschaltet; bei falschem Code `401` (Secret bleibt unbestätigt, ein weiterer Versuch ist möglich). `409`, falls bereits ein Authenticator aktiv ist.

Beide Setup-Endpunkte prüfen die Herkunft der Anfrage über `req.socket.remoteAddress` (niemals über spoofbare Header wie `X-Forwarded-For`) gegen private/loopback-Adressbereiche (`127.0.0.0/8`, `::1`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, IPv6-ULA `fc00::/7`, Link-Local `fe80::/10`) – außerhalb dieser Bereiche liefern beide Routen `404`, identisch zu einer unbekannten Route (kein Hinweis auf deren Existenz).

**Schutz aller übrigen Endpunkte:** Jeder Request ohne oder mit ungültigem `X-TOTP-Code`-Header erhält `401`. Es gibt bewusst **keine Replay-Sperre** pro Code – derselbe (noch gültige) Code kann für mehrere Requests innerhalb desselben 30-Sekunden-Fensters wiederverwendet werden, da TOTP hier als reine Zugriffsschranke für ein persönliches Tool dient und nicht als Einmal-Login; eine Replay-Sperre würde legitime, schnell aufeinanderfolgende API-Aufrufe mit demselben Code sonst blockieren. Zur Absicherung gegen Uhr-Drift wird zusätzlich zum aktuellen Zeitfenster je ein Schritt davor/danach akzeptiert.

**Entfernen (ausschließlich per CLI, kein Server-Endpunkt):** `cl totp remove` löscht das aktive/ausstehende Secret aus der Datenbank. Absicht: Ein Angreifer, der bereits vollen HTTP-Zugriff auf den Server hätte, könnte sonst den eigenen Authenticator entfernen und einen neuen einrichten. Da der Removal-Command nie als HTTP-Route exposed ist, erfordert das Entfernen zwingend Shell-Zugriff auf die Maschine, auf der `cl server` läuft.

Implementiert in `src/totp.ts` (Base32-En-/Decoding, `generateSecret`, `generateTotp`, `verifyTotp` mit Zeitfenster-Toleranz, `buildOtpAuthUrl`), `src/network.ts` (`isLocalNetworkAddress`), `src/db.ts` (Tabelle `t_totp`, Single-Row via `CHECK (id = 1)`: `getTotpSecret`/`setPendingTotpSecret`/`confirmTotpSecret`/`deleteTotpSecret`) und `src/server.ts` (Routing, `isRequestAuthorized()`). CLI-Command `cl totp remove` in `src/index.ts`.

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

**`POST /paths/<pathName>/commands/<key>`** startet den Command headless im Hintergrund (404, falls `pathName`/`key` unbekannt), analog zu den Agent-/Task-Commands: sofortige Antwort `202 { "id": "<uuid>" }`, Live-Output + Status über das bestehende `GET /state/<id>` (gemeinsame `t_commands`-Tabelle; `model` ist bei Pfad-Commands `"-"`, da kein LLM beteiligt ist).

Implementiert in `src/config.ts` (`PathCommandEntry`, `listPathCommands`, `resolvePathCommand`), `src/launch.ts` (`runShellCommand`) und `src/server.ts` (Routing, Wiederverwendung von `t_commands` für Status-Tracking).

## Manifest (`GET /manifest`)

Liefert die gesamte per `config.json` gesteuerte Oberfläche in einem einzigen Aufruf – gedacht als Grundlage für eine spätere, voll dynamische Remote-Steuerung (z. B. per App), ohne dass diese die Struktur von `config.json` kennen oder mehrere Endpunkte kombinieren muss:

```json
{
  "agents": [
    { "command": "cl", "description": "..." },
    { "command": "cl dev", "description": "..." }
  ],
  "tasks": [{ "name": "cleanup", "model": "sonnet" }],
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
- `tasks` – jeder Eintrag aus `config.tasks` reduziert auf `{ name, model }` (ohne `contexts`/`tasks`-Inhalte).
- `paths` – pro Pfad-Eintrag der Name, die vollständigen `commands[]` (wie `GET /paths/<pathName>/commands`) sowie `hosted[]` als `{ name, type }` (wie `GET /files/<pathName>`, aber zusätzlich mit `type`) – **nie** die zugrundeliegenden Dateisystem-Pfade.

Kein Ersatz für die bestehenden Detail-Endpunkte, sondern eine zusätzliche, gebündelte Sicht für eine UI, die alle verfügbaren Befehle, Funktionen (Agents/Tasks) und Dateien dynamisch anzeigen will, ohne für jede neue `config.json`-Ergänzung angepasst werden zu müssen.

Implementiert in `src/config.ts` (`listHostedSummaries`) und `src/server.ts` (`handleGetManifest`).

## Dynamisches `--help`

`cl --help` zeigt zusaetzlich zur `commander`-Standardausgabe eine Liste aller in `config.json` definierten Agents inklusive ihrer `description`:

```
Agents (aus config.json):
  cl            Standard-Agent, gestartet mit `cl` ohne Argument.
  cl mainagent  Gleicher Agent wie der Standard-Agent, aufrufbar per Name.
```

Diese Liste wird bei jedem `--help`-Aufruf frisch aus der aktuellen `config.json` gelesen (`formatAgentsHelp()` in `src/index.ts`, nutzt `listAgents()` aus `src/config.ts`) – ein neuer Agent in `config.json` erscheint automatisch, ohne Code-Aenderung. Bei ungueltiger `config.json` wird statt eines Crashs eine Fehlermeldung im Hilfetext angezeigt.

## Deployment als `cl`

Das Tool wird als eigenständige, ausführbare Datei nach `~/.local/bin/cl` deployed:

- `npm run build` – TypeScript-Compile (`tsc`) mit vollem Type-Checking, Ausgabe nach `dist/`.
- `npm run deploy` – bündelt `dist/index.js` per `esbuild` (alle Dependencies wie `commander` werden eingebettet, da am Zielort kein `node_modules` existiert) und kopiert das Ergebnis nach `~/.local/bin/cl` (ausführbar).
- `npm run release` – führt `build` und `deploy` nacheinander aus.
- `npm run dev` – führt `src/index.ts` direkt über `tsx` aus, ohne vorherigen Compile-Schritt.

## Config/Context-System

`config.json` (Projekt-Root) hat vier Felder:

- `main` – ein Objekt `{ description: string, contexts: string[], model: string }`, der Default-Agent für `cl` ohne Argument.
- `agents` – ein Array benannter Objekte `{ name: string, description: string, contexts: string[], model: string }`, erreichbar über `cl <name>`.
- `databaseDirectory` – Verzeichnis für die SQLite-Datenbank von `cl server` (siehe "HTTP-Server (cl server)"), aktuell `/home/simon/commands`.
- `paths` – ein Array benannter Arbeitsverzeichnisse `{ name: string, path: string, hosted?: { name: string, path: string, type: "path" | "file" }[], commands?: { key: string, command: string, displayName: string, description: string }[] }` (z. B. `{ "name": "myapp", "path": "/my/path" }`), aus dem `cl server`s POST-Routen über den `path`-Namen im Request-Body das Arbeitsverzeichnis (`cwd`) für den `claude`-Prozess auflösen (siehe "HTTP-Server (cl server)"). Das optionale `hosted`-Array definiert benannte Datei-/Verzeichnis-Freigaben, herunterladbar über `GET /files/...` (siehe "HTTP-Server (cl server)") – `hosted[].path` ist relativ zum `path` des Eintrags, nicht absolut. Das optionale `commands`-Array definiert vordefinierte Shell-Befehle, auslösbar über `POST /paths/<pathName>/commands/<key>` (siehe "Pfad-Commands (paths[].commands)").

`description` wird im `--help`-Text pro Agent angezeigt (siehe "Dynamisches --help").

`contexts` referenziert Markdown-Dateien unter `contexts/`:

- `"main"` → `contexts/main.md`
- `"dev/tools"` → `contexts/dev/tools.md` (Unterordner werden 1:1 gespiegelt)

Aufgelöst über `src/config.ts` (`loadConfig()`, `resolveAgent(name)`, `resolveContext(name)`):

- Bevorzugt werden die lokal liegenden Dateien (`config.json`, `contexts/*.md`) gelesen – relevant für `npm run dev`.
- Sind keine lokalen Dateien vorhanden (z. B. beim deployten `cl`-Binary in `~/.local/bin`, wo keine `config.json`/`contexts/` danebenliegen), wird auf eine zur Build-Zeit eingebettete Kopie zurückgegriffen. So funktioniert das Tool unabhängig vom Ausführungsort.
- Bei jedem `dev`/`build` wird die eingebettete Kopie (`src/generated/embedded-context.ts`) frisch aus dem aktuellen Stand von `config.json` + `contexts/**/*.md` generiert.

Wird vom Agent-Start (`cl` / `cl <name>`) genutzt, um Model und System-Prompt des jeweiligen Agents aufzulösen.

## Tests (`npm test`)

`npm test` (= `tsx --test 'src/**/*.test.ts'`) führt die komplette Test-Suite aus – 133 Tests über 9 Dateien, ein File pro Feature-Bereich:

- **`src/config.test.ts`** – Validierung (`parseConfig`: gültige/ungültige Configs, reservierte Agent-/Command-Namen, `hosted`-/`commands`-Einträge), `listAgents`, `listHostedNames`/`resolveHostedEntry`, `listPathCommands`/`resolvePathCommand`, sowie `loadConfig`/`resolveAgent`/`resolveContext`/`resolveTask` gegen echte temporäre Fixtures (sowohl "lokale Dateien vorhanden" als auch "keine lokalen Dateien → Embedded-Fallback").
- **`src/launch.test.ts`** – `buildClaudeArgs`/`buildSystemPrompt`/`buildTaskContent` (reine Funktionen) sowie `runHeadlessCommand`/`runShellCommand` gegen ein Fake-`claude`-Binary bzw. echte Shell-Commands (Output-Streaming, Exit-Codes, Verhalten wenn `claude` fehlt).
- **`src/db.test.ts`** – SQLite-Operationen (`openDatabase`, `insertCommand`/`getCommand`, `updateCommandOutput`, `completeCommand`, `logAccess`, `getTotpSecret`/`setPendingTotpSecret`/`confirmTotpSecret`/`deleteTotpSecret`) gegen echte temporäre `.db`-Dateien.
- **`src/totp.test.ts`** – Base32-En-/Decoding-Rundreise, `generateTotp`/`verifyTotp` (gültiger Code, Zeitfenster-Toleranz, falsches Secret/Format), `buildOtpAuthUrl`.
- **`src/network.test.ts`** – `isLocalNetworkAddress` gegen Loopback, RFC1918-Bereiche, IPv6-ULA/Link-Local, öffentliche Adressen, IPv4-mapped IPv6.
- **`src/server.test.ts`** – `cl server`s HTTP-Endpunkte per echtem `fetch()` gegen einen in-process gestarteten Server (Erfolg, Validierungsfehler, 404s, 401 ohne/mit falschem `X-TOTP-Code`, Live-Status `running` → `completed`, Model-Override, hosted-Datei-Download, hosted-Verzeichnis-Listing, Pfad-Commands).
- **`src/server-auth.test.ts`** – die vollständige TOTP-Setup-Lebensdauer (unbestätigt → 401, Setup → Confirm → aktiv, `409` bei erneutem Setup-Versuch, Code-Wiederverwendbarkeit im selben Zeitfenster).
- **`src/index.test.ts`** – die komplette CLI als Subprozess (`--help`, `--version`, Agent-Start, Model-Override + Headless mit/ohne Prompt-Wert, unbekannter Agent, Startup-Crash bei reserviertem Namen, `cl server`/`cl task`/`cl totp remove` End-to-End inkl. `SIGTERM`-Shutdown).

Kein echter `claude`-Aufruf in den Tests: `src/test-support/mock-claude.ts` erzeugt ein ausführbares Fake-`claude`-Script, das seine Argumente als JSON zurückmeldet und konfigurierbare Output/Exit-Codes liefert. `src/test-support/fixture-config.ts` erzeugt temporäre `config.json`+`contexts/`-Verzeichnisse; `src/config.ts`s `getRootDir()` liest dafür `process.env.CL_ROOT_DIR` (nur für Tests relevant, im Normalbetrieb ungesetzt). `src/test-support/run-cli.ts` spawnt die CLI für Subprozess-Tests.
