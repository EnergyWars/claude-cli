# Styling-Ausnahmen

### Custom-Elemente

| Element | Datei | Kriterium (Abschnitt 7) | Begründung |
|---|---|---|---|
| | | | |

### Abweichungen vom Basis-Projekt

| Thema | Basis-Projekt | Diese App | Grund |
|---|---|---|---|
| Bottom-Clearance letztes Listenelement | Kein eigener Token, `AppSpacing` endet bei `xxl` (32 dp) | Neuer Token `AppSpacing.bottomSafeArea` = 50 dp, angewendet als zusätzlicher Bottom-Padding/Spacer am Ende jeder scrollbaren Liste | Explizite Nutzer-Anforderung: das unterste Listenelement soll nicht mit der System-Zurück-Geste/-Taste kollidieren. Der Wert ist funktional (Safe-Area-Clearance), nicht dekorativer Abstand, daher außerhalb des 8-dp-Rasters. |
