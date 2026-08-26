# Features

## Zweck

app-getter ist eine eigenständige Android-App mit genau einer Aufgabe: einen `cl server` (siehe `../FEATURES.md`) im lokalen Netz finden, dessen gesammelte Dateien (`GET /collections`, i. d. R. APKs) auflisten und per Tap herunterladen + automatisch installieren. Komplett ohne Login/TOTP-Pairing – anders als `commander` (siehe `../commander/FEATURES.md`) braucht app-getter keinerlei Authentifizierung, da es ausschließlich die serverseitig bewusst öffentlichen Endpunkte `GET /status`, `GET /collections` und `GET /collections/get/<name>` nutzt.

## Auto-Discovery

Beim Start scannt die App automatisch das lokale `/24`-Subnetz des Geräts nach einem `cl server` (`GET /status`, identische Technik wie `commander`s Verbindungsaufbau) – kein manueller IP/Port-Eintrag nötig. Während des Scans zeigt der Startbildschirm einen Ladezustand. Wird kein Server gefunden, erscheint ein Hinweis mit „Erneut suchen"-Button.

## Dateiliste

Sobald ein Server gefunden ist, listet die App alle über `GET /collections` gemeldeten Dateien auf (Name + Zeitpunkt der letzten Änderung, neueste zuerst). Ohne Dateien erscheint ein Leer-Hinweis.

## Download + Auto-Install

Tippen auf eine Datei lädt sie über `GET /collections/get/<name>` in den App-eigenen Downloads-Ordner und löst danach automatisch einen Install-Intent aus (`ACTION_VIEW`, MIME `application/vnd.android.package-archive`, über einen eigenen `FileProvider`). Android verlangt weiterhin einmalig die Nutzerbestätigung „Installation aus unbekannten Quellen erlauben" (ab API 26 plattformseitig zwingend, ohne System-/Geräteeigentümer-Rechte nicht umgehbar) – die App selbst automatisiert den kompletten restlichen Ablauf.

Während des Downloads zeigt die betroffene Dateizeile statt des Download-Icons einen `LinearProgressIndicator` mit Live-Text darunter: Prozentsatz, Download-Geschwindigkeit (B/s, KB/s oder MB/s) und verbleibende Zeit (sofern die Serverantwort eine Content-Length liefert – sonst ein unbestimmter Balken nur mit Geschwindigkeit). Darunter wechselt das Text-Label je Phase: „Wird heruntergeladen…" → „Wird überprüft…" (Datei wird nach Abschluss auf Vollständigkeit geprüft) → „Wird installiert…" (Install-Intent wird ausgelöst). Die übrigen Zeilen sind während eines laufenden Downloads deaktiviert (nur ein Download gleichzeitig).

## Einstellungen

Über das Zahnrad-Icon erreichbar:

- **Theme:** System/Hell/Dunkel (DataStore-persistiert), identisches Verhalten wie bei `commander`.
- **Server-Override:** Optionales manuelles Host/Port-Feld (Default-Port `8787`). Ist ein Host gesetzt, wird dieser direkt verwendet statt das Subnetz zu scannen – gedacht für Netzwerke, in denen die automatische Discovery nicht funktioniert (z. B. VPN, isolierte WLAN-Segmente).
- **Feedback senden:** Freitextfeld + Senden-Button, legt über `POST /feedback` (unauthentifiziert) einen Eintrag auf dem Server an. Nur Senden – Auflisten/Bearbeiten/Löschen von Feedback bleibt `commander` vorbehalten. Löst die App keinen Host auf (weder Override noch Discovery), erscheint ein Fehlertext statt eines Requests.

## Tests

Unit-Tests (`./gradlew test`, kein `androidTest`/Compose-UI-Test, projektweite Konvention wie bei `commander`):

- **`AppGetterApiTest`** – `probeStatus`/`getCollections`/`downloadCollectionFile`/`sendFeedback` gegen MockWebServer, inkl. Fehlerfall (`ApiException` mit Server-Fehlermeldung) und finalem Fortschritt (Bytes/Gesamtgröße) über den `onProgress`-Callback von `downloadCollectionFile`.
- **`NetworkDiscoveryTest`** – `subnetHosts`/`raceFirstMatch` (reine Funktionen, aus `commander` übernommen).
- **`TimestampFormatTest`** – `formatTimestamp` (reine Funktion, aus `commander` übernommen).
- **`CollectionsViewModelTest`** – `downloadingFileName`-Zustand während des Downloads (gesetzt bis der Install-Intent konsumiert wird, geleert bei Fehler, zweiter Tap während eines laufenden Downloads wird ignoriert), `downloadStatus`-Durchreichung von `ApkInstaller`, gegen gemockte `ApkInstaller`/`AppGetterApi`/`SettingsRepository` (MockK, `StandardTestDispatcher`).
- **`DownloadProgressTest`** – `DownloadProgressTracker` (Geschwindigkeit aus Byte-Delta/Zeit-Delta, ETA aus Restbytes/Geschwindigkeit, Fenster-Bereinigung alter Samples, `null` bei unbekannter Gesamtgröße oder Geschwindigkeit 0) sowie `DownloadProgress.fraction()` (Normalfall, unbekannte/0 Gesamtgröße, Clamping auf 1).
- **`DownloadProgressFormatTest`** – `formatDownloadSpeed`/`formatDownloadEta` (B/s, KB/s, MB/s, Sekunden vs. Minuten:Sekunden, `null` bei unbekannter ETA).
- **`ApkInstallerTest`** – Phasenwechsel `DOWNLOADING` → `VERIFYING` → `INSTALLING` inkl. durchgereichtem Progress-Callback, Verifikationsfehler bei leerer Datei, `clearDownloadStatus()`.
- **`FeedbackViewModelTest`** – leerer/whitespace-Text loest keinen Request aus, Erfolg leert das Textfeld und setzt `sent`, Server-Fehler behaelt den Text und setzt `error`, kein aufloesbarer Host meldet einen Fehler ohne API-Aufruf.

`verify-theme.sh` prüft wie bei `commander` die Isolation der Farb-Tokens in `Color.kt`/`Theme.kt` sowie deren Datei-Integrität (`theme-hashes.sha256`).
