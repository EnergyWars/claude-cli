# Features

## Verbindungsaufbau (Setup)

Erster Start (keine gespeicherte Verbindung) zeigt den Setup-Screen: Felder für Host/IP und Port (Default `8787`), Button „Automatisch suchen“, Button „Verbinden“. Ablauf beim Tippen auf „Verbinden“:

1. `GET /health` – prüft Erreichbarkeit, unabhängig vom Pairing-Status.
2. `GET /auth/status` – liefert `{ active, pending }`.
   - `active == true` (ein Authenticator ist auf dem Server bereits eingerichtet, z. B. von einem anderen Client): Screen wechselt in den Modus „Bestehendes Secret eingeben“ – ein Textfeld nimmt das damals bei der Ersteinrichtung angezeigte Base32-Secret entgegen, „Verbinden“ verifiziert es gegen einen geschützten Endpunkt (`GET /paths`), ohne es serverseitig zu verändern.
   - sonst (nichts eingerichtet oder ein unbestätigtes Setup steht aus): App richtet den Authenticator **selbst** ein – `POST /auth/setup` liefert ein neues Secret, die App berechnet daraus sofort selbst den aktuellen TOTP-Code (kein externer Authenticator nötig) und bestätigt ihn per `POST /auth/setup/confirm`.
3. Bei Erfolg wird die Verbindung (Host, Port, TOTP-Secret) gespeichert (siehe „Verbindungsspeicher“) und die App wechselt zum Dashboard.

Fehlerfälle (Server nicht erreichbar, außerhalb des lokalen Netzes für Setup/Status, falscher manueller Code) werden als Fehlermeldung angezeigt.

### Automatische Server-Suche (Auto-Discovery)

Button „Automatisch suchen“ neben den Host/Port-Feldern: ermittelt die eigene lokale IPv4-Adresse des Geräts (`java.net.NetworkInterface`, erste nicht-Loopback-IPv4-Adresse), nimmt deren `/24`-Subnetz an (die ersten drei Oktette) und fragt parallel (max. 32 gleichzeitig) `GET http://<ip>:<port>/status` für jede Adresse `.1` bis `.254` ab – der Port kommt aus dem Port-Feld (Default `8787`, nicht separat abfragbar). Die erste Adresse, die mit `204` antwortet, wird sofort ins Host-Feld übernommen (Scan der übrigen Adressen wird abgebrochen); antwortet keine, erscheint eine Fehlermeldung „Kein Server im lokalen Netz gefunden.“. Der Nutzer muss danach weiterhin selbst „Verbinden“ tippen (Discovery befüllt nur das Feld, pairing/verbindet nicht automatisch). Kurze Timeouts (400 ms) pro Adresse, damit der Scan bei 254 Adressen nicht spürbar lange dauert.

## Verbindungsspeicher

`data/connection/ConnectionRepository.kt` hält Host, Port und TOTP-Secret in DataStore Preferences; das Secret liegt darin **nicht im Klartext**, sondern AES-256-GCM-verschlüsselt mit einem Schlüssel aus dem Android-Keystore (`data/crypto/KeystoreCipher.kt`, nie exportierbar aus der sicheren Hardware/OS-Storage). „Verbindung trennen“ (Settings) löscht den gesamten Eintrag; die App fällt dann auf den Setup-Screen zurück.

## Google-Authenticator-Client (TOTP)

`data/totp/TotpGenerator.kt` implementiert RFC 6238/4226 clean-room in Kotlin (Base32-Decoding, `HmacSHA1`, 30-Sekunden-Schritt, 6-stellig, Standard-Truncation) – exakt kompatibel zu `cl server`s `src/totp.ts`. Für jeden authentifizierten API-Aufruf wird der aktuelle Code frisch aus dem gespeicherten Secret berechnet und als `X-TOTP-Code`-Header gesendet; die App braucht dafür keine externe Authenticator-App.

## Dashboard (Home)

Lädt nach dem Verbinden `GET /manifest` und zeigt dynamisch mehrere Abschnitte (kein Hardcoding von Agents/Pfaden):

- **Tickets** – ein Einstieg oben auf der Seite, öffnet die globale Ticket-Liste über alle Pfade hinweg (siehe „Tickets“). Untertitel zeigt die Anzahl offener Tickets (`GET /tickets?status=open`), fällt bei Fehler auf einen generischen Text zurück statt die restliche Seite zu blockieren.
- **Agents** – jeder Eintrag aus `manifest.agents` (`{command, description}`), Tap öffnet „Agent starten“ mit vorausgewähltem Agent.
- **Pfade** – jeder Eintrag aus `manifest.paths` mit Anzahl Commands/Dateien, Tap öffnet die Pfad-Detailseite.
- **Verlauf** – lokal gespeicherte, zuletzt ausgelöste Commands (siehe „Lokaler Verlauf“), Tap öffnet die Status-Detailseite auch nach App-Neustart.

Ein Aktualisieren-Button lädt das Manifest neu; Verbindungsfehler (z. B. abgelaufenes/entferntes TOTP) erscheinen als Banner mit „Erneut versuchen“.

## Agent starten

Dropdown-Auswahl für Agent (aus dem Manifest), Pfad (aus dem Manifest) und optional Model (Standard/`haiku`/`sonnet`/`opus`/`fable`), Mehrzeilen-Textfeld für den Prompt. „Starten“ ruft `POST /` (main-Agent) bzw. `POST /<agent>` auf, merkt sich die zurückgegebene Command-ID lokal (Verlauf) und öffnet die Status-Detailseite. Von der Pfad-Detailseite aus vorbelegt mit dem gewählten Pfad („Agent hier starten“) – das ist der Weg, um in einem konfigurierten Pfad (z. B. `periodical`) Claude zum Weiterentwickeln der App zu starten.

## Pfad-Details

Pro Pfad (`GET /manifest`-Eintrag) zwei Bereiche:

- **Commands** (`paths[].commands`) – Anzeigename/Beschreibung je Eintrag, Tap führt ihn aus (`POST /paths/<pathName>/commands/<key>`) und öffnet die Status-Detailseite.
- **Dateien** (`paths[].hosted`) – `type: "file"`-Einträge haben einen Direkt-Download-Button; `type: "path"`-Einträge lassen sich aufklappen (`GET /files/<pathName>/<hostedName>`, listet die enthaltenen Dateien) und jede einzelne Datei darin einzeln herunterladen (`GET /files/<pathName>/<hostedName>/<fileName>`).

Downloads landen unter `getExternalFilesDir(DIRECTORY_DOWNLOADS)` (kein Storage-Permission nötig) und werden danach automatisch zum Öffnen/Teilen angeboten (`FileProvider`, siehe „Datei-Downloads“). Das ist der Weg, um die von einem Agent gebaute APK direkt herunterzuladen (z. B. `periodical`s `debug-apk`/`release-apk`-Hosted-Einträge).

## Command-Status (live)

Pollt `GET /state/<id>` alle 2 Sekunden, solange `status == "running"`. Zeigt Status-Pill (`running`/`completed`/`failed`), Agent/Command-Text, den bisherigen Output live (monospace) und den Exit-Code nach Abschluss. Falls die Command-Detailseite mit einem Pfadnamen geöffnet wurde und dieser Pfad `hosted`-Datei-Einträge hat, erscheinen nach erfolgreichem Abschluss zusätzlich Schnellzugriff-Download-Buttons dafür.

## Lokaler Verlauf

Da `cl server` selbst keine Liste aller Commands anbietet, merkt sich die App lokal (Room, `data/db/AppDatabase.kt`, Tabelle `command_history`) jede über die App selbst ausgelöste Command-ID (Agent-Start oder Pfad-Command) mit Label und Pfadname. So bleiben laufende/vergangene Commands im Dashboard auffindbar, auch nachdem die App neu gestartet wurde.

## Datei-Downloads

`data/download/HostedFileDownloader.kt` kapselt Download (`ClServerApi.downloadHostedEntry`/`downloadHostedFile`, Zieldateiname aus dem `Content-Disposition`-Header) und das anschließende Öffnen/Teilen über einen `FileProvider` (`res/xml/file_paths.xml`, Autorität `<applicationId>.fileprovider`) – kein Speicher-Runtime-Permission nötig, funktioniert auf allen unterstützten API-Leveln (26–35) identisch.

## Tickets

Erreichbar sowohl global über den „Tickets“-Einstieg auf der Startseite (alle Pfade) als auch über den Button „Tickets“ auf der Pfad-Detailseite (auf diesen Pfad gefiltert) – derselbe Screen gegen `cl server`s `/tickets/...`-Endpunkte, mit `pathName` als optionalem Navigations-Argument.

Ein Ticket besteht aus: der **Original-Anweisung** (der Text, den man beim Anlegen eingegeben hat, unverändert), einer **Zusammenfassung** (Ziel + aktueller Ist-Zustand), einer **Claude-Anweisung** (wie man das Feature später Claude geben würde, um es umzusetzen), einer **Kategorie** (freies Schlagwort zur Gruppierung zusammenhängender Tickets) und einem **Status** (Offen/In Bearbeitung/Fertig/Abgelehnt).

**Liste:** Global (`GET /tickets`) zeigt Tickets aller Pfade, gefiltert (`GET /tickets/<pathName>`) nur die eines Pfads. Jede Zeile zeigt die Zusammenfassung, im globalen Modus zusätzlich Pfadname und Kategorie, sonst nur die Kategorie, sowie eine Status-Pille. Ein Status-Dropdown filtert die Liste (Query-Parameter `status`, „Alle“ = ohne Filter). Zum Anlegen: im globalen Modus zusätzlich ein Projekt-Dropdown (Pfade aus `GET /manifest`), dann ein Textfeld + „Ticket erstellen“, das den eingegebenen Text an `POST /tickets/<pathName>` schickt – der Text wird unverändert als Original-Anweisung gespeichert, ein Agent auf dem Server interpretiert ihn zusätzlich im Projektkontext und liefert Zusammenfassung/Claude-Anweisung/Kategorie; nach Erfolg öffnet die App automatisch die Detailseite des neuen Tickets.

**Detail:** Textfelder für Original-Anweisung, Zusammenfassung, Claude-Anweisung und Kategorie sowie ein Status-Dropdown, alle vorausgefüllt mit dem aktuellen Stand (`GET /tickets/<pathName>/<id>`) und komplett editierbar. „Speichern“ schickt alle fünf Werte per `PATCH /tickets/<pathName>/<id>`. „Ticket löschen“ ruft `DELETE /tickets/<pathName>/<id>` auf und kehrt danach zur Liste zurück.

Implementiert in `ui/tickets/` (`TicketListScreen`/`TicketListViewModel`, `TicketDetailScreen`/`TicketDetailViewModel`, `TicketStatusUi.kt` für die Status→Farbe/Label-Zuordnung), `data/api/ClServerApi.kt` (`listTickets`/`listAllTickets`/`createTicket`/`getTicket`/`updateTicket`/`deleteTicket`), `data/api/ApiModels.kt` (`Ticket`/`TicketList`/`TicketCreateRequest`/`TicketPatchRequest`). Getestet in `data/api/ClServerApiTest.kt` (alle Aufrufe inkl. `listAllTickets` gegen MockWebServer, inkl. Query-Parameter und PATCH/DELETE-Methode).

## Einstellungen

Wiederverwendet die Basisvorlage: Theme-Umschalter (System/Hell/Dunkel, DataStore-persistiert). Zusätzlich ein „Verbindung“-Abschnitt: zeigt Host:Port der aktiven Verbindung, „Verbindung trennen“ löscht sie und führt zurück zum Setup-Screen.

## Netzwerk

`cl server` spricht ausschließlich Klartext-HTTP (keine TLS-Option) – die App erlaubt daher `usesCleartextTraffic` global (Host/Port sind nutzerdefiniert, i. d. R. lokales Netz/VPN). Alle authentifizierten Aufrufe laufen über `data/api/ClServerApi.kt` (OkHttp + kotlinx.serialization), das pro Request frisch den TOTP-Header berechnet und Server-Fehlerantworten (`{error}`-JSON) in eine lesbare `ApiException` übersetzt.
