## Verhaltensregeln
Rede grundsätzlich immer deutsch mit mir. Jede Markdown-Datei, die du befüllst, grundsätzlich in deutsch. Außer ich sage explizit etwas anderes.
Alle Apps sollen alle Features immer in detusch übersetzt haben mit englisch als fallback.

Nachdem du meine Anforderungen umgesetzt hast, prüfe genau den Aufbau eines Projektes:

- **FEATURES.md**: Jedes Projekt bekommt eine eigene im Projektstamm – selbstpflegend, projektspezifische Ausnahmen von globalen Regeln explizit vermerken. Die projektspezifische `FEATURES.md` enthält **alle Features der App** vollständig beschrieben; bei jeder Feature-Änderung (neu, geändert, entfernt) nach Abschluss aktualisieren.
  - Die CLAUDE.md ist zu leeren, falls sie feature Beschreibungen enthält, entsprechend ist das zu migrieren
  - Die FEATURES.md erst anpassen, nachdem ein Feature fertig ist
  - In der FEATURES.md nur nachschlagen wenn unbedingt nötig
- **.gitignore**: Mindestens eine im Projektstamm; bei Subprojekten mit abweichendem Stack zusätzliche im Unterverzeichnis. Laufend befüllen.
- **context.md**: Falls vorhanden, zuerst lesen – enthält Implementierungsstand, Dateistruktur und Architekturentscheidungen. Immer aktuell halten: nach jeder Codeänderung sofort updaten, nicht erst am Ende. Inhalt:
  - Enthält den gesamten Inhalt von "Aufbau eines Prpojektes"
  - Implementierungsstatus aller Features aus `CLAUDE.md`
  - Jedem Feature sind die zugehörigen Dateien zugeordnet
  - Bei Widersprüchen zwischen `CLAUDE.md` und `context.md` hat `CLAUDE.md` Vorrang
  - Die Datei ist unbedingt und ohne ausnahme nach dem abschluss jeder Funktions-Änderung der App zu pflegen
  - Die context.md beschreibt nur den aktuellen stand, niemals eine history
  - Nachdem etwas fertig umgesetzt wurde, muss die context.md so weit wie möglich verkürzt werden, sodass sie nur den aktuellen stand beschreibt
- Projekt ist ein Git-Projekt. Existiert kein git-Projekt, lege ein neues an
