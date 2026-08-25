# Kontext: commander

## Aufbau eines Projektes

Siehe `~/.claude/CLAUDE.md` ("Aufbau eines Projektes") – gilt hier unverändert: `FEATURES.md` (alle Features vollständig, selbstpflegend), `.gitignore` (Android-spezifisch, siehe unten), `context.md` (diese Datei, immer aktueller Stand ohne History), `CLAUDE.md` (Ausnahmen von globalen Regeln).

## Zweck

Native Android-App (Kotlin/Compose), Fernsteuerungs-Client für `cl server` aus dem übergeordneten `claude-cli`-Repo (`../src/server.ts`, `../openapi.json`). Eigenständiges Gradle-Projekt (`rootProject.name = "Commander"`, `applicationId`/`namespace = com.wafflehq.commander`), aufgesetzt per `/base-project`-Skill aus der WaffleHQ-Basisvorlage.

## Architektur-Entscheidungen

- **Basis:** AGP 8.9.0, JVM 17, `compileSdk 35`, `minSdk 26`, `targetSdk 35`, Jetpack Compose Material 3, Single-Activity (`MainActivity`), Compose Navigation, Hilt DI, Room, DataStore Preferences, 5-Hue-Ramp-Theme (`ui/theme/`) – aus der Basisvorlage übernommen. `compileSdk`/AGP wurden bewusst **nicht** angehoben.
- **Gradle-Wrapper `8.11.1`**, **Kotlin `2.2.21`**/**KSP `2.2.21-2.0.5`**/**Room `2.8.4`**/**Hilt `2.58`**/**`hiltNavigationCompose 1.3.0`**: siehe Begründung der Versionsgrenzen in `CLAUDE.md`. `ui/navigation/HiltViewModels.kt` kapselt den deprecated `androidx.hilt.navigation.compose.hiltViewModel()`-Aufruf hinter einem eigenen `hiltViewModel()`; alle Screens importieren diesen statt der `androidx`-Variante direkt.
- **`com.squareup.okhttp3:okhttp:5.3.2`**, **`mockwebserver3:5.3.2`** (Test), **`kotlinx-serialization-json:1.7.3`**, **`io.mockk:mockk:1.13.12`** (Test, für `ConnectionGateViewModelTest`s `SettingsRepository`-Fake – Konstruktor braucht einen echten `Context`, daher nicht per Hand fakebar).
- **Kein `androidx.security:security-crypto`:** TOTP-Secret wird über `data/crypto/KeystoreCipher.kt` (AES-256-GCM, Android-Keystore) verschlüsselt, nicht über `EncryptedSharedPreferences` (seit `1.1.0-beta01` deprecated).
- **TOTP-Client (`data/totp/TotpGenerator.kt`):** Clean-Room-Kotlin-Implementierung von RFC 6238/4226, bit-exakt kompatibel zu `../src/totp.ts`.
- **Auto-Discovery (`data/discovery/NetworkDiscovery.kt`):** `/24`-Subnetz-Scan gegen `GET /status`, `raceFirstMatch()`/`subnetHosts()` als reine, testbare Funktionen getrennt vom Android-spezifischen `localIpv4Address()`.
- **API-Client (`data/api/ClServerApi.kt`):** Dünner OkHttp-Wrapper. Unauthentifizierte Methoden nehmen Host/Port explizit entgegen (Pairing/Login). Authentifizierte Methoden lesen die aktuelle `Connection`/`Session` aus `ConnectionSource`, senden `Authorization: Bearer <JWT>`. **Kein** `getPaths()`/`getPathCommands()`: `GET /manifest` liefert Agents + Pfade (inkl. Commands/Hosted-Einträgen) gebündelt. Downloads streamen direkt in eine Zieldatei, Zieldateiname aus `Content-Disposition`. Fehler einheitlich als `ApiException(httpCode, message)`.
- **Login/Session:** JWT-basiert (`data/connection/ConnectionRepository.kt`, `Session`/`AuthSession`), `ui/login/LoginScreen.kt` fragt den 6-stelligen TOTP-Code ab und tauscht ihn gegen ein Token. `ConnectionGateViewModel` (`ui/navigation/`) kombiniert den Session-Status **und** `SettingsRepository.selectedProjectName` zu einem `GateState` (`NoConnection`/`NeedsLogin`/`Ready(hasSelectedProject: Boolean)`) und bestimmt so den Navigations-Startpunkt.
- **Ausgewähltes Projekt (`data/settings/SettingsRepository.kt`):** DataStore-Key `selected_project` – merkt sich den zuletzt gewählten `manifest.paths[].name` app-weit, bis der Nutzer ihn über das Projekt-Dropdown im Hub ändert oder sich abmeldet (`SettingsViewModel.disconnect()` löscht ihn mit).
- **Dev-Kontexte (`data/context/DevContextRepository.kt`, `data/db/AppDatabase.kt`):** Room-Tabelle `dev_context` (`id`/`name`/`value`) – frei anlegbare Prompt-Textbausteine, verwaltet unter Settings → Kontexte. Beim Agent-Start wird der gewählte Kontext-Wert per String-Konkatenation (`"$value\n\n$prompt"`, siehe `ui/run/RunAgentViewModel.kt#buildAgentCommand`) vor den Prompt gehängt, bevor `POST /<agent>` aufgerufen wird. `AppDatabase` ist auf `version = 2`, `fallbackToDestructiveMigration(dropAllTables = true)` (`di/AppModule.kt`) – keine eigene Migration nötig, da rein lokale Presets eines persönlichen Tools.
- **Datei-Downloads/APK-Install (`data/download/HostedFileDownloader.kt`):** Downloads landen unter `getExternalFilesDir(DIRECTORY_DOWNLOADS)`, Öffnen/Teilen über `FileProvider`. Endet der tatsächliche Dateiname (aus `Content-Disposition`) auf `.apk` (reine Funktion `isApkFileName`, testbar ohne Android-Kontext), liefert `openOrInstallIntent()` statt des generischen Öffnen-Intents einen `ACTION_VIEW`-Intent mit MIME `application/vnd.android.package-archive` – Android zeigt dabei bei Bedarf selbst den "Unbekannte Quellen erlauben"-Dialog (`REQUEST_INSTALL_PACKAGES`-Permission im Manifest, kein Runtime-Request nötig). Verwendet sowohl in `ui/downloads/DownloadsScreen.kt` als auch in `ui/command/CommandDetailScreen.kt` (ein per Befehl gebautes APK installiert sich genauso automatisch).
- **Navigation (`ui/navigation/AppNavHost.kt`, `Routes`):** Linearer Fluss ohne Hamburger-Menü. `NoConnection → SETUP`, `NeedsLogin → LOGIN`, `Ready` ohne gemerktes Projekt `→ PROJECT_SELECT` (reine Namensliste aus `manifest.paths`, Tap wählt + persistiert), `Ready` mit gemerktem Projekt `→ PROJECT_HOME` (Hub: Projekt-Dropdown zum Wechseln oben, Zahnrad zu Settings, darunter genau vier Einträge: **Befehle**, **Downloads**, **Dev-Agent**, **Tickets**). Jeder der vier Bereiche ist ein eigener, fokussierter Screen ohne Querverweise auf die anderen drei:
  - `COMMANDS` (`ui/commands/`): nur `path.commands`, Tap führt aus und öffnet `COMMAND_DETAIL`.
  - `DOWNLOADS` (`ui/downloads/`): nur `path.hosted` (Ordner aufklappbar, Datei-Download inkl. APK-Auto-Install).
  - `AGENTS` (`ui/agents/`): Liste `manifest.agents` (`command`+`description`), Tap öffnet `AGENT_RUN`.
  - `AGENT_RUN` (`ui/run/RunAgentScreen.kt`, in-place aus dem früheren "Agent starten" umgebaut): Agent und Pfad sind fix (aus der Navigation, nur Anzeige, keine Dropdowns mehr), dazu Prompt-Feld, optionales Kontext-Dropdown (`data/context`) und optionales Model-Dropdown (Default vorausgewählt).
  - `TICKETS`/`TICKET_DETAIL` (`ui/tickets/`): wie zuvor, aber `pathName` ist jetzt **Pflichtparameter** – der frühere globale, pfadlose Modus (alle Pfade gemischt, Pfad-Dropdown beim Anlegen) wurde entfernt, da nicht mehr erreichbar.
  - `SETTINGS`/`SETTINGS_DISPLAY` unverändert; neu `SETTINGS_CONTEXTS`/`SETTINGS_CONTEXT_NEW`/`SETTINGS_CONTEXT_EDIT` (`ui/settings/contexts/`) für die CRUD-Verwaltung der Dev-Kontexte.
  Entfernt: das frühere Dashboard (`ui/home/`, alle Agents/Pfade/Tickets-Zähler/Verlauf gemischt auf einer Seite), `ui/pathdetail/` (Commands+Hosted+Agent-Start+Tickets-Button gemeinsam auf einer Seite), der lokale Command-Verlauf (`data/history/*`, war nur auf dem alten Dashboard sichtbar), `ui/components/AppDrawer.kt`/`AppHeader.kt` (Hamburger-Menü, dadurch toter Code).
- **DI (`di/AppModule.kt`, `di/BindsModule.kt`):** `AppDatabase`/`DevContextDao` über `@Provides` (Room). Alle übrigen Repositories nutzen reine `@Inject`-Konstruktor-Injection.

## Dateistruktur

| Datei/Verzeichnis                                                    | Zweck                                                                   |
| --------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `data/totp/TotpGenerator.kt`                                          | RFC 6238/4226 TOTP-Client                                                |
| `data/crypto/KeystoreCipher.kt`                                       | AES-256-GCM via Android-Keystore                                         |
| `data/connection/ConnectionRepository.kt`                             | Verbindungsspeicher (Host/Port/Secret/Session), `ConnectionSource`       |
| `data/api/ApiModels.kt`, `data/api/ClServerApi.kt`                    | `@Serializable`-Modelle + OkHttp-Client für `cl server`                  |
| `data/discovery/NetworkDiscovery.kt`                                  | Auto-Discovery: `/24`-Subnetz nach `GET /status` scannen                 |
| `data/db/AppDatabase.kt`                                              | Room: `DevContextEntity`/`DevContextDao`                                 |
| `data/context/DevContextRepository.kt`                                | Dev-Kontext-Presets (CRUD)                                               |
| `data/download/HostedFileDownloader.kt`                               | Download + Öffnen/Teilen/APK-Install (FileProvider)                      |
| `data/settings/SettingsRepository.kt`                                 | Theme-Mode + ausgewähltes Projekt (DataStore)                            |
| `di/AppModule.kt`, `di/BindsModule.kt`                                | Hilt-Module                                                              |
| `ui/setup/*`, `ui/login/*`                                            | Verbindungsaufbau/Pairing, Login (TOTP → JWT)                            |
| `ui/projectselect/*`                                                  | Projektauswahl (reine Namensliste)                                       |
| `ui/projecthome/*`                                                    | Projekt-Hub (Projekt-Dropdown + 4 Einträge)                              |
| `ui/commands/*`                                                       | Befehle eines Projekts                                                   |
| `ui/downloads/*`                                                      | Downloadbare Dateien eines Projekts                                      |
| `ui/agents/*`                                                         | Agenten-Liste eines Projekts                                             |
| `ui/run/*`                                                            | Agent ausführen (fixer Agent+Pfad, Kontext+Model optional)               |
| `ui/command/*`                                                        | Live-Status/Output eines Commands                                        |
| `ui/tickets/*`                                                        | Ticket-Liste (projektgebunden) + Ticket-Detail                           |
| `ui/settings/*`, `ui/settings/contexts/*`                             | Theme-Toggle, Verbindung trennen, Dev-Kontexte verwalten                 |
| `ui/navigation/AppNavHost.kt`, `ConnectionGateViewModel.kt`           | `Routes`, `NavHost`, Gate-State (Verbindung + Projekt)                   |
| `app/src/main/res/xml/file_paths.xml`                                 | FileProvider-Pfad-Spezifikation                                          |
| `app/src/test/.../data/totp/TotpGeneratorTest.kt`                     | RFC-6238-Testvektoren                                                    |
| `app/src/test/.../data/api/ClServerApiTest.kt`                        | HTTP-Vertrag gegen MockWebServer                                         |
| `app/src/test/.../data/discovery/NetworkDiscoveryTest.kt`             | `subnetHosts`/`raceFirstMatch` als reine Funktionen                      |
| `app/src/test/.../data/download/HostedFileDownloaderTest.kt`          | `isApkFileName`                                                          |
| `app/src/test/.../ui/run/RunAgentCommandTest.kt`                      | `buildAgentCommand` (Kontext-Konkatenation)                              |
| `app/src/test/.../ui/navigation/ConnectionGateViewModelTest.kt`       | Gate-State-Übergänge inkl. `hasSelectedProject`                          |
| `verify-theme.sh`, `theme-hashes.sha256`                              | Aus dem `base-project`-Skill                                             |

## Feature-Implementierungsstatus

Siehe `FEATURES.md`. Vollständig umgesetzt: Verbindungsaufbau/Pairing/Login, Projektauswahl + gemerktes Projekt, Projekt-Hub (Befehle/Downloads/Dev-Agent/Tickets), Befehle ausführen, Downloads inkl. automatischem APK-Install, Dev-Agent-Lauf mit optionalem Kontext-Preset und Model-Wahl, Dev-Kontexte verwalten (Settings), Live-Command-Status, Tickets (projektgebunden), Theme-Einstellungen + Verbindung trennen.

**Noch nicht umgesetzt / bewusst ausgeklammert:** Compose-UI-Tests (kein Espresso/Compose-Testing im Katalog); eigenes Launcher-Icon; Server-seitiges Löschen des TOTP-Authenticators aus der App heraus (bleibt CLI-only).
