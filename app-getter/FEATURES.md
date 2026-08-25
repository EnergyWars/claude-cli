# Features

## Zweck

app-getter ist eine eigenständige Android-App mit genau einer Aufgabe: einen `cl server` (siehe `../FEATURES.md`) im lokalen Netz finden, dessen gesammelte Dateien (`GET /collections`, i. d. R. APKs) auflisten und per Tap herunterladen + automatisch installieren. Komplett ohne Login/TOTP-Pairing – anders als `commander` (siehe `../commander/FEATURES.md`) braucht app-getter keinerlei Authentifizierung, da es ausschließlich die serverseitig bewusst öffentlichen Endpunkte `GET /status`, `GET /collections` und `GET /collections/get/<name>` nutzt.

## Auto-Discovery

Beim Start scannt die App automatisch das lokale `/24`-Subnetz des Geräts nach einem `cl server` (`GET /status`, identische Technik wie `commander`s Verbindungsaufbau) – kein manueller IP/Port-Eintrag nötig. Während des Scans zeigt der Startbildschirm einen Ladezustand. Wird kein Server gefunden, erscheint ein Hinweis mit „Erneut suchen"-Button.

## Dateiliste

Sobald ein Server gefunden ist, listet die App alle über `GET /collections` gemeldeten Dateien auf (Name + Zeitpunkt der letzten Änderung, neueste zuerst). Ohne Dateien erscheint ein Leer-Hinweis.

## Download + Auto-Install

Tippen auf eine Datei lädt sie über `GET /collections/get/<name>` in den App-eigenen Downloads-Ordner und löst danach automatisch einen Install-Intent aus (`ACTION_VIEW`, MIME `application/vnd.android.package-archive`, über einen eigenen `FileProvider`). Android verlangt weiterhin einmalig die Nutzerbestätigung „Installation aus unbekannten Quellen erlauben" (ab API 26 plattformseitig zwingend, ohne System-/Geräteeigentümer-Rechte nicht umgehbar) – die App selbst automatisiert den kompletten restlichen Ablauf.

## Einstellungen

Über das Zahnrad-Icon erreichbar:

- **Theme:** System/Hell/Dunkel (DataStore-persistiert), identisches Verhalten wie bei `commander`.
- **Server-Override:** Optionales manuelles Host/Port-Feld (Default-Port `8787`). Ist ein Host gesetzt, wird dieser direkt verwendet statt das Subnetz zu scannen – gedacht für Netzwerke, in denen die automatische Discovery nicht funktioniert (z. B. VPN, isolierte WLAN-Segmente).

## Tests

Unit-Tests (`./gradlew test`, kein `androidTest`/Compose-UI-Test, projektweite Konvention wie bei `commander`):

- **`AppGetterApiTest`** – `probeStatus`/`getCollections`/`downloadCollectionFile` gegen MockWebServer, inkl. Fehlerfall (`ApiException` mit Server-Fehlermeldung).
- **`NetworkDiscoveryTest`** – `subnetHosts`/`raceFirstMatch` (reine Funktionen, aus `commander` übernommen).
- **`TimestampFormatTest`** – `formatTimestamp` (reine Funktion, aus `commander` übernommen).

`verify-theme.sh` prüft wie bei `commander` die Isolation der Farb-Tokens in `Color.kt`/`Theme.kt` sowie deren Datei-Integrität (`theme-hashes.sha256`).
