import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import {
  DATABASE_DIRECTORY_ENV_VAR,
  loadEnv,
  parseEnvFile,
  resolveDatabaseDirectory,
} from './env.js';
import { EMBEDDED_ENV_FILE } from './generated/embedded-context.js';

test('parseEnvFile: parsed KEY=VALUE-Zeilen, ignoriert Kommentare/Leerzeilen, entfernt umschliessende Anfuehrungszeichen', () => {
  const content = [
    '# Kommentar',
    '',
    'CL_DATABASE_DIR=/tmp/db',
    'PORT="7765"',
    "QUOTED='single'",
    'NO_VALUE=',
    '  INDENTED = mit Leerzeichen  ',
  ].join('\n');

  assert.deepEqual(parseEnvFile(content), {
    CL_DATABASE_DIR: '/tmp/db',
    PORT: '7765',
    QUOTED: 'single',
    NO_VALUE: '',
    INDENTED: 'mit Leerzeichen',
  });
});

test('parseEnvFile: ignoriert Zeilen ohne "="', () => {
  assert.deepEqual(parseEnvFile('keine-zuweisung\nA=1'), { A: '1' });
});

function withTempDir<T>(fn: (dir: string) => T): T {
  const dir = mkdtempSync(join(tmpdir(), 'cl-env-'));
  try {
    return fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('loadEnv: eine lokale .env im rootDir ersetzt die eingebettete .env vollstaendig', () => {
  withTempDir((dir) => {
    writeFileSync(join(dir, '.env'), 'CL_DATABASE_DIR=/local/db\n');
    assert.deepEqual(loadEnv({}, dir), { CL_DATABASE_DIR: '/local/db' });
  });
});

test('loadEnv: ohne lokale .env wird auf die eingebettete .env zurueckgefallen', () => {
  withTempDir((dir) => {
    assert.deepEqual(loadEnv({}, dir), parseEnvFile(EMBEDDED_ENV_FILE));
  });
});

test('loadEnv: echte Umgebungsvariablen ueberschreiben sowohl lokale als auch eingebettete .env', () => {
  withTempDir((dir) => {
    writeFileSync(join(dir, '.env'), 'CL_DATABASE_DIR=/local/db\nPORT=1111\n');
    assert.deepEqual(loadEnv({ PORT: '2222' }, dir), {
      CL_DATABASE_DIR: '/local/db',
      PORT: '2222',
    });
  });
});

test('resolveDatabaseDirectory: liefert den Wert aus process.env', () => {
  assert.equal(
    resolveDatabaseDirectory({ [DATABASE_DIRECTORY_ENV_VAR]: '/tmp/cl-db' }),
    '/tmp/cl-db',
  );
});

test('resolveDatabaseDirectory: wirft, wenn weder Umgebungsvariable noch .env-Datei den Wert liefern', () => {
  const previous = process.env.CL_ROOT_DIR;
  const dir = mkdtempSync(join(tmpdir(), 'cl-env-empty-'));
  writeFileSync(join(dir, '.env'), 'PORT=1234\n');
  process.env.CL_ROOT_DIR = dir;
  try {
    assert.throws(() => resolveDatabaseDirectory({}), new RegExp(DATABASE_DIRECTORY_ENV_VAR));
  } finally {
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    rmSync(dir, { recursive: true, force: true });
  }
});
