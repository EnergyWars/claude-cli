# claude-cli

TypeScript-CLI-Tool (Node.js), kein Android-Projekt – die Vorgaben aus der übergeordneten `~/IdeaProjects/CLAUDE.md` (WaffleHQ-Android-Basisprojekt) gelten hier **nicht**.

## Ausnahme von der globalen Regel "immer neueste Tool-Version"

`typescript` ist auf `^6.0.3` gepinnt statt der aktuell neuesten `7.x`. TypeScript 7 ist der neue native (Go-basierte) Compiler-Rewrite; `typescript-eslint@8` (Peer-Dep `<6.1.0`) unterstützt ihn noch nicht. Sobald `typescript-eslint` TS7 unterstützt, anheben.

Details zu Architektur und Dateistruktur: siehe `context.md`.
