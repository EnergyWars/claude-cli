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
      path TEXT NOT NULL,
      status TEXT NOT NULL,
      output TEXT NOT NULL DEFAULT '',
      exit_code INTEGER,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `);
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_totp (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      secret TEXT NOT NULL,
      confirmed INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL
    )
  `);
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_tickets (
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
  db.exec('CREATE INDEX IF NOT EXISTS idx_tickets_path_name ON t_tickets (path_name)');
  return db;
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

export interface TotpRow {
  secret: string;
  confirmed: boolean;
  createdAt: string;
}

function toTotpRow(row: Record<string, SQLOutputValue>): TotpRow {
  return {
    secret: String(row.secret),
    confirmed: Number(row.confirmed) === 1,
    createdAt: String(row.created_at),
  };
}

export function getTotpSecret(db: DatabaseSync): TotpRow | undefined {
  const row = db.prepare('SELECT secret, confirmed, created_at FROM t_totp WHERE id = 1').get();
  return row === undefined ? undefined : toTotpRow(row);
}

export function setPendingTotpSecret(db: DatabaseSync, secret: string): void {
  db.prepare(
    `INSERT INTO t_totp (id, secret, confirmed, created_at) VALUES (1, ?, 0, ?)
     ON CONFLICT(id) DO UPDATE SET secret = excluded.secret, confirmed = 0, created_at = excluded.created_at`,
  ).run(secret, new Date().toISOString());
}

export function confirmTotpSecret(db: DatabaseSync): void {
  db.prepare('UPDATE t_totp SET confirmed = 1 WHERE id = 1').run();
}

export function deleteTotpSecret(db: DatabaseSync): boolean {
  const result = db.prepare('DELETE FROM t_totp WHERE id = 1').run();
  return result.changes > 0;
}

export type TicketStatus = 'open' | 'in progress' | 'closed';

export const TICKET_STATUSES: readonly TicketStatus[] = ['open', 'in progress', 'closed'];

export function isTicketStatus(value: string): value is TicketStatus {
  return (TICKET_STATUSES as readonly string[]).includes(value);
}

export interface TicketRow {
  id: number;
  pathName: string;
  title: string;
  description: string;
  task: string;
  status: TicketStatus;
  createdAt: string;
  updatedAt: string;
}

export interface TicketUpdate {
  title?: string;
  description?: string;
  task?: string;
  status?: TicketStatus;
}

function toTicketRow(row: Record<string, SQLOutputValue>): TicketRow {
  return {
    id: Number(row.id),
    pathName: String(row.path_name),
    title: String(row.title),
    description: String(row.description),
    task: String(row.task),
    status: String(row.status) as TicketStatus,
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  };
}

export function insertTicket(
  db: DatabaseSync,
  row: { pathName: string; title: string; description: string; task: string },
): TicketRow {
  const now = new Date().toISOString();
  const result = db
    .prepare(
      'INSERT INTO t_tickets (path_name, title, description, task, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
    )
    .run(row.pathName, row.title, row.description, row.task, 'open', now, now);
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
    title: update.title ?? existing.title,
    description: update.description ?? existing.description,
    task: update.task ?? existing.task,
    status: update.status ?? existing.status,
  };
  db.prepare(
    'UPDATE t_tickets SET title = ?, description = ?, task = ?, status = ?, updated_at = ? WHERE id = ?',
  ).run(merged.title, merged.description, merged.task, merged.status, new Date().toISOString(), id);
  return getTicket(db, id);
}

export function deleteTicket(db: DatabaseSync, id: number): boolean {
  const result = db.prepare('DELETE FROM t_tickets WHERE id = ?').run(id);
  return result.changes > 0;
}
