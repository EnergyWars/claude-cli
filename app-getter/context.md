# Kontext: app-getter

## Zweck

Eigenständige Android-App (Kotlin/Compose), die ausschließlich einen `cl server` im lokalen Netz findet, dessen gesammelte Dateien (`GET /collections`, i. d. R. APKs, siehe `../context.md` "Collection-System") auflistet und per Tap herunterlädt + automatisch installiert. Komplett unauthentifiziert (kein Login), da die genutzten Endpunkte (`GET /status`, `GET /collections`, `GET /collections/get/:name`) serverseitig bewusst öffentlich sind.

## Architektur-Entscheidungen

- **Herkunft:** Von `commander/` abgeleitet (nicht aus der rohen Basisvorlage) – Theme/Tokens/Components (`ui/theme/*`, `ui/components/*`), der Hilt/Compose-Navigation-Grundaufbau, die Discovery-Logik (`data/discovery/NetworkDiscovery.kt`, unverändert kopiert) und der FileProvider-Install-Mechanismus wurden 1:1 übernommen (Paket `com.wafflehq.commander` → `com.wafflehq.appgetter`). Entfernt: Login/Setup/TOTP/JWT-Flow, `ConnectionRepository`/`Session`, Agents/Commands/Tickets/History/Downloads-pro-Projekt/Context-Editor, Room (keine lokalen Entitäten nötig).
- **Kein Auth:** Alle von `AppGetterApi` (`data/api/AppGetterApi.kt`) angesprochenen Endpunkte sind unauthentifiziert – Host/Port werden pro Aufruf explizit übergeben, es gibt kein Session-/Token-Konzept wie bei `commander`.
- **Discovery + manueller Override:** `data/settings/SettingsRepository.kt` speichert optional einen manuellen `host`/`port`-Override (DataStore, Default-Port `8787`) sowie den Theme-Mode. Ist kein Host manuell gesetzt, scannt `data/discovery/NetworkDiscovery.kt` (`discoverHost(port)`) das lokale `/24`-Subnetz per `GET /status` (identische Technik wie `commander`s Auto-Discovery).
- **Auto-Install:** `data/install/ApkInstaller.kt` lädt eine Datei über `AppGetterApi.downloadCollectionFile()` in `getExternalFilesDir(DIRECTORY_DOWNLOADS)` und liefert einen `ACTION_VIEW`-Intent mit MIME `application/vnd.android.package-archive` über den eigenen `FileProvider` (`${applicationId}.fileprovider`). Android verlangt ab API 26 weiterhin eine einmalige Nutzerbestätigung ("Installation aus unbekannten Quellen erlauben") – ein wirklich stiller Install ist ohne System-/Geräteeigentümer-Rechte plattformseitig nicht möglich; die App löst den Intent automatisch aus, der letzte Bestätigungsschritt bleibt System-UI. `AndroidManifest.xml` deklariert dafür `REQUEST_INSTALL_PACKAGES` zusätzlich zu `INTERNET`.
- **Navigation (`ui/navigation/AppNavHost.kt`):** Nur zwei Routen – `HOME` (`ui/collections/CollectionsScreen.kt`, Startbildschirm, State-Machine `Scanning`/`NotFound`/`Found(host, port, files)` in `CollectionsViewModel`) und `SETTINGS` (`ui/settings/SettingsScreen.kt`: Theme-Dropdown + Host/Port-Override-Formular). Kein Projekt-/Pfad-Konzept wie bei `commander` – es gibt nur "den einen Server".
- **DI:** Kein eigenes Hilt-`@Module` nötig – alle Klassen (`AppGetterApi`, `NetworkDiscovery`, `SettingsRepository`, `ApkInstaller`, alle ViewModels) sind reine `@Inject`-Konstruktor-Injection, es gibt keine Interface-Bindings oder Room-Provider wie bei `commander`.

## Dateistruktur

| Datei/Verzeichnis                                       | Zweck                                                                 |
| -------------------------------------------------------- | ---------------------------------------------------------------------- |
| `data/api/AppGetterApi.kt`, `ApiModels.kt`                | Unauthentifizierter OkHttp-Client: `probeStatus`/`getCollections`/`downloadCollectionFile` |
| `data/discovery/NetworkDiscovery.kt`                      | Auto-Discovery: `/24`-Subnetz nach `GET /status` scannen (aus `commander` übernommen) |
| `data/install/ApkInstaller.kt`                            | Download + `ACTION_VIEW`-Install-Intent (FileProvider)                |
| `data/settings/SettingsRepository.kt`, `ThemeMode.kt`     | Theme-Mode + optionaler Host/Port-Override (DataStore)                |
| `ui/theme/*`, `ui/components/*`                           | 1:1 aus `commander` übernommenes WaffleHQ-Design-System                |
| `ui/collections/CollectionsScreen.kt`, `CollectionsViewModel.kt` | Startbildschirm: Scan-Status, Dateiliste, Install-Button          |
| `ui/collections/TimestampFormat.kt`                       | `formatTimestamp` (reine Funktion, aus `commander` übernommen)         |
| `ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`   | Theme-Wahl + Host/Port-Override-Formular                              |
| `ui/navigation/AppNavHost.kt`                             | `Routes.HOME`/`Routes.SETTINGS`                                       |
| `AppGetterApp.kt`, `MainActivity.kt`                      | `@HiltAndroidApp`-Application, Compose-Root inkl. Theme-Auflösung      |
| `app/src/test/.../data/api/AppGetterApiTest.kt`           | HTTP-Vertrag gegen MockWebServer                                       |
| `app/src/test/.../data/discovery/NetworkDiscoveryTest.kt` | `subnetHosts`/`raceFirstMatch` (aus `commander` übernommen)            |
| `app/src/test/.../ui/collections/TimestampFormatTest.kt`  | `formatTimestamp` (aus `commander` übernommen)                        |
| `verify-theme.sh`, `theme-hashes.sha256`                  | Theme-Integritätsprüfung (Muster aus `commander`, Hashes für dieses Repo neu erzeugt) |

## Feature-Implementierungsstatus

Siehe `FEATURES.md`. Vollständig umgesetzt: Auto-Discovery im lokalen Netz (mit manuellem Host/Port-Override in den Einstellungen), Auflisten aller `GET /collections`-Dateien inkl. Zeitstempel, Download + automatischer Install-Intent pro Datei, Theme-Einstellungen (System/Hell/Dunkel). `./gradlew test` grün (Unit-Tests für API-Client, Discovery, Timestamp-Formatierung), `verify-theme.sh` grün.
