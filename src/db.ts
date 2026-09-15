import { randomBytes } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { DatabaseSync, type SQLOutputValue } from 'node:sqlite';

export type CommandStatus = 'running' | 'completed' | 'failed' | 'stopped';

export interface CommandRow {
  id: string;
  agent: string;
  model: string;
  command: string;
  path: string;
  status: CommandStatus;
  output: string;
  exitCode: number | null;
  pid: number | null;
  createdAt: string;
  updatedAt: string;
  costUsd: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cacheCreationInputTokens: number | null;
  cacheReadInputTokens: number | null;
}

/** Token-/Kostenverbrauch eines einzelnen `claude`-Laufs, aus `--output-format json` extrahiert (siehe `parseHeadlessResultJson` in `src/launch.ts`). */
export interface CommandUsage {
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationInputTokens: number;
  cacheReadInputTokens: number;
}

export interface CostEntry {
  id: string;
  path: string;
  createdAt: string;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationInputTokens: number;
  cacheReadInputTokens: number;
}

/** `"scheduler"` fuer `schedulers[]`, `"script-scheduler"` fuer `scriptSchedulers[]` (siehe `t_scheduler_disabled`). */
export type SchedulerKind = 'scheduler' | 'script-scheduler';

export interface SystemMetricRow {
  id: number;
  createdAt: string;
  cpuPercent: number;
  memUsedPercent: number;
  memTotalBytes: number;
  memFreeBytes: number;
}

function ensureColumns(
  db: DatabaseSync,
  table: string,
  columns: { name: string; definition: string }[],
): void {
  const existing = new Set(
    (db.prepare(`PRAGMA table_info(${table})`).all() as { name: string }[]).map((row) => row.name),
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
  // t_config_pointer.version_id absichtlich nicht DB-seitig erzwungen: resolveEffectiveConfig()
  // validiert das selbst und wirft eine sprechende Fehlermeldung statt eines FK-Constraint-Fehlers.
  db.exec('PRAGMA foreign_keys = OFF');
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
    { name: 'pid', definition: 'pid INTEGER' },
    { name: 'cost_usd', definition: 'cost_usd REAL' },
    { name: 'input_tokens', definition: 'input_tokens INTEGER' },
    { name: 'output_tokens', definition: 'output_tokens INTEGER' },
    { name: 'cache_creation_input_tokens', definition: 'cache_creation_input_tokens INTEGER' },
    { name: 'cache_read_input_tokens', definition: 'cache_read_input_tokens INTEGER' },
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
  db.exec('DROP INDEX IF EXISTS idx_commands_path');
  db.exec(
    'CREATE INDEX IF NOT EXISTS idx_commands_path_created ON t_commands (path, created_at DESC)',
  );
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_feedback (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      text TEXT NOT NULL,
      section TEXT,
      context TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `);
  ensureColumns(db, 't_feedback', [
    { name: 'section', definition: 'section TEXT' },
    { name: 'context', definition: 'context TEXT' },
    { name: 'path', definition: 'path TEXT' },
  ]);
  db.exec('CREATE INDEX IF NOT EXISTS idx_feedback_path ON t_feedback (path)');
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_config_versions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      content TEXT NOT NULL,
      created_at TEXT NOT NULL
    )
  `);
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_config_pointer (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      version_id INTEGER,
      updated_at TEXT NOT NULL,
      FOREIGN KEY (version_id) REFERENCES t_config_versions(id)
    )
  `);
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_system_metrics (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      created_at TEXT NOT NULL,
      cpu_percent REAL NOT NULL,
      mem_used_percent REAL NOT NULL,
      mem_total_bytes INTEGER NOT NULL,
      mem_free_bytes INTEGER NOT NULL
    )
  `);
  db.exec(
    'CREATE INDEX IF NOT EXISTS idx_system_metrics_created ON t_system_metrics (created_at)',
  );
  db.exec(`
    CREATE TABLE IF NOT EXISTS t_scheduler_disabled (
      kind TEXT NOT NULL,
      name TEXT NOT NULL,
      path_name TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      PRIMARY KEY (kind, name, path_name)
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
    (db.prepare('PRAGMA table_info(t_tickets)').all() as { name: string }[]).map((row) => row.name),
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

/** Persistiert die PID des zum Command gehoerenden Kindprozesses, sobald er gespawnt wurde - Grundlage fuer {@link listRunningCommandsWithPid}. */
export function setCommandPid(db: DatabaseSync, id: string, pid: number): void {
  db.prepare('UPDATE t_commands SET pid = ? WHERE id = ?').run(pid, id);
}

export function updateCommandOutput(db: DatabaseSync, id: string, output: string): void {
  db.prepare('UPDATE t_commands SET output = ?, updated_at = ? WHERE id = ?').run(
    output,
    new Date().toISOString(),
    id,
  );
}

/**
 * `usage` wird nur bei erfolgreicher Extraktion aus `--output-format json` mitgegeben (siehe
 * `parseHeadlessResultJson` in `src/launch.ts`) - `COALESCE` laesst die Kosten-/Token-Spalten
 * unangetastet, wenn kein `usage` vorliegt (z.B. Spawn-Fehler, per SIGTERM gestoppter Lauf).
 */
export function completeCommand(
  db: DatabaseSync,
  id: string,
  status: 'completed' | 'failed' | 'stopped',
  exitCode: number | null,
  output: string,
  usage?: CommandUsage,
): void {
  db.prepare(
    `UPDATE t_commands SET status = ?, exit_code = ?, output = ?, updated_at = ?,
       cost_usd = COALESCE(?, cost_usd),
       input_tokens = COALESCE(?, input_tokens),
       output_tokens = COALESCE(?, output_tokens),
       cache_creation_input_tokens = COALESCE(?, cache_creation_input_tokens),
       cache_read_input_tokens = COALESCE(?, cache_read_input_tokens)
     WHERE id = ?`,
  ).run(
    status,
    exitCode,
    output,
    new Date().toISOString(),
    usage?.costUsd ?? null,
    usage?.inputTokens ?? null,
    usage?.outputTokens ?? null,
    usage?.cacheCreationInputTokens ?? null,
    usage?.cacheReadInputTokens ?? null,
    id,
  );
}

function toNullableNumber(value: SQLOutputValue | undefined): number | null {
  return value === null || value === undefined ? null : Number(value);
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
    pid: row.pid === null || row.pid === undefined ? null : Number(row.pid),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
    costUsd: toNullableNumber(row.cost_usd),
    inputTokens: toNullableNumber(row.input_tokens),
    outputTokens: toNullableNumber(row.output_tokens),
    cacheCreationInputTokens: toNullableNumber(row.cache_creation_input_tokens),
    cacheReadInputTokens: toNullableNumber(row.cache_read_input_tokens),
  };
}

/** Wie {@link toCommandRow}, aber fuer Zeilen ohne selektierte `output`-Spalte (siehe {@link listCommands}). */
function toCommandSummaryRow(row: Record<string, SQLOutputValue>): CommandRow {
  return {
    id: String(row.id),
    agent: String(row.agent),
    model: String(row.model),
    command: String(row.command),
    path: String(row.path),
    status: String(row.status) as CommandStatus,
    output: '',
    exitCode: row.exit_code === null ? null : Number(row.exit_code),
    pid: row.pid === null || row.pid === undefined ? null : Number(row.pid),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
    costUsd: toNullableNumber(row.cost_usd),
    inputTokens: toNullableNumber(row.input_tokens),
    outputTokens: toNullableNumber(row.output_tokens),
    cacheCreationInputTokens: toNullableNumber(row.cache_creation_input_tokens),
    cacheReadInputTokens: toNullableNumber(row.cache_read_input_tokens),
  };
}

export function getCommand(db: DatabaseSync, id: string): CommandRow | undefined {
  const row = db.prepare('SELECT * FROM t_commands WHERE id = ?').get(id);
  return row === undefined ? undefined : toCommandRow(row);
}

/**
 * Neueste zuerst; "rowid" als Tiebreaker fuer Commands mit identischem created_at (Millisekunden-Aufloesung).
 * `ORDER BY created_at DESC` wird per `idx_commands_path_created (path, created_at DESC)` ohne
 * Sortierschritt ueber die volle Treffermenge bedient (SQLite muss nur noch innerhalb von Gruppen mit
 * identischem created_at nach rowid sortieren, praktisch immer sehr kleine Gruppen). Die potenziell
 * grosse `output`-Spalte (voller CLI-Output) wird bewusst NICHT selektiert - die Verlaufsliste zeigt sie
 * nicht an, `getCommand` laedt sie separat pro Detail-Ansicht.
 * Ohne `options` (bzw. ohne `limit`) unveraendert die volle Liste; mit `limit` paginiert per SQL LIMIT/OFFSET
 * (kein In-Memory-`slice()` noetig – bleibt effizient auch bei langem Verlauf).
 */
export function listCommands(
  db: DatabaseSync,
  path: string,
  options?: { limit?: number; offset?: number },
): CommandRow[] {
  const columns =
    'id, agent, model, command, path, status, exit_code, pid, created_at, updated_at, cost_usd, input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens';
  if (options?.limit === undefined) {
    const rows = db
      .prepare(
        `SELECT ${columns} FROM t_commands WHERE path = ? ORDER BY created_at DESC, rowid DESC`,
      )
      .all(path);
    return rows.map((row) => toCommandSummaryRow(row));
  }
  const rows = db
    .prepare(
      `SELECT ${columns} FROM t_commands WHERE path = ? ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?`,
    )
    .all(path, options.limit, options.offset ?? 0);
  return rows.map((row) => toCommandSummaryRow(row));
}

export function countCommands(db: DatabaseSync, path: string): number {
  const row = db.prepare('SELECT COUNT(*) AS count FROM t_commands WHERE path = ?').get(path) as {
    count: number;
  };
  return row.count;
}

/** Auf "running" stehende Commands mit bekannter PID - Grundlage fuer die Reconciliation verwaister Eintraege beim Serverstart. */
export function listRunningCommandsWithPid(db: DatabaseSync): { id: string; pid: number }[] {
  const rows = db
    .prepare("SELECT id, pid FROM t_commands WHERE status = 'running' AND pid IS NOT NULL")
    .all();
  return rows.map((row) => ({ id: String(row.id), pid: Number(row.pid) }));
}

export const DEFAULT_STATS_WINDOW_HOURS = 24;

const AGENT_COMMANDS_ONLY_CLAUSE =
  "agent NOT LIKE 'path-command:%' AND agent NOT LIKE 'hook:%' AND agent NOT LIKE 'script-scheduler:%'";

/** Reine Agent-Laeufe (ohne Pfad-Commands) mit Status "running" fuer diesen Pfad. */
export function countRunningAgents(db: DatabaseSync, path: string): number {
  const row = db
    .prepare(
      `SELECT COUNT(*) AS count FROM t_commands WHERE path = ? AND status = 'running' AND ${AGENT_COMMANDS_ONLY_CLAUSE}`,
    )
    .get(path);
  return Number(row?.count ?? 0);
}

/** Reine Agent-Laeufe (ohne Pfad-Commands), die seit `sinceIso` gestartet wurden, fuer diesen Pfad. */
export function countAgentsSince(db: DatabaseSync, path: string, sinceIso: string): number {
  const row = db
    .prepare(
      `SELECT COUNT(*) AS count FROM t_commands WHERE path = ? AND created_at >= ? AND ${AGENT_COMMANDS_ONLY_CLAUSE}`,
    )
    .get(path, sinceIso);
  return Number(row?.count ?? 0);
}

/** Alle Commands mit erfasstem Kostenverbrauch (echte `claude`-Laeufe, siehe {@link CommandUsage}), neueste zuerst - Grundlage fuer `GET /costs` (aggregiert projektuebergreifend, siehe `handleGetCosts` in `src/server.ts`). Pfad-Commands/Hooks/Script-Scheduler-Laeufe haben nie `cost_usd` gesetzt und tauchen hier nie auf. */
export function listCommandCosts(db: DatabaseSync): CostEntry[] {
  const rows = db
    .prepare(
      `SELECT id, path, created_at, cost_usd, input_tokens, output_tokens, cache_creation_input_tokens, cache_read_input_tokens
       FROM t_commands WHERE cost_usd IS NOT NULL ORDER BY created_at DESC, rowid DESC`,
    )
    .all();
  return rows.map((row) => ({
    id: String(row.id),
    path: String(row.path),
    createdAt: String(row.created_at),
    costUsd: Number(row.cost_usd),
    inputTokens: Number(row.input_tokens ?? 0),
    outputTokens: Number(row.output_tokens ?? 0),
    cacheCreationInputTokens: Number(row.cache_creation_input_tokens ?? 0),
    cacheReadInputTokens: Number(row.cache_read_input_tokens ?? 0),
  }));
}

/**
 * Deaktiviert/aktiviert einen (Script-)Scheduler fuer genau einen Pfad, ohne `config.json` anzufassen -
 * Grundlage fuer `POST /paths/:pathName/schedulers/:name/enable|disable` (siehe `src/server.ts`). Ein
 * deaktivierter Eintrag hat eine Zeile in `t_scheduler_disabled`; Aktivieren loescht sie wieder, statt
 * einen `enabled`-Flag zu pflegen - Standardzustand (kein Eintrag) ist also immer "aktiviert".
 */
export function setSchedulerEnabled(
  db: DatabaseSync,
  kind: SchedulerKind,
  name: string,
  pathName: string,
  enabled: boolean,
): void {
  if (enabled) {
    db.prepare(
      'DELETE FROM t_scheduler_disabled WHERE kind = ? AND name = ? AND path_name = ?',
    ).run(kind, name, pathName);
    return;
  }
  db.prepare(
    `INSERT INTO t_scheduler_disabled (kind, name, path_name, updated_at)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(kind, name, path_name) DO UPDATE SET updated_at = excluded.updated_at`,
  ).run(kind, name, pathName, new Date().toISOString());
}

/** Nur der automatische Cron-Trigger (`triggerScheduler`/`triggerScriptScheduler`) prueft dies - ein manueller Trigger ueber die API laeuft unabhaengig vom deaktivierten Zustand. */
export function isSchedulerDisabled(
  db: DatabaseSync,
  kind: SchedulerKind,
  name: string,
  pathName: string,
): boolean {
  const row = db
    .prepare(
      'SELECT 1 AS present FROM t_scheduler_disabled WHERE kind = ? AND name = ? AND path_name = ?',
    )
    .get(kind, name, pathName);
  return row !== undefined;
}

export function insertSystemMetric(
  db: DatabaseSync,
  metric: {
    cpuPercent: number;
    memUsedPercent: number;
    memTotalBytes: number;
    memFreeBytes: number;
  },
): void {
  db.prepare(
    'INSERT INTO t_system_metrics (created_at, cpu_percent, mem_used_percent, mem_total_bytes, mem_free_bytes) VALUES (?, ?, ?, ?, ?)',
  ).run(
    new Date().toISOString(),
    metric.cpuPercent,
    metric.memUsedPercent,
    metric.memTotalBytes,
    metric.memFreeBytes,
  );
}

/** Chronologisch aufsteigend (aeltester zuerst) - direkt in dieser Reihenfolge fuer ein Zeitreihen-Diagramm nutzbar. */
export function listSystemMetrics(db: DatabaseSync, sinceIso?: string): SystemMetricRow[] {
  const rows =
    sinceIso === undefined
      ? db.prepare('SELECT * FROM t_system_metrics ORDER BY created_at ASC').all()
      : db
          .prepare('SELECT * FROM t_system_metrics WHERE created_at >= ? ORDER BY created_at ASC')
          .all(sinceIso);
  return rows.map((row) => ({
    id: Number(row.id),
    createdAt: String(row.created_at),
    cpuPercent: Number(row.cpu_percent),
    memUsedPercent: Number(row.mem_used_percent),
    memTotalBytes: Number(row.mem_total_bytes),
    memFreeBytes: Number(row.mem_free_bytes),
  }));
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

export type TicketStatus = 'generating' | 'open' | 'in progress' | 'done' | 'rejected';

export const TICKET_STATUSES: readonly TicketStatus[] = [
  'generating',
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

function insertTicketRow(
  db: DatabaseSync,
  row: {
    pathName: string;
    originalRequest: string;
    summary: string;
    claudeInstruction: string;
    category: string;
    status: TicketStatus;
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
      row.status,
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
  return insertTicketRow(db, { ...row, status: 'open' });
}

/** Legt sofort ein leeres Ticket im Status "generating" an, bevor der Ticket-Agent gelaufen ist. */
export function insertGeneratingTicket(
  db: DatabaseSync,
  row: { pathName: string; originalRequest: string; ipAddress?: string | null },
): TicketRow {
  return insertTicketRow(db, {
    ...row,
    summary: '',
    claudeInstruction: '',
    category: '',
    status: 'generating',
  });
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
  section: string | null;
  context: string | null;
  path: string | null;
  createdAt: string;
  updatedAt: string;
}

function toFeedbackRow(row: Record<string, SQLOutputValue>): FeedbackRow {
  return {
    id: Number(row.id),
    text: String(row.text),
    section: row.section === null || row.section === undefined ? null : String(row.section),
    context: row.context === null || row.context === undefined ? null : String(row.context),
    path: row.path === null || row.path === undefined ? null : String(row.path),
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
  };
}

export function insertFeedback(
  db: DatabaseSync,
  text: string,
  section: string | null = null,
  context: string | null = null,
  path: string | null = null,
): FeedbackRow {
  const now = new Date().toISOString();
  const result = db
    .prepare(
      'INSERT INTO t_feedback (text, section, context, path, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)',
    )
    .run(text, section, context, path, now, now);
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

/** Neueste zuerst; ohne pathName alle Eintraege, mit pathName nur die diesem Pfad zugeordneten. */
export function listFeedback(db: DatabaseSync, pathName?: string): FeedbackRow[] {
  const rows =
    pathName === undefined
      ? db.prepare('SELECT * FROM t_feedback ORDER BY id DESC').all()
      : db.prepare('SELECT * FROM t_feedback WHERE path = ? ORDER BY id DESC').all(pathName);
  return rows.map((row) => toFeedbackRow(row));
}

export function updateFeedback(
  db: DatabaseSync,
  id: number,
  text: string,
): FeedbackRow | undefined {
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

export interface ConfigVersionRow {
  id: number;
  content: string;
  createdAt: string;
}

export interface ConfigVersionSummary {
  id: number;
  createdAt: string;
}

function toConfigVersionRow(row: Record<string, SQLOutputValue>): ConfigVersionRow {
  return {
    id: Number(row.id),
    content: String(row.content),
    createdAt: String(row.created_at),
  };
}

export function insertConfigVersion(db: DatabaseSync, content: string): ConfigVersionRow {
  const now = new Date().toISOString();
  const result = db
    .prepare('INSERT INTO t_config_versions (content, created_at) VALUES (?, ?)')
    .run(content, now);
  const version = getConfigVersion(db, Number(result.lastInsertRowid));
  if (!version) {
    throw new Error('Config-Version konnte nach dem Anlegen nicht gelesen werden.');
  }
  return version;
}

export function getConfigVersion(db: DatabaseSync, id: number): ConfigVersionRow | undefined {
  const row = db.prepare('SELECT * FROM t_config_versions WHERE id = ?').get(id);
  return row === undefined ? undefined : toConfigVersionRow(row);
}

/** Neueste zuerst. */
export function listConfigVersions(db: DatabaseSync): ConfigVersionSummary[] {
  const rows = db.prepare('SELECT id, created_at FROM t_config_versions ORDER BY id DESC').all();
  return rows.map((row) => ({ id: Number(row.id), createdAt: String(row.created_at) }));
}

export interface ConfigPointerRow {
  versionId: number | null;
  updatedAt: string;
}

export function getConfigPointer(db: DatabaseSync): ConfigPointerRow | undefined {
  const row = db.prepare('SELECT version_id, updated_at FROM t_config_pointer WHERE id = 1').get();
  if (row === undefined) {
    return undefined;
  }
  return {
    versionId: row.version_id === null ? null : Number(row.version_id),
    updatedAt: String(row.updated_at),
  };
}

export function setConfigPointer(db: DatabaseSync, versionId: number | null): void {
  db.prepare(
    `INSERT INTO t_config_pointer (id, version_id, updated_at) VALUES (1, ?, ?)
     ON CONFLICT(id) DO UPDATE SET version_id = excluded.version_id, updated_at = excluded.updated_at`,
  ).run(versionId, new Date().toISOString());
}
