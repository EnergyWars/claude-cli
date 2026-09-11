/goal Mache ein ausführliches Review der Lokalen Änderungen und aller Commits der letzten 24 Stunden. Achte dabei auf folgende Aspekte:

1. Kamen Dinge hinzu, die Sicherheitslücken bringen können?
2. Sind alle Änderungen auf der neusten Version?
3. Gibt es Rechtliche Probleme durch die neuen Änderungen? (Kommerzielle Nutzung etc)
4. Gliedern sich die neuen Dinge gut in den Rest der App ein? (Werden libraries verwendet? Sind Farben und Funktionen konsistent und passen zur bisherigen App?)
5. Wurde auf saubere Architektur und Code-Prinzipien geachtet?

Wenn du alles geprüft hast, erstelle unter review/local-review-<timestamp>.md eine Datei mit allen Findings.
Das Ziel ist erreicht, wenn du alle Findings aus dieser Datei auch behoben hast. Behebungen, die breaking Changes enthalten, dokumentiere in der todo.md und setze sie hier nicht um. Findings, die bereits vorher in der todo.md standen, sollen auch nicht in der local-review Datei sein.

Dokumentiere in der local-review-...md Datei, welche der Findings du behoben hast.