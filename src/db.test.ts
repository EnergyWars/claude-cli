import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import { DatabaseSync } from 'node:sqlite';

import {
  completeCommand,
  confirmTotpSecret,
  deleteFeedback,
  deleteTicket,
  deleteTotpSecret,
  getCommand,
  getFeedback,
  getTicket,
  getTotpSecret,
  insertCommand,
  insertFeedback,
  insertTicket,
  listAllTickets,
  listCommands,
  listFeedback,
  listTickets,
  logAccess,
  openDatabase,
  setPendingTotpSecret,
  updateCommandOutput,
  updateFeedback,
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

test('openDatabase: ergaenzt fehlende Spalte "path" in einer alten t_commands-Tabelle', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-db-migration-'));
  try {
    const legacyDb = new DatabaseSync(join(dir, 'commands.db'));
    legacyDb.exec(`
      CREATE TABLE t_commands (
        id TEXT PRIMARY KEY,
        agent TEXT NOT NULL,
        model TEXT NOT NULL,
        command TEXT NOT NULL,
        status TEXT NOT NULL,
        output TEXT NOT NULL DEFAULT '',
        exit_code INTEGER,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      )
    `);
    legacyDb.close();

    const migratedDb = openDatabase(dir);
    try {
      insertCommand(migratedDb, {
        id: 'legacy-cmd',
        agent: 'main',
        model: 'sonnet',
        command: 'x',
        path: '/tmp',
      });
      const row = getCommand(migratedDb, 'legacy-cmd');
      assert.ok(row);
      assert.equal(row.path, '/tmp');
    } finally {
      migratedDb.close();
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('openDatabase: ergaenzt fehlende Spalte "jwt_secret" in einer alten t_totp-Zeile', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-db-migration-totp-'));
  try {
    const legacyDb = new DatabaseSync(join(dir, 'commands.db'));
    legacyDb.exec(`
      CREATE TABLE t_totp (
        id INTEGER PRIMARY KEY CHECK (id = 1),
        secret TEXT NOT NULL,
        confirmed INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL
      )
    `);
    legacyDb
      .prepare('INSERT INTO t_totp (id, secret, confirmed, created_at) VALUES (1, ?, 1, ?)')
      .run('LEGACYSECRETAAAAAAAAAAAAAAAAAAA', new Date().toISOString());
    legacyDb.close();

    const migratedDb = openDatabase(dir);
    try {
      const row = getTotpSecret(migratedDb);
      assert.ok(row);
      assert.equal(row.secret, 'LEGACYSECRETAAAAAAAAAAAAAAAAAAA');
      assert.equal(row.confirmed, true);
      assert.equal(typeof row.jwtSecret, 'string');
      assert.ok(row.jwtSecret.length > 0);
    } finally {
      migratedDb.close();
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
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

test('listCommands: liefert nur Commands des angegebenen Pfads, neueste zuerst', () => {
  insertCommand(db, {
    id: 'lc-other-path',
    agent: 'main',
    model: 'sonnet',
    command: 'a',
    path: '/list-commands-other',
  });
  insertCommand(db, {
    id: 'lc-1',
    agent: 'main',
    model: 'sonnet',
    command: 'b',
    path: '/list-commands-test',
  });
  insertCommand(db, {
    id: 'lc-2',
    agent: 'dev',
    model: 'sonnet',
    command: 'c',
    path: '/list-commands-test',
  });
  const rows = listCommands(db, '/list-commands-test');
  assert.deepEqual(
    rows.map((row) => row.id),
    ['lc-2', 'lc-1'],
  );
  assert.ok(rows.every((row) => row.path === '/list-commands-test'));
});

test('listCommands: leeres Array fuer Pfad ohne Commands', () => {
  assert.deepEqual(listCommands(db, '/no-such-path'), []);
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

test('setPendingTotpSecret: legt unbestaetigtes Secret inkl. jwt_secret an', () => {
  setPendingTotpSecret(db, 'SECRETAAAAAAAAAAAAAAAAAAAAAAAAA');
  const row = getTotpSecret(db);
  assert.ok(row);
  assert.equal(row.secret, 'SECRETAAAAAAAAAAAAAAAAAAAAAAAAA');
  assert.equal(row.confirmed, false);
  assert.equal(typeof row.jwtSecret, 'string');
  assert.ok(row.jwtSecret.length > 0);
});

test('setPendingTotpSecret: ein zweiter Aufruf ersetzt das vorherige (unbestaetigte) Secret und rotiert das jwt_secret', () => {
  setPendingTotpSecret(db, 'FIRSTSECRETAAAAAAAAAAAAAAAAAAAA');
  const first = getTotpSecret(db);
  assert.ok(first);
  setPendingTotpSecret(db, 'SECONDSECRETAAAAAAAAAAAAAAAAAAA');
  const row = getTotpSecret(db);
  assert.ok(row);
  assert.equal(row.secret, 'SECONDSECRETAAAAAAAAAAAAAAAAAAA');
  assert.equal(row.confirmed, false);
  assert.notEqual(row.jwtSecret, first.jwtSecret);
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
    originalRequest: 'Original-Anweisung',
    summary: 'Zusammenfassung',
    claudeInstruction: 'Claude-Anweisung',
    category: 'Backend',
    ipAddress: '203.0.113.5',
  });
  assert.equal(typeof ticket.id, 'number');
  assert.equal(ticket.pathName, 'myapp');
  assert.equal(ticket.originalRequest, 'Original-Anweisung');
  assert.equal(ticket.summary, 'Zusammenfassung');
  assert.equal(ticket.claudeInstruction, 'Claude-Anweisung');
  assert.equal(ticket.category, 'Backend');
  assert.equal(ticket.status, 'open');
  assert.equal(ticket.ipAddress, '203.0.113.5');
  assert.equal(typeof ticket.createdAt, 'string');
  assert.equal(ticket.createdAt, ticket.updatedAt);
});

test('insertTicket: ipAddress ist optional und wird als null gespeichert, wenn nicht angegeben', () => {
  const ticket = insertTicket(db, {
    pathName: 'myapp',
    originalRequest: 'Original-Anweisung',
    summary: 'Zusammenfassung',
    claudeInstruction: 'Claude-Anweisung',
    category: 'Backend',
  });
  assert.equal(ticket.ipAddress, null);
});

test('insertTicket: IDs sind fortlaufend und eindeutig ueber alle Pfade hinweg', () => {
  const first = insertTicket(db, {
    pathName: 'a',
    originalRequest: 'r1',
    summary: 's1',
    claudeInstruction: 'i1',
    category: 'c1',
  });
  const second = insertTicket(db, {
    pathName: 'b',
    originalRequest: 'r2',
    summary: 's2',
    claudeInstruction: 'i2',
    category: 'c2',
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
      originalRequest: 'r',
      summary: 'Erstes',
      claudeInstruction: 'i',
      category: 'c',
    });
    insertTicket(ticketsDb, {
      pathName: 'proj-y',
      originalRequest: 'r',
      summary: 'Anderer Pfad',
      claudeInstruction: 'i',
      category: 'c',
    });
    const t2 = insertTicket(ticketsDb, {
      pathName: 'proj-x',
      originalRequest: 'r',
      summary: 'Zweites',
      claudeInstruction: 'i',
      category: 'c',
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
      originalRequest: 'r',
      summary: 'Offen',
      claudeInstruction: 'i',
      category: 'c',
    });
    const doneTicket = insertTicket(ticketsDb, {
      pathName: 'proj',
      originalRequest: 'r',
      summary: 'Fertig',
      claudeInstruction: 'i',
      category: 'c',
    });
    updateTicket(ticketsDb, doneTicket.id, { status: 'done' });

    assert.deepEqual(
      listTickets(ticketsDb, 'proj', 'open').map((t) => t.id),
      [openTicket.id],
    );
    assert.deepEqual(
      listTickets(ticketsDb, 'proj', 'done').map((t) => t.id),
      [doneTicket.id],
    );
    assert.equal(listTickets(ticketsDb, 'proj', 'in progress').length, 0);
    assert.equal(listTickets(ticketsDb, 'proj', 'rejected').length, 0);
    assert.equal(listTickets(ticketsDb, 'proj').length, 2);
  } finally {
    ticketsDb.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('listAllTickets: liefert Tickets ueber alle Pfade hinweg, optional gefiltert nach Status', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-db-tickets-all-'));
  const ticketsDb = openDatabase(dir);
  try {
    const t1 = insertTicket(ticketsDb, {
      pathName: 'proj-x',
      originalRequest: 'r',
      summary: 's',
      claudeInstruction: 'i',
      category: 'c',
    });
    const t2 = insertTicket(ticketsDb, {
      pathName: 'proj-y',
      originalRequest: 'r',
      summary: 's',
      claudeInstruction: 'i',
      category: 'c',
    });
    updateTicket(ticketsDb, t2.id, { status: 'rejected' });

    assert.deepEqual(
      listAllTickets(ticketsDb).map((t) => t.id),
      [t1.id, t2.id],
    );
    assert.deepEqual(
      listAllTickets(ticketsDb, 'rejected').map((t) => t.id),
      [t2.id],
    );
    assert.equal(listAllTickets(ticketsDb, 'open').length, 1);
  } finally {
    ticketsDb.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('updateTicket: aktualisiert nur die uebergebenen Felder, laesst den Rest unveraendert', () => {
  const ticket = insertTicket(db, {
    pathName: 'update-test',
    originalRequest: 'Alte Anweisung',
    summary: 'Alte Zusammenfassung',
    claudeInstruction: 'Alte Claude-Anweisung',
    category: 'Alt',
  });
  const updated = updateTicket(db, ticket.id, { summary: 'Neu' });
  assert.ok(updated);
  assert.equal(updated.summary, 'Neu');
  assert.equal(updated.originalRequest, 'Alte Anweisung');
  assert.equal(updated.claudeInstruction, 'Alte Claude-Anweisung');
  assert.equal(updated.category, 'Alt');
  assert.equal(updated.status, 'open');
});

test('updateTicket: kann mehrere Felder gleichzeitig aktualisieren, inkl. Status', () => {
  const ticket = insertTicket(db, {
    pathName: 'update-test-2',
    originalRequest: 'Alt',
    summary: 'Alt',
    claudeInstruction: 'Alt',
    category: 'Alt',
  });
  const updated = updateTicket(db, ticket.id, {
    originalRequest: 'Neu',
    summary: 'Neue Zusammenfassung',
    claudeInstruction: 'Neue Claude-Anweisung',
    category: 'Neu',
    status: 'in progress',
  });
  assert.deepEqual(
    updated && {
      originalRequest: updated.originalRequest,
      summary: updated.summary,
      claudeInstruction: updated.claudeInstruction,
      category: updated.category,
      status: updated.status,
    },
    {
      originalRequest: 'Neu',
      summary: 'Neue Zusammenfassung',
      claudeInstruction: 'Neue Claude-Anweisung',
      category: 'Neu',
      status: 'in progress',
    },
  );
});

test('updateTicket: aktualisiert updatedAt, laesst createdAt unveraendert', async () => {
  const ticket = insertTicket(db, {
    pathName: 'update-test-3',
    originalRequest: 'Alt',
    summary: 'Alt',
    claudeInstruction: 'Alt',
    category: 'Alt',
  });
  await new Promise((resolve) => setTimeout(resolve, 5));
  const updated = updateTicket(db, ticket.id, { status: 'rejected' });
  assert.ok(updated);
  assert.equal(updated.createdAt, ticket.createdAt);
  assert.notEqual(updated.updatedAt, ticket.createdAt);
});

test('updateTicket: liefert undefined fuer unbekannte ID, ohne zu werfen', () => {
  assert.equal(updateTicket(db, 999_999, { summary: 'x' }), undefined);
});

test('deleteTicket: entfernt das Ticket und liefert true, sonst false', () => {
  const ticket = insertTicket(db, {
    pathName: 'delete-test',
    originalRequest: 'x',
    summary: 'y',
    claudeInstruction: 'z',
    category: 'c',
  });
  assert.equal(deleteTicket(db, ticket.id), true);
  assert.equal(getTicket(db, ticket.id), undefined);
  assert.equal(deleteTicket(db, ticket.id), false);
});

test('openDatabase: migriert alte title/description/task-Spalten und Status "closed" auf das neue Schema', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-db-tickets-migrate-'));
  try {
    const legacyDb = new DatabaseSync(join(dir, 'commands.db'));
    legacyDb.exec(`
      CREATE TABLE t_tickets (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        path_name TEXT NOT NULL,
        title TEXT NOT NULL,
        description TEXT NOT NULL,
        task TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'open',
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      )
    `);
    legacyDb.prepare(
      'INSERT INTO t_tickets (path_name, title, description, task, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    ).run('legacy-proj', 'Alter Titel', 'Alte Beschreibung', 'Alte Aufgabe', 'closed', 'c', 'u');
    legacyDb.close();

    const migratedDb = openDatabase(dir);
    try {
      const [ticket] = listAllTickets(migratedDb);
      assert.ok(ticket);
      assert.equal(ticket.originalRequest, 'Alter Titel');
      assert.equal(ticket.summary, 'Alte Beschreibung');
      assert.equal(ticket.claudeInstruction, 'Alte Aufgabe');
      assert.equal(ticket.category, 'Allgemein');
      assert.equal(ticket.status, 'done');

      const columns = new Set(
        (migratedDb.prepare('PRAGMA table_info(t_tickets)').all() as { name: string }[]).map(
          (row) => row.name,
        ),
      );
      assert.equal(columns.has('title'), false);
      assert.equal(columns.has('description'), false);
      assert.equal(columns.has('task'), false);

      const inserted = insertTicket(migratedDb, {
        pathName: 'legacy-proj',
        originalRequest: 'Neue Anweisung',
        summary: 'Neue Zusammenfassung',
        claudeInstruction: 'Neue Claude-Anweisung',
        category: 'Backend',
      });
      assert.equal(inserted.originalRequest, 'Neue Anweisung');
    } finally {
      migratedDb.close();
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('insertFeedback + getFeedback: Roundtrip', () => {
  const feedback = insertFeedback(db, 'Bitte Dark Mode hinzufuegen.');
  assert.equal(typeof feedback.id, 'number');
  assert.equal(feedback.text, 'Bitte Dark Mode hinzufuegen.');
  assert.equal(feedback.createdAt, feedback.updatedAt);
  assert.deepEqual(getFeedback(db, feedback.id), feedback);
});

test('getFeedback: undefined fuer unbekannte ID', () => {
  assert.equal(getFeedback(db, 999_999), undefined);
});

test('listFeedback: neueste zuerst', () => {
  const first = insertFeedback(db, 'Erstes Feedback');
  const second = insertFeedback(db, 'Zweites Feedback');
  const all = listFeedback(db);
  const firstIndex = all.findIndex((entry) => entry.id === first.id);
  const secondIndex = all.findIndex((entry) => entry.id === second.id);
  assert.ok(secondIndex < firstIndex);
});

test('updateFeedback: aendert den Text und updatedAt', () => {
  const feedback = insertFeedback(db, 'Alter Text');
  const updated = updateFeedback(db, feedback.id, 'Neuer Text');
  assert.ok(updated);
  assert.equal(updated.text, 'Neuer Text');
  assert.equal(updated.id, feedback.id);
  assert.equal(updated.createdAt, feedback.createdAt);
});

test('updateFeedback: liefert undefined fuer unbekannte ID, ohne zu werfen', () => {
  assert.equal(updateFeedback(db, 999_999, 'x'), undefined);
});

test('deleteFeedback: entfernt den Eintrag und liefert true, sonst false', () => {
  const feedback = insertFeedback(db, 'Zu loeschen');
  assert.equal(deleteFeedback(db, feedback.id), true);
  assert.equal(getFeedback(db, feedback.id), undefined);
  assert.equal(deleteFeedback(db, feedback.id), false);
});
