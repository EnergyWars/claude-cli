# app-getter

Native Android-App (Kotlin/Compose), schlanker Installer-Client für `cl server`s Collection-Feature (siehe `../CLAUDE.md`, `../context.md`). Eigenständiges Gradle-Projekt innerhalb des `claude-cli`-Repos – die globalen WaffleHQ-Android-Basisregeln (`~/IdeaProjects/CLAUDE.md`) gelten hier **wieder**, im Gegensatz zum restlichen (TypeScript-)Repo.

## Zweck

Findet automatisch einen `cl server` im lokalen Netz (gleiche Discovery-Technik wie `commander`), listet alle über `GET /collections` gesammelten Dateien (i. d. R. APKs) auf und installiert sie per Tap automatisch (Download + Install-Intent). Komplett unauthentifiziert – bewusst kein Login/TOTP/JWT, da `/status`, `/collections` und `/collections/get/*` serverseitig öffentlich sind.

## Ausnahmen von den globalen Regeln

- **Aus `commander/` abgeleitet, nicht aus der rohen Basisvorlage** (`~/.claude/development/base-project`): commander hatte den WaffleHQ-Pflicht-Trim (kein 33-teiliger Design-Showcase, keine Feature-Files) bereits vorgenommen und enthielt exakt die benötigten Bausteine (Theme/Tokens/Components, Discovery, FileProvider-Install-Logik, Gradle-Wrapper-Pins) – von dort übernommen statt erneut aus der Basisvorlage zu trimmen.
- **Kein Room/lokale Datenbank**: es gibt keine lokal zu persistierenden Entitäten (nur Theme-Mode + optionaler Host/Port-Override in DataStore-Settings) – anders als die Basisvorlagen-Checkliste ("`PlaceholderEntity` ersetzen") vorsieht, wurde Room komplett entfernt statt eine Platzhalter-Entität zu ersetzen.
- **Kein Login/TOTP/JWT/Session-Konzept**: anders als `commander` braucht app-getter keine Authentifizierung, da es ausschließlich die unauthentifizierten `GET /status`/`GET /collections`/`GET /collections/get/:name`-Endpunkte von `cl server` nutzt.
- Gleiche Versions-Pins wie `commander` (AGP `8.9.0`, Gradle-Wrapper `8.11.1`) – aus denselben Gründen (siehe `commander/CLAUDE.md`): `AGP 8.9.0` ist offiziell erst ab Gradle `8.11.1` getestet.
- Launcher-Icon ist ein generischer Platzhalter (einfacher Kreis, unverändert aus der Basisvorlage übernommen) – noch nicht durch ein eigenes Icon ersetzt.

## Build

`gradle build`/`assembleDebug`/`assembleRelease` **niemals** ohne explizite Erlaubnis ausführen – jedes Mal fragen, auch bei expliziter Aufforderung.

Details zu Architektur und Dateistruktur: siehe `context.md`.
