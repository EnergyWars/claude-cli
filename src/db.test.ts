import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import type { DatabaseSync } from 'node:sqlite';

import {
  completeCommand,
  getCommand,
  insertCommand,
  logAccess,
  openDatabase,
  updateCommandOutput,
} from './db.js';

let dbDir: string;
let db: DatabaseSync;

before(() => {
  dbDir = mkdtempSync(join(tmpdir(), 'cl-db-test-'));
  db = openDatabase(dbDir);
});

after(() => {
  db.close();
  rmSync(dbDir, { recursive: true, force: true });
});

test('openDatabase: legt Verzeichnis und commands.db an', () => {
  assert.ok(existsSync(join(dbDir, 'commands.db')));
});

test('openDatabase: erzeugt t_access_log und t_commands', () => {
  const tables = db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
    .all()
    .map((row) => String(row.name));
  assert.ok(tables.includes('t_access_log'));
  assert.ok(tables.includes('t_commands'));
});

test('insertCommand + getCommand: Roundtrip mit status "running"', () => {
  insertCommand(db, {
    id: 'cmd-1',
    agent: 'main',
    model: 'sonnet',
    command: 'hallo',
    path: '/tmp',
  });
  const row = getCommand(db, 'cmd-1');
  assert.ok(row);
  assert.equal(row.agent, 'main');
  assert.equal(row.model, 'sonnet');
  assert.equal(row.command, 'hallo');
  assert.equal(row.path, '/tmp');
  assert.equal(row.status, 'running');
  assert.equal(row.output, '');
  assert.equal(row.exitCode, null);
  assert.equal(typeof row.createdAt, 'string');
  assert.equal(row.createdAt, row.updatedAt);
});

test('getCommand: undefined fuer unbekannte ID', () => {
  assert.equal(getCommand(db, 'does-not-exist'), undefined);
});

test('updateCommandOutput: aktualisiert output, laesst status unveraendert', () => {
  insertCommand(db, { id: 'cmd-2', agent: 'main', model: 'sonnet', command: 'x', path: '/tmp' });
  updateCommandOutput(db, 'cmd-2', 'Teil-Output ...');
  const row = getCommand(db, 'cmd-2');
  assert.ok(row);
  assert.equal(row.output, 'Teil-Output ...');
  assert.equal(row.status, 'running');
  assert.equal(row.exitCode, null);
});

test('completeCommand: setzt status/exit_code/output bei Erfolg', () => {
  insertCommand(db, { id: 'cmd-3', agent: 'main', model: 'sonnet', command: 'x', path: '/tmp' });
  completeCommand(db, 'cmd-3', 'completed', 0, 'Fertiger Output');
  const row = getCommand(db, 'cmd-3');
  assert.ok(row);
  assert.equal(row.status, 'completed');
  assert.equal(row.exitCode, 0);
  assert.equal(row.output, 'Fertiger Output');
});

test('completeCommand: setzt status "failed" mit null exit_code (Spawn-Fehler)', () => {
  insertCommand(db, { id: 'cmd-4', agent: 'main', model: 'sonnet', command: 'x', path: '/tmp' });
  completeCommand(db, 'cmd-4', 'failed', null, 'claude ENOENT');
  const row = getCommand(db, 'cmd-4');
  assert.ok(row);
  assert.equal(row.status, 'failed');
  assert.equal(row.exitCode, null);
  assert.equal(row.output, 'claude ENOENT');
});

test('logAccess: schreibt Zeile inkl. Body', () => {
  logAccess(db, 'POST', '/', 202, '{"command":"x"}');
  const row = db
    .prepare('SELECT method, path, status_code, body FROM t_access_log ORDER BY id DESC LIMIT 1')
    .get();
  assert.ok(row);
  assert.equal(row.method, 'POST');
  assert.equal(row.path, '/');
  assert.equal(row.status_code, 202);
  assert.equal(row.body, '{"command":"x"}');
});

test('logAccess: Body ist NULL wenn undefined uebergeben wird (z. B. GET)', () => {
  logAccess(db, 'GET', '/state/xyz', 404, undefined);
  const row = db
    .prepare('SELECT method, path, status_code, body FROM t_access_log ORDER BY id DESC LIMIT 1')
    .get();
  assert.ok(row);
  assert.equal(row.method, 'GET');
  assert.equal(row.status_code, 404);
  assert.equal(row.body, null);
});
