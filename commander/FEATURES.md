# Features

## Verbindungsaufbau (Setup)

Erster Start (keine gespeicherte Verbindung) zeigt den Setup-Screen: Felder für Host/IP und Port (Default `8787`), Button „Automatisch suchen", Button „Verbinden". Prüft Erreichbarkeit (`GET /health`) und Pairing-Status; richtet bei Bedarf einen Authenticator ein oder verifiziert ein bestehendes Secret. Nach Erfolg wird die Verbindung gespeichert und zum Login gewechselt.

### Automatische Server-Suche (Auto-Discovery)

Button „Automatisch suchen": ermittelt die eigene lokale IPv4-Adresse, scannt parallel das `/24`-Subnetz nach `GET /status` und übernimmt die erste antwortende Adresse ins Host-Feld. Der Nutzer muss danach weiterhin selbst „Verbinden" tippen.

## Login

Nach dem Verbinden fragt der Login-Screen den aktuellen 6-stelligen TOTP-Code ab und tauscht ihn gegen ein JWT (`POST /auth/login`). Läuft das Token ab, führt die App automatisch zurück zum Login. „Verbindung wechseln" führt zurück zum Setup.

## Projektauswahl

Ist noch kein Projekt gemerkt (oder nach „Verbindung trennen"), zeigt die App direkt nach dem Login eine reine Liste aller Projekte (`GET /manifest`.`paths`) – nichts weiter. Tippen auf einen Eintrag merkt sich das Projekt dauerhaft (DataStore) und öffnet den Projekt-Hub. Diese Auswahl bleibt bestehen, bis sie explizit geändert wird (Projekt-Dropdown im Hub) oder die Verbindung getrennt wird.

## Projekt-Hub

Zentrale Seite nach der Projektauswahl. Oben ein Dropdown mit dem aktuellen Projektnamen – Tippen öffnet die Liste aller Projekte, Auswahl wechselt sofort um und persistiert. Daneben ein Zahnrad-Icon zu den Einstellungen. Darunter genau vier Einträge, jeder öffnet einen eigenen, auf seinen Zweck beschränkten Screen:

- **Befehle** – alle Commands des aktuellen Projekts.
- **Downloads** – alle downloadbaren Dateien des aktuellen Projekts.
- **Dev-Agent** – alle konfigurierten Agenten des aktuellen Projekts.
- **Tickets** – die Ticket-Liste dieses Projekts.

Es gibt keine weiteren Einträge oder Querverweise zwischen diesen vier Bereichen.

## Befehle

Liste der `path.commands`-Einträge (Anzeigename + Beschreibung) des aktuellen Projekts. Tippen führt den Befehl aus (`POST /paths/<path>/commands/<key>`) und öffnet die Status-Detailseite. Sonst nichts auf diesem Screen.

## Downloads

Liste der `path.hosted`-Einträge des aktuellen Projekts. `type: "file"`-Einträge haben einen Direkt-Download-Button; `type: "path"`-Einträge lassen sich aufklappen und einzelne enthaltene Dateien herunterladen. Endet der heruntergeladene Dateiname auf `.apk`, installiert die App das Paket direkt (System-Install-Dialog inkl. einmaligem „Unbekannte Quellen erlauben", falls noch nicht freigegeben); alle anderen Dateien werden wie bisher zum Öffnen/Teilen angeboten. Downloads landen unter `getExternalFilesDir(DIRECTORY_DOWNLOADS)` (kein Speicher-Runtime-Permission nötig).

## Dev-Agent

Liste aller im aktuellen Projekt konfigurierten Agenten (`manifest.agents`: Command + Beschreibung). Tippen auf einen Agenten öffnet den Ausführungs-Screen:

- **Agent** und **Projekt** sind fix (aus der Auswahl übernommen), nur zur Anzeige – keine weitere Auswahl nötig.
- **Kontext** (optional): Dropdown aus den unter Einstellungen → Kontexte gepflegten Presets, Default „Kein Kontext". Ist ein Kontext gewählt, wird sein Wert vor den eingegebenen Prompt gehängt (`<Kontext-Wert>\n\n<Prompt>`), bevor der Befehl an den Server geht.
- **Model** (optional): Dropdown Standard/`haiku`/`sonnet`/`opus`/`fable`, Default vorausgewählt.
- **Prompt**: Mehrzeiliges Textfeld.

„Starten" ruft `POST /<agent>` auf und öffnet die Status-Detailseite.

## Dev-Kontexte (Einstellungen)

Unter Einstellungen → Kontexte lassen sich beliebig viele Name+Wert-Presets anlegen, bearbeiten und löschen (lokal in Room gespeichert). Sie dienen ausschließlich dazu, beim Dev-Agent-Start vor den Prompt gehängt zu werden (siehe oben) – kein Server-seitiges Konzept.

## Command-Status (live)

Pollt `GET /state/<id>` alle 2 Sekunden, solange `status == "running"`. Zeigt Status-Pill, Agent/Command-Text, den bisherigen Output live (monospace) und den Exit-Code nach Abschluss. Wurde die Seite mit einem Projektnamen geöffnet und hat dieses Projekt `hosted`-Datei-Einträge, erscheinen nach erfolgreichem Abschluss zusätzlich Schnellzugriff-Download-Buttons (inkl. automatischem APK-Install, siehe „Downloads").

## Tickets

Erreichbar über den „Tickets"-Eintrag im Projekt-Hub, immer auf das aktuelle Projekt beschränkt (`GET/POST /tickets/<pathName>`).

Ein Ticket besteht aus: der **Original-Anweisung**, einer **Zusammenfassung**, einer **Claude-Anweisung**, einer **Kategorie**, einem **Status** (Offen/In Bearbeitung/Fertig/Abgelehnt) und der **IP-Adresse** des anlegenden Clients. ID, Pfadname und IP-Adresse sind nach dem Anlegen nicht mehr änderbar.

**Liste:** Textfeld + „Ticket erstellen" legt sofort einen lokalen Platzhalter „Lädt Ticket …" an und feuert die Erstellung im Hintergrund (kann mehrere Minuten dauern) – die Liste bleibt bedienbar und aktualisiert sich einmal pro Sekunde, solange ein Platzhalter existiert. Status-Dropdown filtert die Liste. Lädt bei jedem (Wieder-)Betreten automatisch neu.

**Detail:** Alle Felder außer Pfadname/IP-Adresse editierbar, „Speichern" (`PATCH`), „Ticket löschen" (`DELETE`, kehrt danach zur Liste zurück).

## Einstellungen

Theme-Umschalter (System/Hell/Dunkel), Kontexte-Verwaltung (siehe oben), Verbindungsanzeige mit „Verbindung trennen" (löscht Verbindung **und** das gemerkte Projekt, führt zurück zum Setup-Screen).

## Netzwerk

`cl server` spricht ausschließlich Klartext-HTTP – `usesCleartextTraffic` ist global erlaubt. Alle authentifizierten Aufrufe laufen über `data/api/ClServerApi.kt` (OkHttp + kotlinx.serialization) mit `Authorization: Bearer <JWT>`-Header.
