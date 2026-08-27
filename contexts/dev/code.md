## Muss-Anforderungen an geschriebenen code
- Keine Kommentare im Code
- Verwende nur bibliotheken, die kommerziell kostenlos nutzbar sind
- Schreibe für jedes implementierte Feature Unit-Tests - mindestens 80% coverage, am besten 100%
- Alle Usecases und edgecases getestet
- Neuste Technologien verwendet
- Neue Versionen von allen Tools
- Clean Code und best practices beachtet
- Saubere Software-Architektur
- Code immer auf Englisch
- Keine Libraries mit bekannten Schwachstellen
- Nur Libraries benutzen, die kommerzielle verwendung erlauben
- Keine Inhalte, die urheberrechtlich geschützt sind
- Zu verwendeten Bildern oder ähnlichem immer eine Quelle hinterlegen
- Keine UI Strings im Code, alles über i18n in xml dateien
- Alle Datenbank-Queries sind effizient/performants
- Alle komplexen Berechnungen sind effizient/performant
- Keine OWASP-Top-10-Schwachstellen (SQL-Injection, XSS, Command-Injection etc.)
- Tests müssen immer laufen
- Die Tests sind die Source of truth. Wenn sie fehlschlagen ist der Code falsch, nicht die tests
- Verwende niemals alpha oder beta versionen von eingebundenen Bibliotheken
- Keine deprecated Inhalte verwenden
- Jede Datenbankmigration MUSS vollständig durch Tests abgedeckt sein
- Du kümmerst dich nur um Tests, die dein eigenes Feature betreffen. alle anderen darfst du nicht ausführen, außer du wirst aufgefordert

## Pfadfinder-Regel
Auch wenn es nicht dein Feature betrifft, aber wenn du auf eines der folgenden Probleme stößt, behebe es gleich mit:
- Veraltete Versionen, die eine neuere haben ohne Breaking Changes
- Sicherheitslücken
- Bugs
- Nicht passendes Verhalten der App
- Verstoß gegen Copyrights
- Lags
- Schlechte software-Architektur

