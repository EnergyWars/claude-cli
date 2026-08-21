import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import type { DatabaseSync } from 'node:sqlite';

import {
  completeCommand,
  confirmTotpSecret,
  deleteTicket,
  deleteTotpSecret,
  getCommand,
  getTicket,
  getTotpSecret,
  insertCommand,
  insertTicket,
  listTickets,
  logAccess,
  openDatabase,
  setPendingTotpSecret,
  updateCommandOutput,
  updateTicket,
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

test('openDatabase: erzeugt t_access_log, t_commands, t_totp und t_tickets', () => {
  const tables = db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
    .all()
    .map((row) => String(row.name));
  assert.ok(tables.includes('t_access_log'));
  assert.ok(tables.includes('t_commands'));
  assert.ok(tables.includes('t_totp'));
  assert.ok(tables.includes('t_tickets'));
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

test('getTotpSecret: undefined ohne vorherigen Aufruf von setPendingTotpSecret', () => {
  const freshDir = mkdtempSync(join(tmpdir(), 'cl-db-test-totp-'));
  const freshDb = openDatabase(freshDir);
  try {
    assert.equal(getTotpSecret(freshDb), undefined);
  } finally {
    freshDb.close();
    rmSync(freshDir, { recursive: true, force: true });
  }
});

test('setPendingTotpSecret: legt unbestaetigtes Secret an', () => {
  setPendingTotpSecret(db, 'SECRETAAAAAAAAAAAAAAAAAAAAAAAAA');
  const row = getTotpSecret(db);
  assert.ok(row);
  assert.equal(row.secret, 'SECRETAAAAAAAAAAAAAAAAAAAAAAAAA');
  assert.equal(row.confirmed, false);
});

test('setPendingTotpSecret: ein zweiter Aufruf ersetzt das vorherige (unbestaetigte) Secret', () => {
  setPendingTotpSecret(db, 'FIRSTSECRETAAAAAAAAAAAAAAAAAAAA');
  setPendingTotpSecret(db, 'SECONDSECRETAAAAAAAAAAAAAAAAAAA');
  const row = getTotpSecret(db);
  assert.ok(row);
  assert.equal(row.secret, 'SECONDSECRETAAAAAAAAAAAAAAAAAAA');
  assert.equal(row.confirmed, false);
});

test('confirmTotpSecret: markiert das ausstehende Secret als bestaetigt/aktiv', () => {
  setPendingTotpSecret(db, 'CONFIRMMEAAAAAAAAAAAAAAAAAAAAAA');
  confirmTotpSecret(db);
  const row = getTotpSecret(db);
  assert.ok(row);
  assert.equal(row.confirmed, true);
});

test('deleteTotpSecret: entfernt das Secret und liefert true, sonst false', () => {
  setPendingTotpSecret(db, 'DELETEMEAAAAAAAAAAAAAAAAAAAAAAA');
  assert.equal(deleteTotpSecret(db), true);
  assert.equal(getTotpSecret(db), undefined);
  assert.equal(deleteTotpSecret(db), false);
});

test('insertTicket: legt ein Ticket mit Status "open" an und liefert es inkl. ID', () => {
  const ticket = insertTicket(db, {
    pathName: 'myapp',
    title: 'Titel',
    description: 'Beschreibung',
    task: 'Aufgabe',
  });
  assert.equal(typeof ticket.id, 'number');
  assert.equal(ticket.pathName, 'myapp');
  assert.equal(ticket.title, 'Titel');
  assert.equal(ticket.description, 'Beschreibung');
  assert.equal(ticket.task, 'Aufgabe');
  assert.equal(ticket.status, 'open');
  assert.equal(typeof ticket.createdAt, 'string');
  assert.equal(ticket.createdAt, ticket.updatedAt);
});

test('insertTicket: IDs sind fortlaufend und eindeutig ueber alle Pfade hinweg', () => {
  const first = insertTicket(db, {
    pathName: 'a',
    title: 't1',
    description: 'd1',
    task: 'x1',
  });
  const second = insertTicket(db, {
    pathName: 'b',
    title: 't2',
    description: 'd2',
    task: 'x2',
  });
  assert.equal(second.id, first.id + 1);
});

test('getTicket: undefined fuer unbekannte ID', () => {
  assert.equal(getTicket(db, 999_999), undefined);
});

test('listTickets: liefert nur Tickets des angegebenen Pfads, sortiert nach ID', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-db-tickets-'));
  const ticketsDb = openDatabase(dir);
  try {
    const t1 = insertTicket(ticketsDb, {
      pathName: 'proj-x',
      title: 'Erstes',
      description: 'd',
      task: 't',
    });
    insertTicket(ticketsDb, {
      pathName: 'proj-y',
      title: 'Anderer Pfad',
      description: 'd',
      task: 't',
    });
    const t2 = insertTicket(ticketsDb, {
      pathName: 'proj-x',
      title: 'Zweites',
      description: 'd',
      task: 't',
    });

    const list = listTickets(ticketsDb, 'proj-x');
    assert.deepEqual(
      list.map((t) => t.id),
      [t1.id, t2.id],
    );
  } finally {
    ticketsDb.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('listTickets: leeres Array fuer unbekannten oder ticket-losen Pfad', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-db-tickets-empty-'));
  const ticketsDb = openDatabase(dir);
  try {
    assert.deepEqual(listTickets(ticketsDb, 'does-not-exist'), []);
  } finally {
    ticketsDb.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('listTickets: Status-Filter liefert nur Tickets mit passendem Status', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-db-tickets-status-'));
  const ticketsDb = openDatabase(dir);
  try {
    const openTicket = insertTicket(ticketsDb, {
      pathName: 'proj',
      title: 'Offen',
      description: 'd',
      task: 't',
    });
    const closedTicket = insertTicket(ticketsDb, {
      pathName: 'proj',
      title: 'Geschlossen',
      description: 'd',
      task: 't',
    });
    updateTicket(ticketsDb, closedTicket.id, { status: 'closed' });

    assert.deepEqual(
      listTickets(ticketsDb, 'proj', 'open').map((t) => t.id),
      [openTicket.id],
    );
    assert.deepEqual(
      listTickets(ticketsDb, 'proj', 'closed').map((t) => t.id),
      [closedTicket.id],
    );
    assert.equal(listTickets(ticketsDb, 'proj', 'in progress').length, 0);
    assert.equal(listTickets(ticketsDb, 'proj').length, 2);
  } finally {
    ticketsDb.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('updateTicket: aktualisiert nur die uebergebenen Felder, laesst den Rest unveraendert', () => {
  const ticket = insertTicket(db, {
    pathName: 'update-test',
    title: 'Alt',
    description: 'Alte Beschreibung',
    task: 'Alte Aufgabe',
  });
  const updated = updateTicket(db, ticket.id, { title: 'Neu' });
  assert.ok(updated);
  assert.equal(updated.title, 'Neu');
  assert.equal(updated.description, 'Alte Beschreibung');
  assert.equal(updated.task, 'Alte Aufgabe');
  assert.equal(updated.status, 'open');
});

test('updateTicket: kann mehrere Felder gleichzeitig aktualisieren, inkl. Status', () => {
  const ticket = insertTicket(db, {
    pathName: 'update-test-2',
    title: 'Alt',
    description: 'Alt',
    task: 'Alt',
  });
  const updated = updateTicket(db, ticket.id, {
    title: 'Neu',
    description: 'Neue Beschreibung',
    task: 'Neue Aufgabe',
    status: 'in progress',
  });
  assert.deepEqual(
    updated && {
      title: updated.title,
      description: updated.description,
      task: updated.task,
      status: updated.status,
    },
    { title: 'Neu', description: 'Neue Beschreibung', task: 'Neue Aufgabe', status: 'in progress' },
  );
});

test('updateTicket: aktualisiert updatedAt, laesst createdAt unveraendert', async () => {
  const ticket = insertTicket(db, {
    pathName: 'update-test-3',
    title: 'Alt',
    description: 'Alt',
    task: 'Alt',
  });
  await new Promise((resolve) => setTimeout(resolve, 5));
  const updated = updateTicket(db, ticket.id, { status: 'closed' });
  assert.ok(updated);
  assert.equal(updated.createdAt, ticket.createdAt);
  assert.notEqual(updated.updatedAt, ticket.createdAt);
});

test('updateTicket: liefert undefined fuer unbekannte ID, ohne zu werfen', () => {
  assert.equal(updateTicket(db, 999_999, { title: 'x' }), undefined);
});

test('deleteTicket: entfernt das Ticket und liefert true, sonst false', () => {
  const ticket = insertTicket(db, {
    pathName: 'delete-test',
    title: 'x',
    description: 'y',
    task: 'z',
  });
  assert.equal(deleteTicket(db, ticket.id), true);
  assert.equal(getTicket(db, ticket.id), undefined);
  assert.equal(deleteTicket(db, ticket.id), false);
});
