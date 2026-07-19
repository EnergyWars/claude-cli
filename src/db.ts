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
