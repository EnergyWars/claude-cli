# Kontext: claude-cli

## Zweck

Persönliches CLI-Tool in TypeScript, wird als eigenständige ausführbare Datei `cl` nach `~/.local/bin` deployed.

## Architektur-Entscheidungen

- **Runtime/Module:** Node.js (ESM, `"type": "module"`), TypeScript-Target `ES2023`, `module`/`moduleResolution: NodeNext`.
- **TypeScript-Version:** `^6.0.3` statt der neuesten `7.x` – TypeScript 7 ist der neue native (Go-basierte) Compiler-Rewrite, `typescript-eslint@8` unterstützt ihn noch nicht (Peer-Dep verlangt `<6.1.0`). Sobald `typescript-eslint` TS7 unterstützt, kann angehoben werden.
- **CLI-Framework:** `commander` (Argument-Parsing, `--help`/`--version` automatisch generiert).
- **Dev ohne Compile:** `tsx` führt `src/index.ts` direkt aus.
- **Compile:** `tsc` – volles Type-Checking, Ausgabe nach `dist/` (ESM, referenziert `node_modules` normal).
- **Deploy-Bündelung:** `esbuild` bündelt `dist/index.js` inkl. aller Dependencies (z. B. `commander`) in eine einzelne Datei, da am Zielort `~/.local/bin` kein `node_modules` existiert. `tsc` allein reicht für das Deployment nicht aus.
- **Versionierung:** `src/version.ts` wird vor `dev`/`build` aus `package.json` generiert (`scripts/generate-version.mjs`, via npm `pre`-Hooks). Datei ist generiert und daher in `.gitignore`. Grund: ein direkter JSON-Import von `package.json` würde nach dem Deploy als Einzeldatei zur Laufzeit fehlschlagen (keine `package.json` neben `cl` in `~/.local/bin`).
- **Config/Context-System:** `config.json` (Root) hat zwei Felder: `main` (ein `AgentDefinition`-Objekt `{ contexts: string[], model: string }`, ohne `name`) und `agents` (Array von benannten `AgentConfig`-Objekten `{ name: string, contexts: string[], model: string }`). Jeder Context-Name löst zu einer Markdown-Datei unter `contexts/` auf, z. B. `"main"` → `contexts/main.md`, `"dev/tools"` → `contexts/dev/tools.md`. `src/config.ts` (`loadConfig()`, `resolveAgent(name)`, `resolveContext(name)`) liest bevorzugt die lokal liegenden Dateien (`config.json`/`contexts/*.md`, relativ zum Repo-Root über `import.meta.url` aufgelöst) und fällt zurück auf `EMBEDDED_CONFIG`/`EMBEDDED_CONTEXTS` aus `src/generated/embedded-context.ts`, falls die lokalen Dateien fehlen (Fall des deployten Einzeldatei-Binaries ohne begleitende `config.json`/`contexts/`). `src/generated/embedded-context.ts` wird vor `dev`/`build` aus `config.json` + allen `contexts/**/*.md` generiert (`scripts/generate-context-bundle.mjs`, via npm `pre`-Hooks), ist generiert und in `.gitignore`. Dadurch ist das Ergebnis jedes Builds (inkl. `esbuild`-Bundle) unabhängig vom Ausführungsort funktionsfähig.
- **Agent-Start (`cl [agent]`):** `src/index.ts` deklariert ein optionales positionales Argument `[agent]` via `commander` und ruft im `.action()`-Handler `launchAgent(agent)` auf. `resolveAgent(name)` in `src/config.ts` gibt bei `name === undefined` `config.main` zurück, sonst den Eintrag aus `config.agents` mit passendem `name` (Fehler, falls nicht gefunden). `src/launch.ts` (`launchAgent(name)`) baut aus den `contexts` der aufgelösten `AgentDefinition` den System-Prompt (Inhalte verkettet mit `\n\n`) und startet `claude` (Claude Code CLI) per `child_process.spawn` mit `stdio: 'inherit'` (voll interaktiv im Vordergrund, kein `--print`). Flags: `--model <agent.model>`, `--append-system-prompt <systemPrompt>`, `--permission-mode acceptEdits` (Read ist in Claude Code ohnehin promptlos; `acceptEdits` deckt Write/Edit ab, ohne z. B. Bash pauschal zu bypassen). `cl` ohne Argument → `main`-Agent; `cl <name>` → benannter Agent aus `agents[]`; `--help`/`--version` laufen weiterhin über `commander` (eager options, vor der Action).
- **Linting:** ESLint (Flat Config, `eslint.config.js`) mit `typescript-eslint` `strictTypeChecked` + `stylisticTypeChecked`, typisiert nur für `src/**/*.ts` (eigenes `project`). `eslint.config.js` und `scripts/*.mjs` laufen nur mit `js.configs.recommended` (kein Type-Checking nötig).
- **Formatierung:** Prettier, per `eslint-config-prettier` von ESLint-Stylistik entkoppelt.

## Dateistruktur

| Datei/Verzeichnis                      | Zweck                                                                                       |
| -------------------------------------- | ------------------------------------------------------------------------------------------- |
| `src/index.ts`                         | CLI-Einstiegspunkt (Shebang, `commander`-Setup, `--help`/`--version`)                       |
| `src/version.ts`                       | Generiert, nicht versioniert – Versions-Konstante                                           |
| `src/config.ts`                        | `loadConfig()`/`resolveAgent(name)`/`resolveContext(name)` – lokal-first, embedded-fallback |
| `src/launch.ts`                        | `launchAgent(name)` – startet `claude` interaktiv mit Model/System-Prompt/Permissions       |
| `src/generated/embedded-context.ts`    | Generiert, nicht versioniert – eingebettete `config.json` + Contexts                        |
| `config.json`                          | `main: { contexts, model }`, `agents: { name, contexts, model }[]` – Quelle der Wahrheit    |
| `contexts/*.md`                        | Context-Inhalte, Name ↔ Pfad 1:1 (`"dev/tools"` → `contexts/dev/tools.md`)                  |
| `scripts/generate-version.mjs`         | Schreibt `src/version.ts` aus `package.json` vor `dev`/`build`                              |
| `scripts/generate-context-bundle.mjs`  | Schreibt `src/generated/embedded-context.ts` vor `dev`/`build`                              |
| `scripts/deploy.sh`                    | Bündelt `dist/index.js` mit `esbuild` und kopiert nach `~/.local/bin/cl`                    |
| `tsconfig.json`                        | Strikte Compiler-Optionen, `rootDir: src`, `outDir: dist`                                   |
| `eslint.config.js`                     | ESLint Flat Config                                                                          |
| `.prettierrc.json` / `.prettierignore` | Prettier-Konfiguration                                                                      |
| `dist/`                                | Build-Output (gitignored)                                                                   |

## npm-Scripts

- `npm run dev` – führt `src/index.ts` direkt via `tsx` aus (kein Compile).
- `npm run build` – `tsc`-Compile nach `dist/`.
- `npm run deploy` – bündelt (`esbuild`) und kopiert nach `~/.local/bin/cl`.
- `npm run release` – `build` + `deploy` nacheinander.
- `npm run lint` / `npm run format` – ESLint / Prettier.

## Feature-Implementierungsstatus

Siehe `FEATURES.md`. Aktuell umgesetzt:

- CLI-Grundgerüst mit `--help`/`--version` – `src/index.ts`.
- Deployment-Pipeline (`dev`/`build`/`deploy`/`release`) – `package.json`, `scripts/deploy.sh`, `scripts/generate-version.mjs`.
- Config/Context-System (lokal-first mit Embedded-Fallback) – `config.json`, `contexts/`, `src/config.ts`, `scripts/generate-context-bundle.mjs`.
- `cl [agent]` startet Claude Code interaktiv mit dem `main`-Agent (ohne Argument) oder einem benannten Agent aus `agents[]` (Model, System-Prompt aus Contexts, `acceptEdits`-Permissions) – `src/launch.ts`, `src/index.ts`, `src/config.ts`.

Noch offen: fachliche Subcommands über den Agent-Start hinaus.
