# Features

## CLI-Grundgerüst

Einstiegspunkt (`src/index.ts`) mit `commander` als Argument-Parser. Bietet:

- `-h, --help` – zeigt Nutzung, Optionen, das `[agent]`-Argument und ein Beispiel.
- `-v, --version` – zeigt die aktuelle Version (aus `package.json`, zur Build-Zeit generiert).
- `[agent]` (optionales positionales Argument) – siehe "Agent-Start".

Weitere fachliche Subcommands sind noch nicht implementiert – das Grundgerüst ist bewusst minimal und bereit für spätere Erweiterung über `program.command(...)`.

## Agent-Start (`cl` / `cl <name>`)

`cl` startet `claude` (Claude Code) voll interaktiv im Vordergrund (`stdio: 'inherit'`, keine Pipe/kein `--print`) – als würde man `claude` direkt aufrufen, nur vorkonfiguriert mit einem Agent aus `config.json`:

- **`cl`** (ohne Argument) → der `main`-Eintrag in `config.json`.
- **`cl <name>`** → der Eintrag mit `name === "<name>"` aus dem `agents`-Array in `config.json`. Namen sind frei wählbar (z. B. `name: "iwan"` → `cl iwan`); unbekannte Namen brechen mit Fehlermeldung ab.

Für den aufgelösten Agent gilt:

- **Model:** dessen `model` (aktuell z. B. `"sonnet"`) → `--model`.
- **System-Prompt:** Inhalt aller Dateien, die in dessen `contexts`-Array referenziert sind, verkettet → `--append-system-prompt`.
- **Permissions:** `--permission-mode acceptEdits` – Read ist in Claude Code ohnehin promptlos, `acceptEdits` erlaubt zusätzlich Write/Edit ohne Rückfrage (andere Tools wie Bash fragen weiterhin nach).

Implementiert in `src/launch.ts` (`launchAgent(name)`), aufgerufen aus dem `commander`-Action-Handler in `src/index.ts` mit dem optionalen `[agent]`-Argument.

## Deployment als `cl`

Das Tool wird als eigenständige, ausführbare Datei nach `~/.local/bin/cl` deployed:

- `npm run build` – TypeScript-Compile (`tsc`) mit vollem Type-Checking, Ausgabe nach `dist/`.
- `npm run deploy` – bündelt `dist/index.js` per `esbuild` (alle Dependencies wie `commander` werden eingebettet, da am Zielort kein `node_modules` existiert) und kopiert das Ergebnis nach `~/.local/bin/cl` (ausführbar).
- `npm run release` – führt `build` und `deploy` nacheinander aus.
- `npm run dev` – führt `src/index.ts` direkt über `tsx` aus, ohne vorherigen Compile-Schritt.

## Config/Context-System

`config.json` (Projekt-Root) hat zwei Felder:

- `main` – ein Objekt `{ contexts: string[], model: string }`, der Default-Agent für `cl` ohne Argument.
- `agents` – ein Array benannter Objekte `{ name: string, contexts: string[], model: string }`, erreichbar über `cl <name>`.

`contexts` referenziert Markdown-Dateien unter `contexts/`:

- `"main"` → `contexts/main.md`
- `"dev/tools"` → `contexts/dev/tools.md` (Unterordner werden 1:1 gespiegelt)

Aufgelöst über `src/config.ts` (`loadConfig()`, `resolveAgent(name)`, `resolveContext(name)`):

- Bevorzugt werden die lokal liegenden Dateien (`config.json`, `contexts/*.md`) gelesen – relevant für `npm run dev`.
- Sind keine lokalen Dateien vorhanden (z. B. beim deployten `cl`-Binary in `~/.local/bin`, wo keine `config.json`/`contexts/` danebenliegen), wird auf eine zur Build-Zeit eingebettete Kopie zurückgegriffen. So funktioniert das Tool unabhängig vom Ausführungsort.
- Bei jedem `dev`/`build` wird die eingebettete Kopie (`src/generated/embedded-context.ts`) frisch aus dem aktuellen Stand von `config.json` + `contexts/**/*.md` generiert.

Wird vom Agent-Start (`cl` / `cl <name>`) genutzt, um Model und System-Prompt des jeweiligen Agents aufzulösen.
