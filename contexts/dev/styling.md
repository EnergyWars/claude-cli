# Styling-Regeln für WaffleHQ-Apps

Verbindlich für jede UI-Änderung in jeder WaffleHQ-Android-App (Jetpack Compose, Material 3). Alle Apps teilen dasselbe Design; diese Datei beschreibt es unabhängig von einer konkreten App. Gilt für neuen Code sofort. Bestehende Screens werden nicht massenhaft migriert, aber jedes Element, das angefasst wird, wird beim Anfassen auf diese Regeln gezogen.

Rangfolge bei Widersprüchen: App-eigene `CLAUDE.md` → diese Datei → Basis-Projekt → bestehender App-Code.

Referenzen:

| Quelle | Wo |
|---|---|
| Basis-Projekt (Vorgabe für alles, was die Library nicht abdeckt) | Skill `base-project`; darin `uikit/src/main/java/com/wafflehq/uikit/` mit `components/App*.kt`, `theme/AppTokens.kt`, `theme/AppShapes.kt`, `theme/Type.kt`, `navigation/`, `folders/`, `quickpicker/`, `textarea/`, `entrylock/` |
| Showcase (visuelle Referenz mit IDs) | `uikit/.../showcase/Section{NN}*.kt`, IDs siehe Abschnitt 8 |
| Generische UI-Library | Die `uikit`-Bausteine des Basis-Projekts. Eine App darf sie in Teilmodule aufteilen; die Namen der Bausteine (`AppButton`, `AppCard`, `SettingsScaffold` …) bleiben gleich |
| Prüfskript | `verify-theme.sh` aus dem Basis-Projekt, in jeder App mit angepassten Pfaden; muss nach jeder UI-Änderung grün sein |

---

## 1. Die drei Grundregeln

1. **Library zuerst.** Gibt es für ein Element einen Baustein in der generischen UI-Library, wird ausschließlich dieser verwendet. Kein rohes Material-3-Pendant, kein Nachbau im Screen. Die Zuordnung Element → Baustein steht in Abschnitt 3.
2. **Sonst Basis-Projekt.** Fehlt der Baustein in der Library der App, gibt das Basis-Projekt den Stil vor (`components/App*.kt`, Token-Mapping in `AppTokens.kt`, Showcase). Der Baustein wird dann **nach Vorbild des Basis-Projekts in die Library der App portiert** (inklusive Unit-Test) und danach vom Screen verwendet. Er wird nicht einmalig im Screen nachgebaut. Fehlt er auch im Basis-Projekt, wird er dort ergänzt oder nach dem Token-Mapping aus `AppTokens.kt` und der passenden Showcase-Sektion als Library-Baustein gebaut.
3. **Custom nur mit Begründung.** Was weder Library noch Basis-Projekt definieren, darf nur als Custom-Element gebaut werden, wenn es die Kriterien in Abschnitt 7 erfüllt und im App-eigenen Register (Abschnitt 9) mit Begründung eingetragen ist. Ohne Eintrag ist ein Custom-Element ein Regelverstoß.

Daneben: **Primary-Stil ist der Standard.** Jedes Bedienelement, das eine Aktion oder einen aktiven Zustand ausdrückt, verwendet die Rolle Primary in der Tonal-Ausprägung (Showcase 6a.2 für Buttons, 7a.2 für den FAB). Andere Rollen nur mit semantischem Grund (Abschnitt 4).

---

## 2. Token-Regeln

### Farben
- Sieben semantische Rollen: **Primary, Secondary, Tertiary, Success, Warning, Error, Neutral**. Jede Rolle hat fünf Slots (`RoleColors`): `accent`, `onAccent`, `container`, `onContainer`, `tonalBorder`. Dazu Flächen: `background`, `surface`, `surfaceVariant`, `surface3`, `outline` und die zugehörigen `on*`-Farben.
- Zugriff nur über `AppTheme.colors`/`AppTheme.tokens` bzw. `MaterialTheme.colorScheme.*`. App-spezifische Zusatzfarben (z. B. Modul- oder Kategoriefarben) laufen über den Token-Mechanismus des App-Themes, nie als Literal im Screen.
- Keine Hex-Literale (`Color(0x…)`), keine Compose-Konstanten (`Color.Red`, `Color.Gray` …) außerhalb der Palette-/Theme-Datei. Erlaubt bleiben `Color.Transparent` sowie `Color.White`/`Color.Black` ausschließlich als Kontrast auf Fremdflächen (Fotos, Kamera-Overlay).
- Rohe Rampen-Namen (`Sapphire40`, `Garnet80` …) und Surface-Konstanten werden nur in Palette und Theme referenziert. `verify-theme.sh` erzwingt das.
- Kein Dynamic Color, kein Material You. Die App sieht auf jedem Gerät gleich aus.
- Alpha-Werte (nicht neu erfinden):

| Zweck | Wert |
|---|---|
| Rahmen einer Outlined-Karte / dezenter Divider | `outline.copy(alpha = 0.25f)` |
| Deaktivierter oder abgeblendeter Inhalt (Icons im Leerzustand, inaktive Chips) | 0.38f |
| Getönte Fläche als Hintergrund | `surfaceVariant` ohne Alpha; wenn dezenter nötig, `container` der Rolle |

### Form (Eckenradius)
Einzige Quelle ist `AppRadius`:

| Element | Token | Wert |
|---|---|---|
| Karte, Banner, Menü, Container-Fläche | `AppRadius.card` | 12 dp |
| Button (alle Varianten), Extended FAB | `AppRadius.button` | 20 dp |
| Chip | `AppRadius.chip` | 8 dp |
| Textfeld | `AppRadius.textField` | 4 dp |
| Dialog | `AppRadius.dialog` | 28 dp |
| Bottom Sheet (obere Ecken) | `AppRadius.sheet` | 28 dp |
| Status-Pill, Zähl-Badge | `AppRadius.pill` | 999 dp |

`MaterialTheme.shapes.*` und `RoundedCornerShape(N.dp)` mit Literal sind in App-Code nicht erlaubt. `CircleShape` bleibt für runde Icon-Flächen zulässig. Hängt eine App eigene `Shapes` ins `MaterialTheme`, müssen deren Werte den `AppRadius`-Werten entsprechen.

### Abstand
- 8-dp-Raster, Werte ausschließlich über `AppSpacing`: `xs` 4, `sm` 8, `md` 12, `lg` 16, `xl` 24, `xxl` 32.
- Standardzuordnung: Karten-Innenabstand `lg` (16) bei Inhalt, `md` (12) bei reinen Listenzeilen; Abstand zwischen Karten `md` (12); Abschnittsabstand `lg` (16); Chip-Abstand `sm` (8); Icon-zu-Text `sm` (8); Screen-Rand `lg` (16).
- Platz für den FAB am Listenende über die Library-Konstante dafür (`FabClearance` oder Äquivalent), nie als Literal.
- Dp-Literale sind nur für Größen erlaubt, die kein Abstand sind (Icon 18/24 dp, Farbfeld 10 dp, Diagramm-Geometrie).

### Schrift
- Hausschrift **Geist** (`GeistSans`, Gewichte Light/Regular/Medium/SemiBold/Bold) und **Geist Mono** für tabellarische Zahlen und Code. Schriftdateien liegen unter `res/font/geist_*.ttf`. Tabular-Ziffern (`tnum`) sind in der Typografie aktiviert.
- Nur `MaterialTheme.typography.*`. Kein `fontSize = N.sp`, keine eigene `TextStyle(...)` in Screens.
- Zuordnung: Screen-Titel `titleLarge`; Karten-/Abschnittstitel `titleMedium`; Zeilen-Titel `titleSmall`; Fließtext `bodyMedium`; Sekundärtext/Meta `bodySmall` in `onSurfaceVariant`; Button-/Chip-Label `labelLarge`; Kleinst-Label `labelSmall`; große Kennzahlen `headlineSmall`/`displaySmall`.
- Gewichte: Display/Headline 700, Title/Label 600, Body 400. Zusätzliches `fontWeight` nur, um innerhalb eines Stils eine Hervorhebung zu setzen.
- Einzige zulässige Ausnahme: dichte Gitterzellen (z. B. Kalender), deren Schriftgröße vom Nutzer skalierbar ist. Die Skalierung läuft dann über ein `CompositionLocal` des App-Themes, nicht über Literale.

### Elevation, Effekte, Icons
- Tonal-Elevation 0–1 dp. Karten flach (0 dp). Menüs: Tonal 6 dp, Schatten 4 dp, 1 dp `outline`-Rahmen, Hintergrund `surface`. FAB behält den Material-Schatten.
- Keine Gradienten, kein Blur/Glas, keine großen Drop-Shadows.
- Icons nur aus `androidx.compose.material.icons` (Core oder Extended). Kern-Navigation und Titelleisten: `Icons.Outlined.*`; Modul-/Bereichs-Icons (Drawer, Modulseite, Onboarding): `Icons.Filled.*`. Keine eigenen SVGs außer Launcher.

### Copy und Sprache
- Sentence case, knapp, kein Marketing, keine Ausrufezeichen. Bestätigungen als Frage („Eintrag löschen?"). Inline-Symbole erlaubt: `✓ ✗ · … ± ≈ Ø`.
- Leerzustand zweizeilig: was fehlt + was zu tun ist („Noch keine Einträge.\nTippe auf + für den ersten Eintrag.").
- Deutsch zuerst (`values-de/strings.xml`), Englisch als Fallback (`values/strings.xml`). Kein UI-String im Code. Library-Strings mit Modul-Präfix, Zugriff aus der App nur über R-Alias.

---

## 3. Element → Baustein (Pflichtzuordnung)

Spalte „Roh-M3 erlaubt?" sagt, ob das Material-3-Element ohne Wrapper zulässig ist. „Nein" heißt: nur der Library-Baustein. Ist ein genannter Baustein in der App noch nicht vorhanden, gilt Grundregel 2 (portieren, nicht nachbauen).

| Element | Baustein | Standard-Ausprägung | Roh-M3 erlaubt? |
|---|---|---|---|
| Button mit Text | `AppButton` | `role = Primary`, `variant = Tonal` | Nein |
| Icon-Button | `AppIconButton` | `Neutral`/`Standard` in Titelleisten; `Primary`/`Tonal` als eigenständige Aktion | Nein |
| FAB | `AppFab` / `AppExtendedFab` (Library-Baustein nach Showcase 7a; `container` + `onContainer` der Rolle) | `role = Primary` (7a.2) | Nein |
| Dropdown-/Überlaufmenü | `AppDropdownMenu`, `AppExposedDropdownMenu` (Einträge: `DropdownMenuItem`) | Menü-Spezifikation aus Abschnitt 2 | Nein |
| Slider | `AppSlider` | – | Nein |
| Einzeiliges Textfeld | `AppTextField` (`singleLine`) bzw. `OutlinedTextField` mit `shape = RoundedCornerShape(AppRadius.textField)` | Fokus-Rahmen/-Label `primary.accent`, Ruhe-Rahmen `outline`, Fehler `error.accent` | Nur mit `AppRadius.textField` und ohne weitere Farb-Overrides |
| Mehrzeiliges Textfeld | `KeyboardAwareTextArea`, Höhe über `heightIn(min = …)` | – | Nein |
| Datum / Uhrzeit | Quickpicker-Bausteine (`QuickDateInput*`, `QuickTimeInput*`, `TimePickerField`), Anzeige-Feld über die Library-Farbhelfer für leere/deaktivierte Felder | schneller Picker wenn in der App aktiviert, sonst System-Picker | Nein |
| Dropdown-Feld in Formularen/Settings | `SettingsDropdownField` bzw. `ExposedDropdownMenuBox` + `AppExposedDropdownMenu` | – | Nein |
| Schalter / Checkbox / Radio | `Switch`, `Checkbox`, `RadioButton` mit Standardfarben (Primary); in Settings-Listen `SettingsSwitchRow` | – | Ja, ohne `colors`-Overrides |
| Karte, neutral (Inhalt) | `AppCard(variant = Outlined)`: `surface`, `AppRadius.card`, 1 dp `outline` @ 0.25 | Outlined | Nein |
| Karte, hervorgehoben (aktiver Zustand, Zusammenfassung) | `AppCard(role = Primary, variant = Filled)`: `primary.container` + `onContainer`, kein Rahmen | Primary Filled | Nein |
| Eintrag in Ordner-Listen | `FolderEntryCard`, Ordner `FolderCard`, Baum `folderTreeItems`, Abstände `FolderListDefaults` | – | Nein |
| Listenzeile in Settings/Konfiguration | `SettingsGroup` + `SettingsListRow` / `SettingsSwitchRow` / `SettingsDropdownField` / `SettingsSliderControl`, getrennt durch `SettingsGroupDivider` | – | Nein |
| Chip | `AppChip(variant = Filter/Assist/Input/Suggestion)` | `role = Primary` | Nein |
| Dialog | `AppDialog`: `AppRadius.dialog`, `surface`, Icon `primary.accent`, Titel `onSurface`, Text `onSurfaceVariant`, 1 dp tonal | Bestätigen `AppButton(Primary, Tonal)`, Abbrechen `AppButton(Neutral, Text)` | Nein |
| Verwerfen-Rückfrage im Editor | `DiscardChangesDialog` (Library) | – | Nein |
| Ordner anlegen/umbenennen/löschen | `FolderNameDialog`, `FolderDeleteDialog` | – | Nein |
| Bottom Sheet | `ModalBottomSheet` mit `shape = RoundedCornerShape(topStart = AppRadius.sheet, topEnd = AppRadius.sheet)` | – | Nur so |
| Banner / Hinweisfläche | `AppBanner(role)`: `container`-Hintergrund, `AppRadius.card`, Innenabstand `lg`, Titel `titleSmall` in `accent`, Text `bodySmall` in `onContainer` | Primary (Info), Warning, Error, Success | Nein |
| Status-Pill / Zähl-Badge | `AppStatusPill` (`container` + `accent`-Text, `AppRadius.pill`) / `AppBadge` (`accent` + `onAccent`) | Primary | Nein |
| Fortschritt | `CircularProgressIndicator`/`LinearProgressIndicator` (Primary-Default); blockierend über den Library-Overlay-Baustein | – | Ja |
| Snackbar | `SnackbarHost` im Scaffold; keine `Toast`s | – | Ja |
| Segmented Buttons | `SingleChoiceSegmentedButtonRow` (Primary-Default) | – | Ja, ohne `colors`-Overrides |
| Sortierbare Liste | Drag-&-Drop-Bausteine der Library (`FolderDragHandle`, `DragReorderableList` oder Äquivalent) | – | Nein |
| Gesperrte Einträge | `EntryLock*`-Bausteine | – | Nein |
| Titelleiste / Navigations-Shell | `AppScaffold`/`AppHeader` bzw. `AppNavigationScaffold`/`AppTopNavBar`/`AppSideNavDrawer` | – | Nein (`TopAppBar` roh verboten) |
| Unterseite mit Zurück | `SettingsScaffold(title, onBack, backDescription)` | – | Nein |
| Vollbild-Editor | `EditorScaffold` | – | Nein |

---

## 4. Rollen und Varianten (Primary-Stil)

### Rollen
| Rolle | Wann | Farben (Tonal) |
|---|---|---|
| **Primary** (Standard) | Jede Aktion, aktiver/ausgewählter Zustand, Info-Banner, Hervorhebungs-Karte, FAB, Fokus | `primary.container` + `primary.accent` (Text/Rahmen) bzw. `onContainer` (Karteninhalt) |
| Neutral | Abbrechen/Schließen, Titelleisten-Icons, Überlaufmenü, Aktionen ohne Gewicht | `neutral.container` + `neutral.accent` |
| Error | Löschen, Verwerfen, Stoppen, Fehlerzustände, Fehler-Banner | `error.container` + `error.accent` |
| Warning | Warnhinweise, Batterie-/Berechtigungs-Themen, „Prüfen" | `warning.container` + `warning.accent` |
| Success | Bestätigte/abgeschlossene Zustände, Erfolgs-Banner | `success.container` + `success.accent` |
| Secondary / Tertiary | Nur, wenn zwei gleichrangige Aktionsgruppen auf einem Screen optisch getrennt werden müssen. Vorher prüfen, ob ein app-eigenes Zusatz-Token die bessere Antwort ist | `secondary`/`tertiary` |
| App-eigenes Zusatz-Token (z. B. Modulfarbe) | Identität eines Bereichs (Marker, Bereichs-FAB, Bereichs-Chips) über die `containerColor`/`contentColor`-Overloads der Library-Bausteine | Token der App |

### Varianten (Showcase 6a.1–6a.5)
| Variante | ID | Einsatz |
|---|---|---|
| Filled | 6a.1 | Höchstens einmal pro Screen: die abschließende Commit-Aktion eines Vollbild-Editors (Speichern in `EditorScaffold`). Sonst nicht. |
| **Tonal** | **6a.2** | **Standard** für jede sichtbare Aktion: Karten-Aktionen, Dialog-Bestätigung, Formular-Buttons, Leerzustand-Aktion. |
| Elevated | 6a.3 | Nicht verwenden (Schatten-Minimal-Prinzip). |
| Outlined | 6a.4 | Gleichrangige Alternative neben einem Tonal-Button („Importieren" neben „Neu"). |
| Text | 6a.5 | Abbrechen/Schließen (immer `Neutral`), Inline-Links in Text, Aktionen in Snackbars/Bannern. |

Dieselbe Reihenfolge gilt für alle Rollen: `6f.2` ist Error-Tonal (Löschen-Bestätigung), `6g.5` ist Neutral-Text (Abbrechen).

Der Basis-Baustein `AppDialogConfirmButton` verwendet Filled; er ist auf Tonal zu stellen, damit Dialoge dem Primary-Tonal-Standard folgen.

### FAB (Showcase 7a.1–7a.4)
Standard-FAB `AppFab(role = Primary)` = 7a.2 (56 dp, `primary.container`/`onContainer`, Material-Schatten). Small (7a.1, 40 dp) nur als zweiter, untergeordneter FAB (z. B. „Ordner anlegen" unter „Neu"). Extended (7a.4) nur, wenn das Icon allein nicht selbsterklärend ist. Large (7a.3) nicht verwenden.

### Chips
Filter/Input gewählt: `accent` + `onAccent`. Ungewählt: `container` + `accent`, Rahmen `outline`. Assist/Suggestion: `container` + `accent`. Keine Chips mit dem Material-3-Default (`secondaryContainer`); der Default ist bewusst überschrieben, `AppChip` erledigt das.

### Icon-Buttons (Showcase 8)
Standard (nur Icon in `accent`), Filled (`accent`/`onAccent`), Tonal (`container`/`accent`), Outlined (`accent`-Rahmen). Titelleiste und Listenzeilen: Standard/Neutral. Eigenständige Aktion außerhalb einer Leiste: Tonal/Primary.

---

## 5. Screen-Skelette

Neue Screens nutzen genau eines dieser Gerüste. Rohe `Scaffold` + `TopAppBar` sind in neuem Code nicht erlaubt.

| Screen-Typ | Gerüst | Aufbau |
|---|---|---|
| Hauptseite / Listen-Screen | `AppScaffold`/`AppNavigationScaffold` (Header mit Menü, Home, Settings) oder `SettingsScaffold` ohne Zurück, `floatingActionButton = { AppFab(Primary) }` | `LazyColumn(contentPadding = AppSpacing.lg, verticalArrangement = spacedBy(AppSpacing.md))`, Karten nach Abschnitt 3, letzter Eintrag `Spacer(FabClearance)`, Leerzustand siehe unten |
| Konfigurations-/Einstellungs-Screen | `SettingsScaffold(title, onBack, backDescription)` | `LazyColumn` → `item { SettingsGroup { SettingsListRow / SettingsSwitchRow / SettingsDropdownField / SettingsSliderControl, getrennt durch SettingsGroupDivider } }`. Keine manuellen Paddings. |
| Editor (Anlegen/Bearbeiten mit mehreren Feldern) | `EditorScaffold` inklusive Entwurfs-Autosave und `DiscardChangesDialog` | Felder in `Column(spacedBy(AppSpacing.lg))`, Datum/Uhrzeit über Quickpicker, Freitext über `KeyboardAwareTextArea`, Speichern-Aktion = einzige Filled-Stelle |

Kleine Eingaben (1–3 Felder) laufen als `AppDialog`, nicht als Editor-Screen.

**Leerzustand** (einheitlich): zentrierte `Column(spacedBy(AppSpacing.md))` mit `Icon` 64 dp in `onSurfaceVariant.copy(alpha = 0.38f)`, Text `bodyMedium` in `onSurfaceVariant`, `TextAlign.Center`, zweizeilige Copy; optional darunter ein `AppButton(Primary, Tonal)` für die erste Aktion. Existiert dafür ein Library-Baustein, wird er verwendet oder erweitert, nicht kopiert.

**Destruktive Aktionen**: nie direkt ausführen. Bestätigung per `AppDialog`, Bestätigen `AppButton(role = Error, variant = Tonal)`, Abbrechen `AppButton(role = Neutral, variant = Text)`.

**Validierung**: `isError` + `supportingText` am Feld; Speichern-Button `enabled = false`, solange Pflichtfelder fehlen. Keine Toasts; Fehler außerhalb von Feldern über Snackbar oder Error-Banner.

**Layout-Modifier nie strukturell umschalten.** `padding`, `offset`, `size`, `windowInsetsPadding` usw. bleiben immer in der Modifier-Kette, nur der Wert wechselt (`Modifier.padding(if (x) PaddingValues(0.dp) else p)`). Ein bedingtes Entfernen invalidiert den Positions-Cache von Compose nicht und versetzt alle Popups (Textauswahl, Cursor, `DropdownMenu`).

---

## 6. Prüfliste vor Abschluss einer UI-Änderung

1. Jedes Element in Abschnitt 3 nachgeschlagen und den vorgeschriebenen Baustein verwendet.
2. Kein rohes `Button`/`OutlinedButton`/`TextButton`/`IconButton`/`FloatingActionButton`/`DropdownMenu`/`Slider`/`TopAppBar`/`AlertDialog`/mehrzeiliges `OutlinedTextField`.
3. Rolle Primary, Variante Tonal als Standard; jede andere Rolle hat einen semantischen Grund aus Abschnitt 4.
4. Kein `MaterialTheme.shapes.*`, kein `RoundedCornerShape(N.dp)`, kein `fontSize = N.sp`, keine dp-Literale für Abstände.
5. Keine Hex-Farben, keine `Color.*`-Konstanten; `verify-theme.sh` läuft grün.
6. Neue Custom-Elemente stehen mit Begründung im App-Register (Abschnitt 9).
7. Neue Library-Bausteine haben Unit-Tests im Library-Modul und wurden, falls sie generisch sind, auch ins Basis-Projekt zurückgeführt.
8. Alle neuen Strings in beiden `strings.xml`.

---

## 7. Wann ein Custom-Element zulässig ist

Ein Element gilt als Custom, sobald es mit `Box`/`Row`/`Surface` + `background`/`border`/`clip`/`Canvas`/`drawBehind` ein Bedienelement oder eine Fläche nachbildet, die Material 3, die Library oder das Basis-Projekt bereits als Komponente kennt (Button, Chip, Karte, Badge, Listenzeile, Textfeld, Dialog, Banner).

Zulässig ist ein Custom-Element **nur**, wenn mindestens ein Kriterium erfüllt ist **und** die Begründung im App-Register steht:

- **Datenvisualisierung**: Diagramme, Kurven, Balken, Ringe, Legenden-Swatches. Kein M3-Element modelliert das.
- **Fachliche Geometrie**: Gitterzellen mit festen Größen-/Dichte-Anforderungen (Kalender, Tastenfelder, Spielbretter), die den Kern der App ausmachen.
- **Technische Unmöglichkeit**: Das M3-/Library-Element kann eine Pflichtanforderung nachweislich nicht erfüllen. Die Begründung nennt die konkrete Anforderung und warum eine Library-Erweiterung nicht möglich war.

Nicht ausreichend sind: „sieht kompakter aus", „schneller gebaut", „passt besser zur Bereichsfarbe" (dafür gibt es die `containerColor`-Overloads und Zusatz-Token), „M3-Default gefiel nicht".

Auch ein zulässiges Custom-Element hält die Token-Regeln aus Abschnitt 2 ein (Farben nur aus Theme/Token, Radius aus `AppRadius`, Abstand aus `AppSpacing`, Schrift aus `typography`).

Begründungen gehören ins Register, **nicht** als Kommentar in den Code.

---

## 8. Nachschlagetabelle Showcase

IDs stehen als `code =`/`inspectCode =`/`inspectId(…)` in `uikit/.../showcase/Section{NN}*.kt`. Schema `{Sektion}{Gruppe}.{Nr}`; Gruppe a–g = Rolle (a Primary, b Secondary, c Tertiary, d Success, e Warning, f Error, g Neutral), Nummer = Variante in Reihenfolge der Sektion.

| Sektion | Thema | Baustein |
|---|---|---|
| 1–2 | Typografie, Schrift-Gewichte | `Type.kt`, `TypeScale.kt` |
| 3–5 | Hue-Rampen, Oberflächen & Outline, Rollen-Übersicht | Palette, `Theme.kt`, `AppTokens.kt` |
| 6 | Buttons (`.1` Filled, `.2` Tonal, `.3` Elevated, `.4` Outlined, `.5` Text) | `AppButton` |
| 7 | FAB (`7a.1` Small 40 dp, `7a.2` Standard 56 dp, `7a.3` Large 96 dp, `7a.4` Extended) | `AppFab`, `AppExtendedFab` |
| 8 | Icon-Buttons | `AppIconButton` |
| 9 | Chips | `AppChip` |
| 10 | Textfelder | `AppTextField`, `KeyboardAwareTextArea` |
| 11 | Karten | `AppCard` |
| 12 | Listeneinträge | `SettingsListRow`, `FolderEntryCard` |
| 13 | Auswahl-Steuerelemente | `Switch`/`Checkbox`/`RadioButton`, `SettingsSwitchRow` |
| 14 | Segmented Buttons | `SingleChoiceSegmentedButtonRow` |
| 15 | Slider & Fortschritt | `AppSlider`, Progress-Indikatoren |
| 16 | Badges & Status-Pills | `AppStatusPill`, `AppBadge` |
| 17 | Banner | `AppBanner` |
| 18 | Snackbar & Dialog | `SnackbarHost`, `AppDialog` |
| 19–21 | Icons, Trennlinien, Spacing & Radien | Material Icons, `HorizontalDivider`, `AppSpacing`/`AppRadius` |
| 22 | App-Header (mobil) | `AppScaffold`/`AppHeader`, `AppTopNavBar` |
| 23–24 | Einstellungen Listen-/Unterseite | `SettingsHomePage`, `SettingsScaffold` + `SettingsGroup` |
| 25–31, 33 | Listen (filterbar, Drag & Drop, Auswahl & Löschen, schlicht, gruppiert, ausklappbar, Trailing-Steuerelemente, kombiniert) | Listen-Bausteine der Library (`SettingsUi`, `folders/ui`, `entrylock/ui`) |
| 32 | Umrandete Container-Flächen | `AppCard(variant = Outlined)` |

---

## 9. App-eigenes Register (Vorlage)

Jede App führt im Projektstamm eine Datei `styling-exceptions.md` mit genau zwei Tabellen. Ein Custom-Element ohne Eintrag ist zu ersetzen oder nachzutragen; eine Abweichung ohne Eintrag ist ein Fehler.

### Custom-Elemente
| Element | Datei | Kriterium (Abschnitt 7) | Begründung |
|---|---|---|---|
| | | | |

### Abweichungen vom Basis-Projekt
| Thema | Basis-Projekt | Diese App | Grund |
|---|---|---|---|
| | | | |

Abweichungen sind die Ausnahme. Eine Abweichung, die für alle Apps sinnvoll ist, wird nicht im Register geführt, sondern ins Basis-Projekt und in diese Datei übernommen.
