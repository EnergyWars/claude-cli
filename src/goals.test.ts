import assert from 'node:assert/strict';
import {
  mkdirSync,
  mkdtempSync,
  rmSync,
  symlinkSync,
  utimesSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, test } from 'node:test';

import { GOALS_DIRECTORY_NAME, listGoalFiles, MAX_GOAL_FILE_BYTES } from './goals.js';

let projectDir: string;
let goalsDir: string;

beforeEach(() => {
  projectDir = mkdtempSync(join(tmpdir(), 'cl-goals-'));
  goalsDir = join(projectDir, GOALS_DIRECTORY_NAME);
});

afterEach(() => {
  rmSync(projectDir, { recursive: true, force: true });
});

test('listGoalFiles: liefert eine leere Liste, wenn es kein goals-Verzeichnis gibt', () => {
  assert.deepEqual(listGoalFiles(projectDir), []);
});

test('listGoalFiles: liefert eine leere Liste, wenn das Projektverzeichnis nicht existiert', () => {
  assert.deepEqual(listGoalFiles(join(projectDir, 'gibt-es-nicht')), []);
});

test('listGoalFiles: liefert eine leere Liste fuer ein leeres goals-Verzeichnis', () => {
  mkdirSync(goalsDir);
  assert.deepEqual(listGoalFiles(projectDir), []);
});

test('listGoalFiles: liefert Name, Inhalt und mtime jeder Markdown-Datei', () => {
  mkdirSync(goalsDir);
  const filePath = join(goalsDir, 'feature-a.md');
  writeFileSync(filePath, '# Feature A\n\nÜmläute ✓\n');
  const mtime = new Date('2026-03-04T10:00:00.000Z');
  utimesSync(filePath, mtime, mtime);

  assert.deepEqual(listGoalFiles(projectDir), [
    { name: 'feature-a.md', content: '# Feature A\n\nÜmläute ✓\n', timestamp: mtime.toISOString() },
  ]);
});

test('listGoalFiles: erkennt die Endung .md unabhaengig von der Gross-/Kleinschreibung', () => {
  mkdirSync(goalsDir);
  writeFileSync(join(goalsDir, 'a.MD'), 'A');
  writeFileSync(join(goalsDir, 'b.Md'), 'B');
  writeFileSync(join(goalsDir, 'c.md'), 'C');

  assert.deepEqual(
    listGoalFiles(projectDir).map((goal) => goal.name),
    ['a.MD', 'b.Md', 'c.md'],
  );
});

test('listGoalFiles: ignoriert Dateien ohne .md-Endung, Unterordner und Symlinks', () => {
  mkdirSync(goalsDir);
  writeFileSync(join(goalsDir, 'notes.txt'), 'x');
  writeFileSync(join(goalsDir, 'md'), 'x');
  writeFileSync(join(goalsDir, 'a.md.bak'), 'x');
  mkdirSync(join(goalsDir, 'sub.md'));
  writeFileSync(join(goalsDir, 'sub.md', 'inner.md'), 'x');
  const target = join(projectDir, 'outside.md');
  writeFileSync(target, 'geheim');
  symlinkSync(target, join(goalsDir, 'link.md'));
  writeFileSync(join(goalsDir, 'real.md'), 'ok');

  assert.deepEqual(
    listGoalFiles(projectDir).map((goal) => goal.name),
    ['real.md'],
  );
});

test('listGoalFiles: sortiert alphabetisch nach Dateiname', () => {
  mkdirSync(goalsDir);
  writeFileSync(join(goalsDir, 'zeta.md'), 'z');
  writeFileSync(join(goalsDir, 'alpha.md'), 'a');
  writeFileSync(join(goalsDir, 'mitte.md'), 'm');

  assert.deepEqual(
    listGoalFiles(projectDir).map((goal) => goal.name),
    ['alpha.md', 'mitte.md', 'zeta.md'],
  );
});

test('listGoalFiles: liefert auch leere Dateien mit leerem Inhalt', () => {
  mkdirSync(goalsDir);
  writeFileSync(join(goalsDir, 'leer.md'), '');

  const goals = listGoalFiles(projectDir);
  assert.equal(goals.length, 1);
  assert.equal(goals[0]?.content, '');
});

test('listGoalFiles: ueberspringt Dateien groesser als MAX_GOAL_FILE_BYTES', () => {
  mkdirSync(goalsDir);
  writeFileSync(join(goalsDir, 'gross.md'), 'x'.repeat(MAX_GOAL_FILE_BYTES + 1));
  writeFileSync(join(goalsDir, 'grenze.md'), 'x'.repeat(MAX_GOAL_FILE_BYTES));

  assert.deepEqual(
    listGoalFiles(projectDir).map((goal) => goal.name),
    ['grenze.md'],
  );
});

test('listGoalFiles: liest nur das goals-Verzeichnis des Projekts, nicht dessen Elternverzeichnis', () => {
  writeFileSync(join(projectDir, 'top.md'), 'x');
  mkdirSync(goalsDir);
  writeFileSync(join(goalsDir, 'inner.md'), 'y');

  assert.deepEqual(
    listGoalFiles(projectDir).map((goal) => goal.name),
    ['inner.md'],
  );
});
