# commander

Native Android-App (Kotlin/Compose), Fernsteuerungs-Client für `cl server` (siehe `../CLAUDE.md`, `../context.md`). Eigenständiges Gradle-Projekt innerhalb des `claude-cli`-Repos – die globalen WaffleHQ-Android-Basisregeln (`~/IdeaProjects/CLAUDE.md`) gelten hier **wieder**, im Gegensatz zum restlichen (TypeScript-)Repo.

## Ausnahmen von den globalen Regeln

- Aus der Basisvorlage (`/home/simon/.claude/development/base-project`) übernommen, aber **ohne** den 33-teiligen Design-Showcase (`ui/theme/showcase/**`), Example-Screens und das Feature-Files-Konzept – bewusst entfernt, um die App auf ihren eigentlichen Zweck zu fokussieren. Theme/Tokens/Components blieben vollständig erhalten.
- Statt `androidx.security:security-crypto` (EncryptedSharedPreferences) wird das TOTP-Secret direkt über einen AES-256-GCM-Schlüssel im Android-Keystore verschlüsselt (`data/crypto/KeystoreCipher.kt`) – die Jetpack-Security-APIs sind seit `1.1.0-beta01` (Juni 2025) zugunsten des direkten Keystore-Zugriffs deprecated, ihre Verwendung würde gegen die MUSS-Regel „keine deprecated Inhalte“ verstoßen.
- Ausnahme von "immer neueste Tool-Version": OkHttp, Hilt und `androidx.hilt:hilt-navigation-compose` sind **nicht** auf ihre jeweils neueste Version gepinnt, sondern auf die neueste Version, die noch mit unserem `AGP 8.9.0`/`compileSdk 35`-Stack kompatibel ist (neuere Releases verlangen AGP ≥9/compileSdk ≥36–37). Details inkl. der genauen Versionsgrenzen: `context.md`. Sobald AGP/compileSdk der Vorlage angehoben werden, sollten diese drei mit angehoben werden.

Details zu Architektur und Dateistruktur: siehe `context.md`.
