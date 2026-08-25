import { randomBytes } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { DatabaseSync, type SQLOutputValue } from 'node:sqlite';

export type CommandStatus = 'running' | 'completed' | 'failed';

export interface CommandRow {
  id: string;
  agent: string;
  model: string;
  command: string;
  path: string;
  status: CommandStatus;
  output: string;
  exitCode: number | null;
  createdAt: string;
  updatedAt: string;
}

function ensureColumns(
  db: DatabaseSync,
  table: string,
  columns: { name: string; definition: string }[],
): void {
  const existing = new Set(
    (db.prepare(`PRAGMA table_info(${table})`).all() as { name: string }[]).map(
      (row) => row.name,
    ),
  );
  for (const column of columns) {
    if (!existing.has(column.name)) {
      db.exec(`ALTER TABLE ${table} ADD COLUMN ${column.definition}`);
    }
  }
}

export function openDatabase(directory: string): DatabaseSync {
  mkdirSync(directory, { recursive: true });
  const db = new DatabaseSync(join(directory, 'commands.db'));
  db.exec('PRAGMA journal_mode = WAL');
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_access_log (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      timestamp TEXT NOT NULL,
      method TEXT NOT NULL,
      path TEXT NOT NULL,
      status_code INTEGER NOT NULL,
      body TEXT
    )
  `);
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_commands (
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
  ensureColumns(db, 't_commands', [
    { name: 'path', definition: "path TEXT NOT NULL DEFAULT ''" },
  ]);
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_totp (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      secret TEXT NOT NULL,
      confirmed INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL
    )
  `);
  ensureColumns(db, 't_totp', [{ name: 'jwt_secret', definition: 'jwt_secret TEXT' }]);
  db.prepare('UPDATE t_totp SET jwt_secret = ? WHERE id = 1 AND jwt_secret IS NULL').run(
    randomBytes(32).toString('hex'),
  );
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_tickets (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      path_name TEXT NOT NULL,
      original_request TEXT NOT NULL,
      summary TEXT NOT NULL,
      claude_instruction TEXT NOT NULL,
      category TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'open',
      ip_address TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `);
  ensureColumns(db, 't_tickets', [
    { name: 'original_request', definition: "original_request TEXT NOT NULL DEFAULT ''" },
    { name: 'summary', definition: "summary TEXT NOT NULL DEFAULT ''" },
    { name: 'claude_instruction', definition: "claude_instruction TEXT NOT NULL DEFAULT ''" },
    { name: 'category', definition: "category TEXT NOT NULL DEFAULT ''" },
    { name: 'ip_address', definition: 'ip_address TEXT' },
  ]);
  migrateLegacyTicketColumns(db);
  db.exec('CREATE INDEX IF NOT EXISTS idx_tickets_path_name ON t_tickets (path_name)');
  db.exec('CREATE INDEX IF NOT EXISTS idx_commands_path ON t_commands (path)');
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_feedback (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      text TEXT NOT NULL,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `);
  return db;
}

/**
 * Fruehere Ticket-Spalten (title/description/task, Status "closed") auf das aktuelle Schema
 * (original_request/summary/claude_instruction/category, Status "done") heben. Die alten Spalten
 * werden danach gedroppt: sie waren NOT NULL ohne DEFAULT, ein INSERT ueber das aktuelle Schema
 * (das diese Spalten nicht mehr setzt) wuerde sonst mit "NOT NULL constraint failed" scheitern.
 */
function migrateLegacyTicketColumns(db: DatabaseSync): void {
  const columns = new Set(
    (db.prepare('PRAGMA table_info(t_tickets)').all() as { name: string }[]).map(
      (row) => row.name,
    ),
  );
  if (columns.has('title') && columns.has('description') && columns.has('task')) {
    db.exec(`
      UPDATE t_tickets SET
        original_request = CASE WHEN original_request = '' THEN title ELSE original_request END,
        summary = CASE WHEN summary = '' THEN description ELSE summary END,
        claude_instruction = CASE WHEN claude_instruction = '' THEN task ELSE claude_instruction END,
        category = CASE WHEN category = '' THEN 'Allgemein' ELSE category END
    `);
    db.exec('ALTER TABLE t_tickets DROP COLUMN title');
    db.exec('ALTER TABLE t_tickets DROP COLUMN description');
    db.exec('ALTER TABLE t_tickets DROP COLUMN task');
  }
  db.exec("UPDATE t_tickets SET status = 'done' WHERE status = 'closed'");
}

export function logAccess(
  db: DatabaseSync,
  method: string,
  path: string,
  statusCode: number,
  body: string | undefined,
): void {
  db.prepare(
    'INSERT INTO t_access_log (timestamp, method, path, status_code, body) VALUES (?, ?, ?, ?, ?)',
  ).run(new Date().toISOString(), method, path, statusCode, body ?? null);
}

export function insertCommand(
  db: DatabaseSync,
  row: { id: string; agent: string; model: string; command: string; path: string },
): void {
  const now = new Date().toISOString();
  db.prepare(
    'INSERT INTO t_commands (id, agent, model, command, path, status, output, exit_code, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
  ).run(row.id, row.agent, row.model, row.command, row.path, 'running', '', null, now, now);
}

export function updateCommandOutput(db: DatabaseSync, id: string, output: string): void {
  db.prepare('UPDATE t_commands SET output = ?, updated_at = ? WHERE id = ?').run(
    output,
    new Date().toISOString(),
    id,
  );
}

export function completeCommand(
  db: DatabaseSync,
  id: string,
  status: 'completed' | 'failed',
  exitCode: number | null,
  output: string,
): void {
  db.prepare(
    'UPDATE t_commands SET status = ?, exit_code = ?, output = ?, updated_at = ? WHERE id = ?',
  ).run(status, exitCode, output, new Date().toISOString(), id);
}

function toCommandRow(row: Record<string, SQLOutputValue>): CommandRow {
  return {
    id: String(row.id),
    agent: String(row.agent),
    model: String(row.model),
    command: String(row.command),
    path: String(row.path),
    status: String(row.status) as CommandStatus,
    output: String(row.output),
    exitCode: row.exit_code === null ? null : Number(row.exit_code),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  };
}

export function getCommand(db: DatabaseSync, id: string): CommandRow | undefined {
  const row = db.prepare('SELECT * FROM t_commands WHERE id = ?').get(id);
  return row === undefined ? undefined : toCommandRow(row);
}

/** Neueste zuerst; "rowid" als Tiebreaker fuer Commands mit identischem created_at (Millisekunden-Aufloesung). */
export function listCommands(db: DatabaseSync, path: string): CommandRow[] {
  const rows = db
    .prepare('SELECT * FROM t_commands WHERE path = ? ORDER BY created_at DESC, rowid DESC')
    .all(path);
  return rows.map((row) => toCommandRow(row));
}

export interface TotpRow {
  secret: string;
  confirmed: boolean;
  createdAt: string;
  jwtSecret: string;
}

function toTotpRow(row: Record<string, SQLOutputValue>): TotpRow {
  return {
    secret: String(row.secret),
    confirmed: Number(row.confirmed) === 1,
    createdAt: String(row.created_at),
    jwtSecret: String(row.jwt_secret),
  };
}

export function getTotpSecret(db: DatabaseSync): TotpRow | undefined {
  const row = db
    .prepare('SELECT secret, confirmed, created_at, jwt_secret FROM t_totp WHERE id = 1')
    .get();
  return row === undefined ? undefined : toTotpRow(row);
}

export function setPendingTotpSecret(db: DatabaseSync, secret: string): void {
  const jwtSecret = randomBytes(32).toString('hex');
  db.prepare(
    `INSERT INTO t_totp (id, secret, confirmed, created_at, jwt_secret) VALUES (1, ?, 0, ?, ?)
     ON CONFLICT(id) DO UPDATE SET secret = excluded.secret, confirmed = 0, created_at = excluded.created_at, jwt_secret = excluded.jwt_secret`,
  ).run(secret, new Date().toISOString(), jwtSecret);
}

export function confirmTotpSecret(db: DatabaseSync): void {
  db.prepare('UPDATE t_totp SET confirmed = 1 WHERE id = 1').run();
}

export function deleteTotpSecret(db: DatabaseSync): boolean {
  const result = db.prepare('DELETE FROM t_totp WHERE id = 1').run();
  return result.changes > 0;
}

export type TicketStatus = 'open' | 'in progress' | 'done' | 'rejected';

export const TICKET_STATUSES: readonly TicketStatus[] = [
  'open',
  'in progress',
  'done',
  'rejected',
];

export function isTicketStatus(value: string): value is TicketStatus {
  return (TICKET_STATUSES as readonly string[]).includes(value);
}

export interface TicketRow {
  id: number;
  pathName: string;
  originalRequest: string;
  summary: string;
  claudeInstruction: string;
  category: string;
  status: TicketStatus;
  ipAddress: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TicketUpdate {
  originalRequest?: string;
  summary?: string;
  claudeInstruction?: string;
  category?: string;
  status?: TicketStatus;
}

function toTicketRow(row: Record<string, SQLOutputValue>): TicketRow {
  return {
    id: Number(row.id),
    pathName: String(row.path_name),
    originalRequest: String(row.original_request),
    summary: String(row.summary),
    claudeInstruction: String(row.claude_instruction),
    category: String(row.category),
    status: String(row.status) as TicketStatus,
    ipAddress: row.ip_address === null ? null : String(row.ip_address),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  };
}

export function insertTicket(
  db: DatabaseSync,
  row: {
    pathName: string;
    originalRequest: string;
    summary: string;
    claudeInstruction: string;
    category: string;
    ipAddress?: string | null;
  },
): TicketRow {
  const now = new Date().toISOString();
  const result = db
    .prepare(
      'INSERT INTO t_tickets (path_name, original_request, summary, claude_instruction, category, status, ip_address, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
    )
    .run(
      row.pathName,
      row.originalRequest,
      row.summary,
      row.claudeInstruction,
      row.category,
      'open',
      row.ipAddress ?? null,
      now,
      now,
    );
  const ticket = getTicket(db, Number(result.lastInsertRowid));
  if (!ticket) {
    throw new Error('Ticket konnte nach dem Anlegen nicht gelesen werden.');
  }
  return ticket;
}

export function getTicket(db: DatabaseSync, id: number): TicketRow | undefined {
  const row = db.prepare('SELECT * FROM t_tickets WHERE id = ?').get(id);
  return row === undefined ? undefined : toTicketRow(row);
}

export function listTickets(
  db: DatabaseSync,
  pathName: string,
  status?: TicketStatus,
): TicketRow[] {
  const rows =
    status === undefined
      ? db.prepare('SELECT * FROM t_tickets WHERE path_name = ? ORDER BY id ASC').all(pathName)
      : db
          .prepare('SELECT * FROM t_tickets WHERE path_name = ? AND status = ? ORDER BY id ASC')
          .all(pathName, status);
  return rows.map((row) => toTicketRow(row));
}

export function listAllTickets(db: DatabaseSync, status?: TicketStatus): TicketRow[] {
  const rows =
    status === undefined
      ? db.prepare('SELECT * FROM t_tickets ORDER BY id ASC').all()
      : db.prepare('SELECT * FROM t_tickets WHERE status = ? ORDER BY id ASC').all(status);
  return rows.map((row) => toTicketRow(row));
}

export function updateTicket(
  db: DatabaseSync,
  id: number,
  update: TicketUpdate,
): TicketRow | undefined {
  const existing = getTicket(db, id);
  if (!existing) {
    return undefined;
  }
  const merged = {
    originalRequest: update.originalRequest ?? existing.originalRequest,
    summary: update.summary ?? existing.summary,
    claudeInstruction: update.claudeInstruction ?? existing.claudeInstruction,
    category: update.category ?? existing.category,
    status: update.status ?? existing.status,
  };
  db.prepare(
    'UPDATE t_tickets SET original_request = ?, summary = ?, claude_instruction = ?, category = ?, status = ?, updated_at = ? WHERE id = ?',
  ).run(
    merged.originalRequest,
    merged.summary,
    merged.claudeInstruction,
    merged.category,
    merged.status,
    new Date().toISOString(),
    id,
  );
  return getTicket(db, id);
}

export function deleteTicket(db: DatabaseSync, id: number): boolean {
  const result = db.prepare('DELETE FROM t_tickets WHERE id = ?').run(id);
  return result.changes > 0;
}

export interface FeedbackRow {
  id: number;
  text: string;
  createdAt: string;
  updatedAt: string;
}

function toFeedbackRow(row: Record<string, SQLOutputValue>): FeedbackRow {
  return {
    id: Number(row.id),
    text: String(row.text),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  };
}

export function insertFeedback(db: DatabaseSync, text: string): FeedbackRow {
  const now = new Date().toISOString();
  const result = db
    .prepare('INSERT INTO t_feedback (text, created_at, updated_at) VALUES (?, ?, ?)')
    .run(text, now, now);
  const feedback = getFeedback(db, Number(result.lastInsertRowid));
  if (!feedback) {
    throw new Error('Feedback konnte nach dem Anlegen nicht gelesen werden.');
  }
  return feedback;
}

export function getFeedback(db: DatabaseSync, id: number): FeedbackRow | undefined {
  const row = db.prepare('SELECT * FROM t_feedback WHERE id = ?').get(id);
  return row === undefined ? undefined : toFeedbackRow(row);
}

/** Neueste zuerst. */
export function listFeedback(db: DatabaseSync): FeedbackRow[] {
  const rows = db.prepare('SELECT * FROM t_feedback ORDER BY id DESC').all();
  return rows.map((row) => toFeedbackRow(row));
}

export function updateFeedback(db: DatabaseSync, id: number, text: string): FeedbackRow | undefined {
  const existing = getFeedback(db, id);
  if (!existing) {
    return undefined;
  }
  db.prepare('UPDATE t_feedback SET text = ?, updated_at = ? WHERE id = ?').run(
    text,
    new Date().toISOString(),
    id,
  );
  return getFeedback(db, id);
}

export function deleteFeedback(db: DatabaseSync, id: number): boolean {
  const result = db.prepare('DELETE FROM t_feedback WHERE id = ?').run(id);
  return result.changes > 0;
}
