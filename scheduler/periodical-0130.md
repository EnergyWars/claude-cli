/goal Mache ein ausführliches Review der gesamten App, aber achte dabei ausschließlich auf folgende Aspekte:

1. Ist die Softwarearchitektur sauber?
2. Ist alles, was in der lib sein könnte auch wirklich in der Lib?
3. Gibt es doppelte Implementierungen, die man in die Lib auslagern kann?
4. Werden veraltete dependencies verwendet?


Wenn du alles geprüft hast, erstelle unter review/architecture-<timestamp>.md eine Datei mit allen Findings.
Das Ziel ist erreicht, wenn du alle Findings aus dieser Datei auch behoben hast. Behebungen, die breaking Changes enthalten, dokumentiere in der todo.md und setze sie hier nicht um. Findings, die bereits vorher in der todo.md standen, sollen auch nicht in der local-review Datei sein.

Dokumentiere in der architecture-...md Datei, welche der Findings du behoben hast.