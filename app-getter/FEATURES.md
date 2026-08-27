# Features

## Zweck

app-getter ist eine eigenständige Android-App mit genau einer Aufgabe: einen `cl server` (siehe `../FEATURES.md`) im lokalen Netz finden, dessen gesammelte Dateien (`GET /collections`, i. d. R. APKs) auflisten und per Tap herunterladen und anschließend installieren oder teilen. Komplett ohne Login/TOTP-Pairing – anders als `commander` (siehe `../commander/FEATURES.md`) braucht app-getter keinerlei Authentifizierung, da es ausschließlich die serverseitig bewusst öffentlichen Endpunkte `GET /status`, `GET /collections` und `GET /collections/get/<name>` nutzt.

## Auto-Discovery

Beim Start scannt die App automatisch das tatsächliche lokale Subnetz des Geräts (anhand der Netzmaske der aktiven Netzwerkschnittstelle, WLAN/Ethernet bevorzugt vor anderen Schnittstellen, VPN-/PPP-Schnittstellen ausgeschlossen) nach einem `cl server` (`GET /status`) – kein manueller IP/Port-Eintrag nötig. Sehr große Netze (größer als `/23`) werden auf ein `/24`-Fenster um die eigene IP begrenzt, damit der Scan schnell bleibt. Während des Scans zeigt der Startbildschirm einen Ladezustand. Wird kein Server gefunden, erscheint ein Hinweis mit „Erneut suchen"-Button.

## Dateiliste

Sobald ein Server gefunden ist, listet die App alle über `GET /collections` gemeldeten Dateien auf (Name + Zeitpunkt der letzten Änderung in der Zeitzone des Geräts, neueste zuerst). Ohne Dateien erscheint ein Leer-Hinweis.

Für jede Datei, die schon einmal heruntergeladen wurde, merkt sich die App den Zeitstempel dieses Downloads und zeigt unter dem aktuellen Zeitstempel ein Label an: „✓ Aktuell", wenn der Server keine neuere Version hat, oder „Neue Version verfügbar", wenn sich die Datei auf dem Server seitdem geändert hat. Noch nie heruntergeladene Dateien zeigen kein Label.

## Download + Installieren/Teilen

Tippen auf eine Datei lädt sie über `GET /collections/get/<name>` in den App-eigenen Downloads-Ordner. Nach Abschluss erscheint ein Dialog mit zwei Aktionen: „Installieren" (`ACTION_VIEW`, MIME `application/vnd.android.package-archive`, über einen eigenen `FileProvider` – Android verlangt weiterhin einmalig die Nutzerbestätigung „Installation aus unbekannten Quellen erlauben", ab API 26 plattformseitig zwingend) oder „Teilen" (`ACTION_SEND`-Chooser, damit sich die APK z. B. per Messenger/Mail/Cloud-Speicher an andere Geräte weitergeben lässt). Tippen außerhalb des Dialogs bricht ohne Aktion ab.

Während des Downloads zeigt die betroffene Dateizeile statt des Download-Icons einen `LinearProgressIndicator` mit Live-Text darunter: Prozentsatz, Download-Geschwindigkeit (B/s, KB/s oder MB/s) und verbleibende Zeit (sofern die Serverantwort eine Content-Length liefert – sonst ein unbestimmter Balken nur mit Geschwindigkeit). Darunter wechselt das Text-Label je Phase: „Wird heruntergeladen…" → „Wird überprüft…" (Datei wird nach Abschluss auf Vollständigkeit geprüft) → „Wird installiert…" (kurz bevor der Installieren/Teilen-Dialog erscheint). Die übrigen Zeilen sind während eines laufenden Downloads deaktiviert (nur ein Download gleichzeitig).

Eine fertig heruntergeladene, noch nicht installierte/geteilte Datei geht nicht verloren: Pfad und Zeitstempel werden zusätzlich zum Verlauf persistent gespeichert (`DownloadHistoryRepository`, DataStore). Schließt man den Installieren/Teilen-Dialog versehentlich (Tippen außerhalb, Zurück-Taste) oder verlässt die App und kehrt zurück, öffnet sich der Dialog beim nächsten Laden der Dateiliste automatisch wieder, sofern die Datei noch auf dem Gerät liegt und der Server seitdem keine neuere Version bereitstellt. Ein erneuter Tap auf das Download-Icon lädt in diesem Fall nicht neu herunter, sondern öffnet den Dialog sofort erneut. Der gecachte Eintrag wird erst gelöscht, wenn tatsächlich „Installieren" oder „Teilen" angetippt wird; existiert die zwischengespeicherte Datei nicht mehr (z. B. manuell gelöscht), wird der Eintrag verworfen und ein Tap löst einen frischen Download aus.

## Feedback pro Datei

Jede Zeile in der Dateiliste hat einen eigenen Feedback-Button (Sprechblasen-Icon neben dem Download-Icon). Ein Tap öffnet einen Dialog mit einem mehrzeiligen Textfeld (Feedback darf beliebig lang sein). Im Hintergrund wird automatisch ein Kontext aus Dateiname und Zeitstempel der Datei ermittelt (`<name> (<timestamp>)`, z. B. `periodical-debug.apk (2026-08-26T10:00:00.000Z)`) und beim Senden mitgeschickt – dieser Kontext ist für den Nutzer weder sichtbar noch editierbar. Beim Absenden wird das Textfeld sofort geleert und der Dialog sofort geschlossen – das eigentliche Senden (`POST /feedback`, unauthentifiziert) läuft danach unbemerkt im Hintergrund weiter. Welche Datei das Feedback betrifft, wird automatisch mitgeschickt (kein manuelles Auswählen nötig). Schlägt das Senden fehl (kein Server gefunden oder Serverfehler), erscheint nachträglich ein Fehlerbanner auf dem Startbildschirm. Nur Senden – Auflisten/Bearbeiten/Löschen von Feedback bleibt `commander` vorbehalten (dort wird der Kontext read-only angezeigt, ebenfalls nicht editierbar).

## Einstellungen

Über das Zahnrad-Icon erreichbar:

- **Theme:** System/Hell/Dunkel (DataStore-persistiert), identisches Verhalten wie bei `commander`.
- **Server-Override:** Optionales manuelles Host/Port-Feld (Default-Port `8787`). Ist ein Host gesetzt, wird dieser direkt verwendet statt das Subnetz zu scannen – gedacht für Netzwerke, in denen die automatische Discovery nicht funktioniert (z. B. VPN, isolierte WLAN-Segmente).

## Tests

Unit-Tests (`./gradlew test`, kein `androidTest`/Compose-UI-Test, projektweite Konvention wie bei `commander`):

- **`AppGetterApiTest`** – `probeStatus`/`getCollections`/`downloadCollectionFile`/`sendFeedback` gegen MockWebServer, inkl. Fehlerfall (`ApiException` mit Server-Fehlermeldung) und finalem Fortschritt (Bytes/Gesamtgröße) über den `onProgress`-Callback von `downloadCollectionFile`.
- **`NetworkDiscoveryTest`** – `subnetHosts` (`/24`, `/28`, Fallback auf `/24`-Fenster bei zu großem Netz) und `raceFirstMatch` (reine Funktionen).
- **`TimestampFormatTest`** – `formatTimestamp` (Umrechnung von UTC in die Geräte-Zeitzone, Fallback für Zeitstempel ohne Zeitzonen-Angabe).
- **`DownloadStateTest`** – `downloadState` (`NOT_DOWNLOADED`/`UP_TO_DATE`/`UPDATE_AVAILABLE`, reine Funktion).
- **`CollectionsViewModelTest`** – `downloadingFileName`-Zustand während des Downloads (gesetzt bis der Install-Intent konsumiert wird, geleert bei Fehler, zweiter Tap während eines laufenden Downloads wird ignoriert), `downloadStatus`-Durchreichung von `ApkInstaller`, `downloadedTimestamps`-Durchreichung von `DownloadHistoryRepository` sowie `recordDownload()`/`recordPendingInstall()` nach erfolgreichem Download, gegen gemockte `ApkInstaller`/`AppGetterApi`/`SettingsRepository`/`DownloadHistoryRepository` (MockK, `StandardTestDispatcher`). Zusätzlich: ein zum aktuellen Server-Zeitstempel passender Pending-Install wird nach dem Scan automatisch als `installFile` wiederhergestellt; ein Pending-Install auf eine inzwischen fehlende Datei wird verworfen; ein erneuter Tap auf Download nach Dialog-Dismiss liefert die gecachte Datei ohne erneuten Netzwerk-Download; `resolveInstallFile()` (Installieren/Teilen) löscht den Pending-Install-Eintrag, `consumeInstallFile()` (Dismiss) tut das ausdrücklich nicht.
- **`DownloadHistoryRepositoryTest`** – `parsePendingInstalls` (reine Funktion): paart zusammengehörige Zeitstempel-/Pfad-Keys, ignoriert fremde Preference-Keys, verwirft Einträge ohne Gegenstück, liefert bei leerer Map nichts.
- **`DownloadProgressTest`** – `DownloadProgressTracker` (Geschwindigkeit aus Byte-Delta/Zeit-Delta, ETA aus Restbytes/Geschwindigkeit, Fenster-Bereinigung alter Samples, `null` bei unbekannter Gesamtgröße oder Geschwindigkeit 0) sowie `DownloadProgress.fraction()` (Normalfall, unbekannte/0 Gesamtgröße, Clamping auf 1).
- **`DownloadProgressFormatTest`** – `formatDownloadSpeed`/`formatDownloadEta` (B/s, KB/s, MB/s, Sekunden vs. Minuten:Sekunden, `null` bei unbekannter ETA).
- **`ApkInstallerTest`** – Phasenwechsel `DOWNLOADING` → `VERIFYING` → `INSTALLING` inkl. durchgereichtem Progress-Callback, Verifikationsfehler bei leerer Datei, `clearDownloadStatus()`.
- **`FeedbackViewModelTest`** – `open`/`dismiss` steuern Dialog-Sektion und Text, leerer/whitespace-Text loest keinen Request aus, erfolgreiches Senden leert Text und schliesst den Dialog sofort (bevor der Request beantwortet ist), `section` wird automatisch mitgeschickt, `context` wird vorbelegt (nicht editierbar) getrimmt mitgeschickt (leer → `null`), Server-Fehler bzw. kein aufloesbarer Host melden `error` ohne den Dialog wieder zu oeffnen, `clearError()` setzt den Fehler zurueck.

`verify-theme.sh` prüft wie bei `commander` die Isolation der Farb-Tokens in `Color.kt`/`Theme.kt` sowie deren Datei-Integrität (`theme-hashes.sha256`).
