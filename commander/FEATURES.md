# Features

## Verbindungsaufbau (Setup)

Erster Start (keine gespeicherte Verbindung) zeigt den Setup-Screen: Felder für Host/IP und Port (Default `8787`), Button „Automatisch suchen", Button „Verbinden". Prüft Erreichbarkeit (`GET /health`) und Pairing-Status; richtet bei Bedarf einen Authenticator ein oder verifiziert ein bestehendes Secret. Nach Erfolg wird die Verbindung gespeichert und zum Login gewechselt.

### Automatische Server-Suche (Auto-Discovery)

Button „Automatisch suchen": ermittelt die eigene IPv4-Adresse im verbundenen Wi-Fi-Netzwerk (auch wenn gleichzeitig Mobilfunk oder ein VPN aktiv ist), scannt parallel das `/24`-Subnetz nach `GET /status` und übernimmt die erste antwortende Adresse ins Host-Feld. Der Nutzer muss danach weiterhin selbst „Verbinden" tippen. Ist kein Wi-Fi verbunden, liefert die Suche „Kein Server im lokalen Netz gefunden." – die IP kann dann weiterhin manuell eingetragen werden.

## Login

Nach dem Verbinden fragt der Login-Screen den aktuellen 6-stelligen TOTP-Code ab und tauscht ihn gegen ein JWT (`POST /auth/login`, 2 Stunden gültig). Läuft das Token ab, führt die App automatisch zurück zum Login. „Verbindung wechseln" führt zurück zum Setup.

**Automatisches Verlängern (Sliding Session):** Die App hält die Session proaktiv am Leben, damit ein aktiver Nutzer nicht alle 2 Stunden neu einloggen muss – der Server verlängert dabei nur ein noch gültiges Token (`POST /auth/refresh`), ein bereits abgelaufenes Token erfordert weiterhin einen erneuten Login mit TOTP-Code. Zwei Auslöser:

- **Nach jeder Aktion:** Jeder erfolgreiche authentifizierte Server-Aufruf löst im Hintergrund einen Refresh aus (min. 1 Minute Abstand zwischen zwei Versuchen, damit nicht jeder einzelne von mehreren gleichzeitigen Aufrufen einen eigenen Refresh anstößt).
- **Beim Reintappen in die App:** Kommt die App in den Vordergrund (App-Start oder Rückkehr aus dem Hintergrund), wird sofort ein Refresh versucht, unabhängig vom letzten Aktions-Refresh.

Schlägt ein Refresh fehl (z. B. Netzwerkfehler), bleibt die bestehende Session unverändert bestehen – der nächste erfolgreiche Aufruf oder App-Start versucht es erneut.

## Projektauswahl

Ist noch kein Projekt gemerkt (oder nach „Verbindung trennen"), zeigt die App direkt nach dem Login eine reine Liste aller Projekte (`GET /manifest`.`paths`) – nichts weiter. Tippen auf einen Eintrag merkt sich das Projekt dauerhaft (DataStore) und öffnet den Projekt-Hub. Diese Auswahl bleibt bestehen, bis sie explizit geändert wird (Projekt-Dropdown im Hub) oder die Verbindung getrennt wird.

## Projekt-Hub

Zentrale Seite nach der Projektauswahl. Oben ein Dropdown mit dem aktuellen Projektnamen – Tippen öffnet die Liste aller Projekte, Auswahl wechselt sofort um und persistiert. Daneben ein Zahnrad-Icon zu den Einstellungen. Darunter acht Einträge, jeder öffnet einen eigenen, auf seinen Zweck beschränkten Screen. **Entwicklung** ist optisch hervorgehoben (eigene Karte mit Primary-Akzentfarbe, Icon und Untertitel statt schlichter Listenzeile), da es der zentrale Einstieg für Agenten-Läufe ist; die übrigen sieben Einträge bleiben schlichte Listenzeilen:

- **Entwicklung** (hervorgehoben) – alle konfigurierten Agenten des aktuellen Projekts.
- **Befehle** – alle Commands des aktuellen Projekts.
- **Downloads** – alle downloadbaren Dateien des aktuellen Projekts.
- **Tickets** – die Ticket-Liste dieses Projekts.
- **Verlauf** – der komplette Command-Verlauf dieses Projekts (siehe unten).
- **Feedback** – Feedback-Liste nur des aktuellen Projekts (siehe unten).
- **Sammlung** – löst `cl server`s Collection-Feature nur für das aktuelle Projekt aus (siehe unten).
- **Statistik** – Kennzahlen des aktuellen Projekts (siehe unten).

Feedback und Sammlung sind wie alle anderen Einträge pro Projekt: welches Feedback bzw. welche Collection-Einträge zu einem Projekt gehören, ergibt sich aus `collection[].path` in `config.json` (siehe `../FEATURES.md`, Collection-System) – Feedback wird dabei automatisch serverseitig über den `section`-Wert einem Projekt zugeordnet. Es gibt sonst keine weiteren Einträge oder Querverweise zwischen diesen Bereichen.

Ganz oben, noch vor dem Projekt-Dropdown, zeigt der Hub ein **Nutzungslimits-Banner** (`GET /usage`, siehe `../FEATURES.md`, Nutzungslimits) – pro Limit eine Zeile mit Label, Prozentwert, Balken (`LinearProgressIndicator`, Farbe je nach Auslastung: < 70 % neutral, 70–89 % Warnung, ≥ 90 % Fehler) und darunter dem Reset-Zeitpunkt (`limit.resetsAt`, unverändert die vom Server gelieferte Textangabe, z. B. „Aug 27, 5:40pm (Europe/Berlin)"). Wird alle 60 Sekunden neu geladen, solange der Hub sichtbar ist; ein fehlgeschlagener Abruf wird still ignoriert (zeigt einfach den letzten bekannten Stand weiter, kein Fehler-Banner) – die Nutzungsanzeige ist informativ und soll den Hub nicht blockieren. Ohne Limits (noch nicht geladen oder leere Antwort) bleibt die Zeile einfach weg. Ein Tap auf den Banner-Header klappt die Limit-Zeilen ein bzw. aus; der Zustand bleibt über App-Neustarts hinweg erhalten.

## Befehle

Liste der `path.commands`-Einträge (Anzeigename + Beschreibung) des aktuellen Projekts. Tippen öffnet einen Bestätigungsdialog („Befehl ausführen?" mit Anzeigename); erst nach Bestätigung wird der Befehl ausgeführt (`POST /paths/<path>/commands/<key>`) und die Status-Detailseite geöffnet. Sonst nichts auf diesem Screen.

## Downloads

Liste der `path.hosted`-Einträge des aktuellen Projekts. `type: "file"`-Einträge haben einen Download-/Install-Button; `type: "path"`-Einträge lassen sich aufklappen und einzelne enthaltene Dateien herunterladen. Endet der heruntergeladene Dateiname auf `.apk`, erscheint nach Abschluss ein Dialog mit drei Aktionen: „Installieren" (System-Install-Dialog inkl. einmaligem „Unbekannte Quellen erlauben", falls noch nicht freigegeben), „Teilen" (`ACTION_SEND`-Chooser, um die APK z. B. per Messenger/Mail/Cloud-Speicher weiterzugeben) oder „Löschen" (Bestätigungsnachfrage, entfernt die Datei vom Gerät). Tippen außerhalb des Dialogs bricht ohne Aktion ab, ohne die Datei zu löschen. Alle anderen Dateien werden wie bisher direkt zum Öffnen/Teilen angeboten (kein Dialog, keine Zwischenspeicherung). Downloads landen unter `getExternalFilesDir(DIRECTORY_DOWNLOADS)` (kein Speicher-Runtime-Permission nötig).

Eine fertig heruntergeladene, noch nicht gelöschte APK bleibt dauerhaft gespeichert. Beim nächsten Öffnen der Liste zeigt die betroffene Zeile statt des Download- ein Install-Icon; ein Tap darauf öffnet den Installieren/Teilen/Löschen-Dialog sofort, ohne erneut herunterzuladen. Der Dialog öffnet sich dabei **nicht** von selbst (weder beim Laden der Liste noch beim App-Neustart) – nur ein bewusster Tap auf das Install-Icon öffnet ihn. Erst „Löschen" entfernt die Datei endgültig vom Gerät; existiert die zwischengespeicherte Datei nicht mehr (z. B. manuell gelöscht), zeigt die Zeile wieder das Download-Icon und ein Tap lädt neu herunter.

Während eines Downloads zeigt die betroffene Zeile statt des Download-Icons einen `LinearProgressIndicator` mit Live-Text darunter: Prozentsatz, Download-Geschwindigkeit (B/s, KB/s oder MB/s) und verbleibende Zeit (sofern die Serverantwort eine Content-Length liefert – sonst ein unbestimmter Balken nur mit Geschwindigkeit). Das Text-Label wechselt je Phase: „Wird heruntergeladen…" → „Wird überprüft…" (Datei wird nach Abschluss auf Vollständigkeit geprüft) → „Wird installiert…" bei `.apk`-Dateien bzw. „Wird geöffnet…" bei allen anderen Dateitypen.

## Entwicklung

Liste aller im aktuellen Projekt konfigurierten Agenten (`manifest.agents`: Command + Beschreibung). Tippen auf einen Agenten öffnet den Ausführungs-Screen:

- **Agent** und **Projekt** sind fix (aus der Auswahl übernommen), nur zur Anzeige – keine weitere Auswahl nötig.
- **Kontext** (optional): Dropdown aus den unter Einstellungen → Kontexte gepflegten Presets, Default „Kein Kontext". Ist ein Kontext gewählt, wird sein Wert vor den eingegebenen Prompt gehängt (`<Kontext-Wert>\n\n<Prompt>`), bevor der Befehl an den Server geht.
- **Model** (optional): Dropdown Standard/`haiku`/`sonnet`/`opus`/`fable`, Default vorausgewählt.
- **Prompt**: Mehrzeiliges Textfeld, optional – nur Pflichtfeld, wenn kein Kontext gewählt ist. Ist ein Kontext gewählt und der Prompt leer, wird nur der Kontext-Wert gesendet.

„Starten" ruft `POST /<agent>` auf und öffnet die Status-Detailseite.

## Dev-Kontexte (Einstellungen)

Unter Einstellungen → Kontexte lassen sich beliebig viele Name+Wert-Presets anlegen, bearbeiten und löschen (lokal in Room gespeichert). Sie dienen ausschließlich dazu, beim Start eines Agenten unter „Entwicklung" vor den Prompt gehängt zu werden (siehe oben) – kein Server-seitiges Konzept.

## Command-Status (live)

Abonniert `GET /state/<id>/stream` (Server-Sent Events): Output-Updates kommen dadurch als Push, ohne festes Intervall, sobald der Server sie hat. Bricht die Stream-Verbindung ab, bevor ein Endstatus (`completed`/`failed`/`stopped`) ankam, fällt die App automatisch auf Polling von `GET /state/<id>` alle 2 Sekunden zurück, bis `status != "running"` ist – z. B. wenn ein Proxy zwischen App und `cl server` lang laufende Verbindungen kappt. Zeigt Status-Pill, Agent/Command-Text (daneben ein Icon-Button kopiert den Command-Text/Prompt in die Zwischenablage, bestätigt per Toast „Input kopiert"), den Startzeitpunkt (`createdAt`, Geräte-Zeitzone) sowie die Laufzeit – solange der Command läuft, tickt sie lokal sekündlich hoch (`createdAt` bis aktuelle Gerätezeit), unabhängig davon, ob gerade neuer Output/`updatedAt` vom Server kommt; nach Abschluss friert sie auf `updatedAt - createdAt` ein –, den bisherigen Output live (monospace) und den Exit-Code nach Abschluss. Neben dem „Output"-Titel kopiert ein Icon-Button den kompletten Output in die Zwischenablage (`LocalClipboardManager`), bestätigt per Toast „Output kopiert". Wurde die Seite mit einem Projektnamen geöffnet und hat dieses Projekt `hosted`-Datei-Einträge, erscheinen nach erfolgreichem Abschluss zusätzlich Schnellzugriff-Download-Buttons (inkl. Fortschrittsanzeige, Install-Icon für bereits heruntergeladene APKs und Installieren/Teilen/Löschen-Dialog, siehe „Downloads").

**Laufenden Command stoppen:** Solange `status == "running"` ist, zeigt die Seite einen „Befehl stoppen"-Button. Nach Bestätigung (`AppConfirmDialog`, „Befehl stoppen?") ruft die App `POST /state/<id>/stop` auf, das den Server-Subprozess per `SIGTERM` beendet; der resultierende Status „stopped" kommt wie gewohnt über den laufenden Stream/das Polling zurück, kein optimistisches Update im Client. Schlägt der Aufruf fehl (z. B. weil der Command inzwischen bereits abgeschlossen ist), erscheint ein Fehler-Banner.

## Verlauf

Erreichbar über den „Verlauf"-Eintrag im Projekt-Hub, immer auf das aktuelle Projekt beschränkt (`GET /commands/<pathName>`). Zeigt alle bisher für dieses Projekt abgeschickten Commands (Agenten-Läufe aus „Entwicklung" **und** Befehle) als Liste – Status-Pill, Agent, Command-Text (gekürzt), Zeitstempel und Laufzeit, neueste zuerst. Pollt alle 3 Sekunden automatisch neu, solange der Screen offen ist – neue Commands (aus „Befehle"/„Entwicklung" gestartet oder per Ticket-Play ausgelöst) erscheinen also ohne manuellen Refresh. Tippen auf einen Eintrag öffnet dieselbe Status-Detailseite wie „Befehle"/„Entwicklung" (Live-Output, Downloads).

**Agenten-Lauf neu starten:** Abgeschlossene Einträge (Status „completed", „failed" oder „stopped") aus „Entwicklung" zeigen zusätzlich ein Wiederholen-Icon – nicht nur fehlgeschlagene, auch erfolgreich beendete Läufe lassen sich erneut anstoßen. Tippen öffnet den „Entwicklung"-Ausführungs-Screen für denselben Agenten und Projekt, mit dem ursprünglich gesendeten Prompt bereits vorausgefüllt – abgeschickt wird erst nach erneutem, manuellem Tippen auf „Starten". Gilt nicht für laufende Einträge und nicht für Befehle (`Befehle`-Einträge sind feste Shell-Kommandos ohne freien Prompt zum Vorausfüllen).

## Tickets

Erreichbar über den „Tickets"-Eintrag im Projekt-Hub, immer auf das aktuelle Projekt beschränkt (`GET/POST /tickets/<pathName>`).

Ein Ticket besteht aus: der **Original-Anweisung**, einer **Zusammenfassung**, einer **Claude-Anweisung**, einer **Kategorie**, einem **Status** (Wird generiert/Offen/In Bearbeitung/Fertig/Abgelehnt) und der **IP-Adresse** des anlegenden Clients. ID, Pfadname und IP-Adresse sind nach dem Anlegen nicht mehr änderbar.

**Liste:** Textfeld + „Ticket erstellen" ruft `POST /tickets/<pathName>` auf; der Server legt das Ticket sofort im Status „Wird generiert" an (leere Zusammenfassung/Claude-Anweisung/Kategorie) und füllt es im Hintergrund per Ticket-Agent. Ein Ticket in diesem Status erscheint als ladende Zeile (Spinner statt Kategorie-Text, Original-Anweisung als Titel) in derselben Liste wie alle anderen Tickets – kein separater lokaler Platzhalter mehr. Solange mindestens ein geladenes Ticket im Status „Wird generiert" ist, aktualisiert `TicketListViewModel` die Liste einmal pro Sekunde automatisch, bis der Agent fertig ist (Ticket wechselt dann auf „Offen" bzw. bei Fehlschlag auf „Abgelehnt" mit der Fehlermeldung als Zusammenfassung). Status-Dropdown filtert die Liste (inkl. „Wird generiert"). Lädt bei jedem (Wieder-)Betreten automatisch neu.

**Detail:** Alle Felder außer Pfadname/IP-Adresse editierbar, „Speichern" (`PATCH`), ein „Ausführen und schließen"-Button startet den Play-Ablauf (siehe unten), „Ticket löschen" (`DELETE`, kehrt danach zur Liste zurück).

**Ticket ausführen (Play-Button):** Öffnet einen Bestätigungsdialog mit Agenten-Auswahl (Dropdown aus `manifest.agents`, vorausgewählt ist `cl dev`, falls konfiguriert, sonst der erste Agent). Nach Bestätigung wird die `claudeInstruction` des Tickets per `POST /<agent>` an den gewählten Agenten geschickt (Pfad = `ticket.pathName`, kein Model-Override) und die App wechselt zur Status-Detailseite des gestarteten Commands. Erst nach erfolgreichem Start des Commands wird das Ticket per `PATCH` auf Status „Fertig" gesetzt; schlägt nur das Schließen fehl (der Command läuft aber bereits), bleibt das Ticket offen und muss manuell geschlossen werden.

## Feedback

Erreichbar über den „Feedback"-Eintrag im Projekt-Hub – zeigt nur das Feedback des aktuellen Projekts (`GET /feedback/<pathName>`). Welchem Projekt ein Feedback-Eintrag zugeordnet ist, entscheidet der Server automatisch anhand seines `section`-Werts (siehe `../FEATURES.md`, Feedback-System) – die App selbst schickt beim Laden nur den aktuellen Projektnamen mit.

Liste der Feedback-Einträge dieses Projekts, neueste zuerst. Ist ein Eintrag mit einem Abschnitt (z. B. dem Namen einer Datei in `app-getter`) und/oder einem Kontext (Freitext, z. B. APK-Name und Zeitstempel) verknüpft, werden diese unter dem Text angezeigt. Pro Eintrag:

- **Bearbeiten:** Mehrzeiliges Textfeld (Textarea) ersetzt den Text – Feedback darf beliebig lang sein –, „Speichern" (`PATCH /feedback/<id>`).
- **Löschen:** Bestätigungsdialog, danach `DELETE /feedback/<id>`.
- **In Ticket umwandeln:** Öffnet einen Dialog mit Projekt-Auswahl (Dropdown aus `manifest.paths`, vorausgewählt ist das aktuelle Projekt, falls es in der Liste vorkommt). Nach Bestätigung wird der Feedback-Text unverändert per `POST /tickets/<pathName>` als neues Ticket angelegt und der Feedback-Eintrag anschließend gelöscht (`DELETE /feedback/<id>`).

Feedback kann aus der App heraus **nicht angelegt** werden – `POST /feedback` ist für externe Absender gedacht (allen voran `app-getter`, siehe dessen `FEATURES.md`, das automatisch einen Abschnitt mitschickt).

## Sammlung

Erreichbar über den „Sammlung"-Eintrag im Projekt-Hub. Ein einzelner Button „Sammeln" löst `cl server`s Collection-Feature nur für das aktuelle Projekt aus (`POST /collect/<pathName>`) – sammelt also alle `config.json`-`collection`-Einträge, deren `path` zum aktuellen Projekt passt. Das Ergebnis (`CollectSummary`) wird als Erfolgs-/Fehler-Banner pro Eintrag angezeigt.

## Statistik

Erreichbar über den „Statistik"-Eintrag im Projekt-Hub, immer auf das aktuelle Projekt beschränkt (`GET /stats/<pathName>`). Zeigt vier Kennzahlen als einfache Zeilen (Label + Wert):

- **Laufende Agents:** Anzahl aktuell laufender Agenten-Läufe dieses Projekts (keine Pfad-Commands).
- **Agents (letzte 24 Std.):** Anzahl der Agenten-Läufe dieses Projekts, die innerhalb der letzten 24 Stunden gestartet wurden (festes Zeitfenster, keine Auswahl-UI).
- **Letzter Debug-Build:** Zeitpunkt der zuletzt geänderten Debug-APK im Projektverzeichnis (Geräte-Zeitzone, `formatTimestamp`), sonst „Kein Build vorhanden".
- **Letzter Release-Build:** wie oben, für die Release-APK.

Lädt einmalig beim Öffnen (kein Live-Polling); Ladezustand und Fehleranzeige wie bei den übrigen Screens.

## Einstellungen

Theme-Umschalter (System/Hell/Dunkel), Kontexte-Verwaltung (siehe oben), Server-Konfiguration (siehe unten), Verbindungsanzeige mit „Verbindung trennen" (löscht Verbindung **und** das gemerkte Projekt, führt zurück zum Setup-Screen).

## Server-Konfiguration (Einstellungen)

Unter Einstellungen → Server-Konfiguration lässt sich die auf `cl server` laufende `config.json` remote bearbeiten, ohne Zugriff auf das Server-Dateisystem – nutzt `../FEATURES.md`s `/config`-Endpunkte (siehe dort, Abschnitt „Config-Editierung + Versionshistorie"):

- Ein mehrzeiliges Textfeld zeigt die aktuelle Config als formatiertes JSON (`GET /config`). „Speichern" prüft zuerst lokal, ob der Text gültiges JSON ist (verhindert einen unnötigen Server-Roundtrip bei Tippfehlern), sendet ihn danach per `PUT /config`. Ein ungültiger Inhalt (lokal oder vom Server abgelehnt, z. B. wegen reservierter Agent-Namen) zeigt ein Fehler-Banner, ohne den bisherigen Text zu verwerfen.
- Änderungen wirken **sofort** auf dem Server, ohne Neustart – ändert sich dabei `databaseDirectory`, zeigt ein zusätzliches Hinweis-Banner, dass dafür ein Server-Neustart nötig ist.
- „Versionsverlauf" öffnet eine Liste aller gespeicherten Versionen (`GET /config/versions`, neueste zuerst) plus die feste „Eingebettete Version" (die zur Build-Zeit reinkompilierte Version), mit einem Häkchen bei der aktuell aktiven (`GET /config/pointer`, `null` = eingebettete Version). Tippen auf eine andere Version fragt per Dialog nach und aktiviert sie danach sofort (`PUT /config/pointer`) – ein Rollback, ohne die Versionshistorie selbst zu verändern.

## Netzwerk

`cl server` spricht ausschließlich Klartext-HTTP – `usesCleartextTraffic` ist global erlaubt. Alle authentifizierten Aufrufe laufen über `data/api/ClServerApi.kt` (OkHttp + kotlinx.serialization) mit `Authorization: Bearer <JWT>`-Header.
