import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, test } from 'node:test';

import {
  computeGoalStatus,
  extractGoalCommand,
  findGoalEntry,
  getGoalListForFolder,
  GOALS_DIRECTORY_NAME,
  listGoalLists,
  MAX_GOAL_FILE_BYTES,
  parseGoalEntry,
} from './goals.js';

let projectDir: string;
let goalsDir: string;

beforeEach(() => {
  projectDir = mkdtempSync(join(tmpdir(), 'cl-goals-'));
  goalsDir = join(projectDir, GOALS_DIRECTORY_NAME);
});

afterEach(() => {
  rmSync(projectDir, { recursive: true, force: true });
});

function writeGoalFolder(folder: string, files: Record<string, string>): string {
  const folderPath = join(goalsDir, folder);
  mkdirSync(folderPath, { recursive: true });
  for (const [name, content] of Object.entries(files)) {
    writeFileSync(join(folderPath, name), content);
  }
  return folderPath;
}

const FRONTMATTER_GOAL = (id: string, title: string, dependsOn: string): string => `---
id: ${id}
title: ${title}
description: Beschreibung von ${title}.
date: 2026-09-29
dependsOn: [${dependsOn}]
---

# ${id} – ${title}
*Voraussetzung: ${dependsOn || 'keine'}*

\`\`\`text
/goal Tu etwas fuer ${title}.

Erledigt ist das Ziel erst, wenn Claude im Transcript alles Folgende belegt hat:
1. Nachweis erbracht.
\`\`\`
`;

const LEGACY_GOAL = `# G02 – Tagesnotiz im Perioden-Modul
*Phase A · Voraussetzung: G01*

\`\`\`text
/goal Erfasse eine Tagesnotiz.

Erledigt ist das Ziel erst, wenn Claude im Transcript alles Folgende belegt hat:
1. Nachweis erbracht.
\`\`\`
`;

const PLAN_MD = `# Plan: Perioden-Modul Ausbau

*Datum: 2026-09-27*

**Anweisung im Wortlaut:**
> Test
`;

test('extractGoalCommand: liest den /goal-Block und schneidet den abschliessenden Zeilenumbruch ab', () => {
  const content = '```text\n/goal Mach etwas.\n```\n';
  assert.equal(extractGoalCommand(content), '/goal Mach etwas.');
});

test('extractGoalCommand: liefert undefined ohne /goal-Block', () => {
  assert.equal(extractGoalCommand('# Nur eine Ueberschrift'), undefined);
});

test('computeGoalStatus: ready ohne Abhaengigkeiten', () => {
  assert.deepEqual(computeGoalStatus([], new Set()), { status: 'ready', missingDependencies: [] });
});

test('computeGoalStatus: blocked wenn eine referenzierte Datei noch existiert', () => {
  assert.deepEqual(computeGoalStatus(['G01', 'G03'], new Set(['G01'])), {
    status: 'blocked',
    missingDependencies: ['G01'],
  });
});

test('computeGoalStatus: unbekannte (bereits geloeschte) Abhaengigkeit gilt als erfuellt', () => {
  assert.deepEqual(computeGoalStatus(['G99'], new Set(['G01'])), { status: 'ready', missingDependencies: [] });
});

test('parseGoalEntry: liest Frontmatter-Felder vollstaendig', () => {
  const entry = parseGoalEntry('G01-erstes.md', FRONTMATTER_GOAL('G01', 'Erstes Feature', ''), '2026-09-29T10:00:00.000Z');
  assert.deepEqual(entry, {
    id: 'G01',
    fileName: 'G01-erstes.md',
    title: 'Erstes Feature',
    description: 'Beschreibung von Erstes Feature.',
    date: '2026-09-29',
    dependsOn: [],
    command: entry?.command,
    content: FRONTMATTER_GOAL('G01', 'Erstes Feature', ''),
    timestamp: '2026-09-29T10:00:00.000Z',
    legacy: false,
  });
  assert.match(entry.command, /^\/goal Tu etwas fuer Erstes Feature\./);
});

test('parseGoalEntry: Legacy-Fallback ohne Frontmatter liest ID/Titel/Voraussetzung aus dem Text', () => {
  const entry = parseGoalEntry('G02-tagesnotiz.md', LEGACY_GOAL, '2026-09-27T09:00:00.000Z');
  assert.equal(entry?.id, 'G02');
  assert.equal(entry.title, 'Tagesnotiz im Perioden-Modul');
  assert.deepEqual(entry.dependsOn, ['G01']);
  assert.equal(entry.description, '');
  assert.equal(entry.date, '2026-09-27');
  assert.equal(entry.legacy, true);
});

test('parseGoalEntry: liefert undefined ohne /goal-Block (z. B. PLAN.md)', () => {
  assert.equal(parseGoalEntry('PLAN.md', PLAN_MD, '2026-09-27T09:00:00.000Z'), undefined);
});

test('listGoalLists: liefert eine leere Liste ohne goals-Verzeichnis', () => {
  assert.deepEqual(listGoalLists(projectDir), []);
});

test('listGoalLists: liefert eine leere Liste, wenn das Projektverzeichnis nicht existiert', () => {
  assert.deepEqual(listGoalLists(join(projectDir, 'gibt-es-nicht')), []);
});

test('listGoalLists: gruppiert nach Ordner, liest PLAN.md Titel/Datum und sortiert Goals nach Dateiname', () => {
  writeGoalFolder('2026-09-27-feature', {
    'PLAN.md': PLAN_MD,
    'G02-zweites.md': FRONTMATTER_GOAL('G02', 'Zweites Feature', 'G01'),
    'G01-erstes.md': FRONTMATTER_GOAL('G01', 'Erstes Feature', ''),
  });

  const [summary] = listGoalLists(projectDir);
  assert.equal(summary?.folder, '2026-09-27-feature');
  assert.equal(summary.planTitle, 'Perioden-Modul Ausbau');
  assert.equal(summary.planDate, '2026-09-27');
  assert.deepEqual(
    summary.goals.map((goal) => goal.fileName),
    ['G01-erstes.md', 'G02-zweites.md'],
  );
});

test('listGoalLists: Status ready/blocked wird rein aus vorhandenen Dateien im Ordner berechnet', () => {
  writeGoalFolder('2026-09-28-feature', {
    'G01-erstes.md': FRONTMATTER_GOAL('G01', 'Erstes Feature', ''),
    'G02-zweites.md': FRONTMATTER_GOAL('G02', 'Zweites Feature', 'G01'),
  });

  const [summary] = listGoalLists(projectDir);
  const byId = new Map(summary?.goals.map((goal) => [goal.id, goal]));
  assert.equal(byId.get('G01')?.status, 'ready');
  assert.equal(byId.get('G02')?.status, 'blocked');
  assert.deepEqual(byId.get('G02')?.missingDependencies, ['G01']);
});

test('listGoalLists: G01 ist ready, sobald die G01-Datei geloescht (also "erledigt") ist', () => {
  const folderPath = writeGoalFolder('2026-09-28-feature', {
    'G01-erstes.md': FRONTMATTER_GOAL('G01', 'Erstes Feature', ''),
    'G02-zweites.md': FRONTMATTER_GOAL('G02', 'Zweites Feature', 'G01'),
  });
  rmSync(join(folderPath, 'G01-erstes.md'));

  const [summary] = listGoalLists(projectDir);
  assert.deepEqual(
    summary?.goals.map((goal) => goal.id),
    ['G02'],
  );
  assert.equal(summary.goals[0]?.status, 'ready');
});

test('listGoalLists: neuere Ordner (absteigend sortiert) stehen zuerst', () => {
  writeGoalFolder('2026-09-27-alt', { 'G01-a.md': FRONTMATTER_GOAL('G01', 'A', '') });
  writeGoalFolder('2026-09-29-neu', { 'G01-b.md': FRONTMATTER_GOAL('G01', 'B', '') });

  assert.deepEqual(
    listGoalLists(projectDir).map((summary) => summary.folder),
    ['2026-09-29-neu', '2026-09-27-alt'],
  );
});

test('listGoalLists: ignoriert Dateien ohne /goal-Block, Unterordner und Symlinks innerhalb eines Goal-Ordners', () => {
  const folderPath = writeGoalFolder('2026-09-27-feature', {
    'notes.md': '# Nur Notizen, kein /goal-Block',
    'G01-echt.md': FRONTMATTER_GOAL('G01', 'Echtes Feature', ''),
  });
  mkdirSync(join(folderPath, 'sub.md'));
  writeFileSync(join(folderPath, 'sub.md', 'inner.md'), FRONTMATTER_GOAL('G09', 'Versteckt', ''));
  const target = join(projectDir, 'outside.md');
  writeFileSync(target, FRONTMATTER_GOAL('G08', 'Aussen', ''));
  symlinkSync(target, join(folderPath, 'link.md'));

  const [summary] = listGoalLists(projectDir);
  assert.deepEqual(
    summary?.goals.map((goal) => goal.fileName),
    ['G01-echt.md'],
  );
});

test('listGoalLists: ueberspringt Goal-Dateien groesser als MAX_GOAL_FILE_BYTES', () => {
  const big = FRONTMATTER_GOAL('G01', 'A'.repeat(MAX_GOAL_FILE_BYTES), '');
  writeGoalFolder('2026-09-27-feature', {
    'G01-gross.md': big,
    'G02-ok.md': FRONTMATTER_GOAL('G02', 'Klein', ''),
  });

  const [summary] = listGoalLists(projectDir);
  assert.deepEqual(
    summary?.goals.map((goal) => goal.fileName),
    ['G02-ok.md'],
  );
});

test('listGoalLists: Ordner ohne Goal-Dateien wird mit leerer Liste gefuehrt', () => {
  writeGoalFolder('2026-09-27-leer', {});
  const [summary] = listGoalLists(projectDir);
  assert.equal(summary?.folder, '2026-09-27-leer');
  assert.deepEqual(summary.goals, []);
});

test('getGoalListForFolder: liefert undefined bei unbekanntem Ordner oder Path-Traversal', () => {
  writeGoalFolder('2026-09-27-feature', { 'G01-a.md': FRONTMATTER_GOAL('G01', 'A', '') });
  assert.equal(getGoalListForFolder(projectDir, 'gibt-es-nicht'), undefined);
  assert.equal(getGoalListForFolder(projectDir, '..'), undefined);
  assert.equal(getGoalListForFolder(projectDir, '../outside'), undefined);
  assert.equal(getGoalListForFolder(projectDir, '2026-09-27-feature/../2026-09-27-feature'), undefined);
});

test('findGoalEntry: liefert Eintrag inkl. Ordnerpfad fuer eine gueltige Datei', () => {
  const folderPath = writeGoalFolder('2026-09-27-feature', {
    'G01-erstes.md': FRONTMATTER_GOAL('G01', 'Erstes Feature', ''),
  });

  const found = findGoalEntry(projectDir, '2026-09-27-feature', 'G01-erstes.md');
  assert.equal(found?.entry.id, 'G01');
  assert.equal(found.entry.status, 'ready');
  assert.equal(found.folderPath, folderPath);
});

test('findGoalEntry: liefert undefined bei unbekannter Datei, unsicherem Segment oder falscher Endung', () => {
  writeGoalFolder('2026-09-27-feature', { 'G01-erstes.md': FRONTMATTER_GOAL('G01', 'Erstes Feature', '') });

  assert.equal(findGoalEntry(projectDir, '2026-09-27-feature', 'G99-fehlt.md'), undefined);
  assert.equal(findGoalEntry(projectDir, '2026-09-27-feature', '../G01-erstes.md'), undefined);
  assert.equal(findGoalEntry(projectDir, '2026-09-27-feature', 'G01-erstes.txt'), undefined);
});

test('listGoalLists: Legacy-Datei ohne Frontmatter uebernimmt das Datum aus der Datei-mtime', () => {
  const folderPath = writeGoalFolder('2026-09-27-feature', { 'G02-tagesnotiz.md': LEGACY_GOAL });
  const mtime = new Date('2026-03-04T10:00:00.000Z');
  utimesSync(join(folderPath, 'G02-tagesnotiz.md'), mtime, mtime);

  const [summary] = listGoalLists(projectDir);
  assert.equal(summary?.goals[0]?.timestamp, mtime.toISOString());
  assert.equal(summary.goals[0].date, '2026-03-04');
  assert.equal(summary.goals[0].legacy, true);
});
