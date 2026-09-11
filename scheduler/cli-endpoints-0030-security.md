/goal Mache ein ausführliches Review der gesamten App, aber achte dabei ausschließlich auf folgende Aspekte:

1. Gibt es sicherheitslücken in der App?
2. Werden Versionen von Libraries verwendet, die sicherheitslücken enthalten?
3. Sind alle OWASP Schwachstellen bedacht?
4. Ist die App gegen hacking abgesichert? z.b durch infizierte Json oder PDF-Dateien oder sonstiges?


Wenn du alles geprüft hast, erstelle unter review/inconsistencies-<timestamp>.md eine Datei mit allen Findings.
Das Ziel ist erreicht, wenn du alle Findings aus dieser Datei auch behoben hast. Behebungen, die breaking Changes enthalten, dokumentiere in der todo.md und setze sie hier nicht um. Findings, die bereits vorher in der todo.md standen, sollen auch nicht in der local-review Datei sein.

Dokumentiere in der inconsistencies-...md Datei, welche der Findings du behoben hast.

