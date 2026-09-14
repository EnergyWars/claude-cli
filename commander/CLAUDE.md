# commander

Native Android-App (Kotlin/Compose), Fernsteuerungs-Client für `cl server` (siehe `../CLAUDE.md`, `../context.md`). Eigenständiges Gradle-Projekt innerhalb des `claude-cli`-Repos – die globalen WaffleHQ-Android-Basisregeln (`~/IdeaProjects/CLAUDE.md`) gelten hier **wieder**, im Gegensatz zum restlichen (TypeScript-)Repo.

## Ausnahmen von den globalen Regeln

- Aus der Basisvorlage (`/home/simon/.claude/development/base-project`) übernommen, aber **ohne** den 33-teiligen Design-Showcase (`ui/theme/showcase/**`), Example-Screens und das Feature-Files-Konzept – bewusst entfernt, um die App auf ihren eigentlichen Zweck zu fokussieren. Theme/Tokens/Components blieben vollständig erhalten.
- Statt `androidx.security:security-crypto` (EncryptedSharedPreferences) wird das TOTP-Secret direkt über einen AES-256-GCM-Schlüssel im Android-Keystore verschlüsselt (`data/crypto/KeystoreCipher.kt`) – die Jetpack-Security-APIs sind seit `1.1.0-beta01` (Juni 2025) zugunsten des direkten Keystore-Zugriffs deprecated, ihre Verwendung würde gegen die MUSS-Regel „keine deprecated Inhalte“ verstoßen.
- AGP `8.13.2` (Kotlin `2.3.20`/KSP `2.3.11`), analog zur Basisvorlage – `compileSdk` bleibt bewusst bei `35` (die Vorlage beweist, dass AGP 8.13.2 ohne compileSdk-Anhebung funktioniert).
- Ausnahme von "immer neueste Tool-Version": OkHttp (`5.3.2`), Hilt (`2.58`) und `androidx.hilt:hilt-navigation-compose` (`1.3.0`) sind **nicht** auf die jeweils absolut neueste Maven-Version gepinnt, sondern auf die neueste Version, die noch mit unserem `AGP 8.13.2`/`compileSdk 35`-Stack verifiziert kompatibel ist. Alle drei liegen bereits vor den entsprechenden Versionen in `/home/sklein/IdeaProjects/base-project` und `/home/sklein/IdeaProjects/periodical` (Stand 2026-09-14). Details: `context.md`. Vor einem weiteren Bump über die dort verifizierten Referenzversionen hinaus: Kompatibilität mit AGP 8.13.2/compileSdk 35 prüfen (Build kann in dieser Umgebung nicht ausgeführt werden).
- Gradle-Wrapper ist auf die stabile Version `8.14.5` gepinnt (nicht auf einen neueren Milestone-/Preview-Build wie den `9.0-milestone-1`, den `base-project`/`periodical` verwenden) – `AGP 8.13.2` verlangt offiziell mindestens Gradle `8.13`, `8.14.5` ist die neueste stabile Version der 8.x-Reihe. Ein Gradle-9.0-Milestone-Build ist kein stabiles Release und wird bewusst nicht übernommen. Sobald ein stabiles Gradle-9.x-Release erscheint und AGP entsprechend mitzieht, kann der Wrapper mit angehoben werden.

Details zu Architektur und Dateistruktur: siehe `context.md`.
