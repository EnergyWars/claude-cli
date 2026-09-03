import type { ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createReadStream, existsSync, readdirSync, statSync } from 'node:fs';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { basename, extname, join, resolve, sep } from 'node:path';
import type { DatabaseSync } from 'node:sqlite';

import QRCode from 'qrcode';

import {
  collectAll,
  collectForPath,
  collectOne,
  listCollectedFiles,
  resolveCollectedFilePath,
  resolveCollectionPathForFileName,
} from './collect.js';
import {
  completeCommand,
  confirmTotpSecret,
  countAgentsSince,
  countCommands,
  countRunningAgents,
  DEFAULT_STATS_WINDOW_HOURS,
  deleteFeedback,
  deleteTicket,
  getCommand,
  getConfigPointer,
  getConfigVersion,
  getFeedback,
  getTicket,
  getTotpSecret,
  insertCommand,
  insertConfigVersion,
  insertFeedback,
  insertGeneratingTicket,
  isTicketStatus,
  listAllTickets,
  listCommands,
  listConfigVersions,
  listFeedback,
  listTickets,
  logAccess,
  openDatabase,
  setConfigPointer,
  setPendingTotpSecret,
  TICKET_STATUSES,
  updateCommandOutput,
  updateFeedback,
  updateTicket,
  type CommandRow,
  type ConfigVersionSummary,
  type TicketRow,
  type TicketStatus,
  type TicketUpdate,
} from './db.js';
import { getUsageLimits, type UsageLimit } from './usage.js';
import {
  type AgentDefinition,
  type Config,
  type HostedEntry,
  type PathCommandEntry,
  type PathEntry,
  applyPathsOverride,
  ensureConfigBootstrapped,
  listAgents,
  listHostedNames,
  listHostedSummaries,
  listPathCommands,
  listPathNames,
  parseConfig,
  resolveAgentFrom,
  resolveEffectiveConfig,
  resolveHostedEntry,
  resolvePathCommand,
  resolvePathEntry,
} from './config.js';
import { EMBEDDED_CONFIG } from './generated/embedded-context.js';
import { findLatestBuildTimestamp } from './gradle-install.js';
import { signJwt, verifyJwt } from './jwt.js';
import { runHeadlessCommand, runShellCommand } from './launch.js';
import { isLocalNetworkAddress } from './network.js';
import { listRemoteSessions, startRemoteSession } from './remote-session.js';
import { runTicketAgent, type TicketAgentOutput } from './ticket.js';
import { buildOtpAuthUrl, generateSecret, verifyTotp } from './totp.js';
import { VERSION } from './version.js';

const JWT_TTL_SECONDS = 60 * 60 * 2;

const MIME_TYPES: Record<string, string> = {
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.csv': 'text/csv; charset=utf-8',
  '.pdf': 'application/pdf',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.zip': 'application/zip',
  '.apk': 'application/vnd.android.package-archive',
};

function mimeTypeFor(filePath: string): string {
  return MIME_TYPES[extname(filePath).toLowerCase()] ?? 'application/octet-stream';
}

const MAX_BODY_BYTES = 1_000_000;

interface CommandRequestBody {
  command: string;
  model?: string;
  path: string;
  /** Ueberschreibt vollstaendig (kein Merge) die permissions aus config.json fuer diesen einen Agent-Lauf. Immer additiv zu den Permissions des Zielprojekts. */
  permissions?: string[];
}

interface AuthCodeBody {
  code: string;
}

function sendJson(res: ServerResponse, statusCode: number, body: unknown): void {
  res.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

async function readRequestBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let totalBytes = 0;
    req.on('data', (chunk: Buffer) => {
      totalBytes += chunk.length;
      if (totalBytes > MAX_BODY_BYTES) {
        req.destroy();
        reject(new Error('Request-Body zu gross.'));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      resolve(Buffer.concat(chunks).toString('utf8'));
    });
    req.on('error', reject);
  });
}

function parseCommandRequestBody(raw: unknown): CommandRequestBody {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Body muss ein JSON-Objekt sein.');
  }
  const record = raw as Record<string, unknown>;
  if (typeof record.command !== 'string' || record.command.trim() === '') {
    throw new Error('Feld "command" (nicht-leerer String) ist erforderlich.');
  }
  if (typeof record.path !== 'string' || record.path.trim() === '') {
    throw new Error('Feld "path" (nicht-leerer String) ist erforderlich.');
  }
  if (record.model !== undefined && typeof record.model !== 'string') {
    throw new Error('Feld "model" muss ein String sein, falls angegeben.');
  }
  if (
    record.permissions !== undefined &&
    (!Array.isArray(record.permissions) ||
      !record.permissions.every((entry) => typeof entry === 'string'))
  ) {
    throw new Error('Feld "permissions" muss ein Array von Strings sein, falls angegeben.');
  }

  const body: CommandRequestBody = { command: record.command, path: record.path };
  if (typeof record.model === 'string') {
    body.model = record.model;
  }
  if (Array.isArray(record.permissions)) {
    body.permissions = record.permissions;
  }
  return body;
}

interface TicketCreateBody {
  text: string;
}

function parseTicketCreateBody(raw: unknown): TicketCreateBody {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Body muss ein JSON-Objekt sein.');
  }
  const record = raw as Record<string, unknown>;
  if (typeof record.text !== 'string' || record.text.trim() === '') {
    throw new Error('Feld "text" (nicht-leerer String) ist erforderlich.');
  }
  return { text: record.text };
}

function ticketStatusList(): string {
  return TICKET_STATUSES.map((status) => `"${status}"`).join(', ');
}

function parseTicketStatusQuery(value: string | null): TicketStatus | undefined {
  if (value === null) {
    return undefined;
  }
  if (!isTicketStatus(value)) {
    throw new Error(`Query-Parameter "status" muss einer von ${ticketStatusList()} sein.`);
  }
  return value;
}

function parseTicketUpdateBody(raw: unknown): TicketUpdate {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Body muss ein JSON-Objekt sein.');
  }
  const record = raw as Record<string, unknown>;
  const update: TicketUpdate = {};

  if (record.originalRequest !== undefined) {
    if (typeof record.originalRequest !== 'string' || record.originalRequest.trim() === '') {
      throw new Error('Feld "originalRequest" muss ein nicht-leerer String sein, falls angegeben.');
    }
    update.originalRequest = record.originalRequest;
  }
  if (record.summary !== undefined) {
    if (typeof record.summary !== 'string' || record.summary.trim() === '') {
      throw new Error('Feld "summary" muss ein nicht-leerer String sein, falls angegeben.');
    }
    update.summary = record.summary;
  }
  if (record.claudeInstruction !== undefined) {
    if (typeof record.claudeInstruction !== 'string' || record.claudeInstruction.trim() === '') {
      throw new Error(
        'Feld "claudeInstruction" muss ein nicht-leerer String sein, falls angegeben.',
      );
    }
    update.claudeInstruction = record.claudeInstruction;
  }
  if (record.category !== undefined) {
    if (typeof record.category !== 'string' || record.category.trim() === '') {
      throw new Error('Feld "category" muss ein nicht-leerer String sein, falls angegeben.');
    }
    update.category = record.category;
  }
  if (record.status !== undefined) {
    if (typeof record.status !== 'string' || !isTicketStatus(record.status)) {
      throw new Error(`Feld "status" muss einer von ${ticketStatusList()} sein, falls angegeben.`);
    }
    update.status = record.status;
  }

  if (Object.keys(update).length === 0) {
    throw new Error(
      'Mindestens eines der Felder "originalRequest", "summary", "claudeInstruction", "category", "status" muss angegeben werden.',
    );
  }
  return update;
}

interface CollectRequestBody {
  targetName?: string;
}

function parseCollectRequestBody(raw: unknown): CollectRequestBody {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Body muss ein JSON-Objekt sein.');
  }
  const record = raw as Record<string, unknown>;
  if (record.targetName !== undefined && typeof record.targetName !== 'string') {
    throw new Error('Feld "targetName" muss ein String sein, falls angegeben.');
  }
  return typeof record.targetName === 'string' ? { targetName: record.targetName } : {};
}

interface FeedbackTextBody {
  text: string;
  section: string | null;
  context: string | null;
}

function optionalTrimmedString(record: Record<string, unknown>, field: string): string | null {
  const value = record[field];
  if (value !== undefined && typeof value !== 'string') {
    throw new Error(`Feld "${field}" muss, falls vorhanden, ein String sein.`);
  }
  const trimmed = typeof value === 'string' ? value.trim() : '';
  return trimmed === '' ? null : trimmed;
}

function parseFeedbackTextBody(raw: unknown): FeedbackTextBody {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Body muss ein JSON-Objekt sein.');
  }
  const record = raw as Record<string, unknown>;
  if (typeof record.text !== 'string' || record.text.trim() === '') {
    throw new Error('Feld "text" (nicht-leerer String) ist erforderlich.');
  }
  return {
    text: record.text,
    section: optionalTrimmedString(record, 'section'),
    context: optionalTrimmedString(record, 'context'),
  };
}

function parseAuthCodeBody(raw: unknown): AuthCodeBody {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Body muss ein JSON-Objekt sein.');
  }
  const record = raw as Record<string, unknown>;
  if (typeof record.code !== 'string' || record.code.trim() === '') {
    throw new Error('Feld "code" (nicht-leerer String) ist erforderlich.');
  }
  return { code: record.code };
}

function issueAuthToken(jwtSecret: string): { token: string; expiresAt: string } {
  const nowMs = Date.now();
  const token = signJwt({}, jwtSecret, { expiresInSeconds: JWT_TTL_SECONDS, nowMs });
  const expiresAt = new Date(nowMs + JWT_TTL_SECONDS * 1000).toISOString();
  return { token, expiresAt };
}

function authorizeRequest(db: DatabaseSync, req: IncomingMessage): boolean {
  const totp = getTotpSecret(db);
  if (!totp?.confirmed) {
    return false;
  }
  const header = req.headers.authorization;
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
    return false;
  }
  const token = header.slice('Bearer '.length).trim();
  if (token.length === 0) {
    return false;
  }
  return verifyJwt(token, totp.jwtSecret).valid;
}

function handlePostAuthSetup(db: DatabaseSync, res: ServerResponse): void {
  const existing = getTotpSecret(db);
  if (existing?.confirmed) {
    sendJson(res, 409, {
      error:
        'Es ist bereits ein Google Authenticator aktiv. Zuerst per CLI entfernen ("cl totp remove"), bevor ein neuer eingerichtet wird.',
    });
    return;
  }
  const secret = generateSecret();
  setPendingTotpSecret(db, secret);
  sendJson(res, 200, { secret, otpauthUrl: buildOtpAuthUrl(secret, 'cl-server', 'cl') });
}

function sendHtml(res: ServerResponse, statusCode: number, html: string): void {
  res.writeHead(statusCode, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(html);
}

function htmlPage(title: string, body: string): string {
  return `<!doctype html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 480px; margin: 48px auto; padding: 0 16px; color: #1a1a1a; }
  .qr { margin: 24px 0; }
  .qr svg { width: 240px; height: 240px; }
  code { background: #f0f0f0; padding: 2px 6px; border-radius: 4px; word-break: break-all; }
  .hint { color: #555; font-size: 0.9em; }
</style>
</head>
<body>
${body}
</body>
</html>`;
}

async function handleGetAuthSetup(db: DatabaseSync, res: ServerResponse): Promise<void> {
  let totp = getTotpSecret(db);
  if (totp?.confirmed) {
    sendHtml(
      res,
      200,
      htmlPage(
        'cl server – bereits eingerichtet',
        `<h1>Bereits eingerichtet</h1>
<p>Es ist bereits ein Google Authenticator aktiv. Um einen neuen einzurichten, zuerst auf dem Server <code>cl totp remove</code> ausfuehren.</p>`,
      ),
    );
    return;
  }
  if (!totp) {
    setPendingTotpSecret(db, generateSecret());
    totp = getTotpSecret(db);
  }
  if (!totp) {
    throw new Error('TOTP-Secret konnte nicht angelegt werden.');
  }
  const otpauthUrl = buildOtpAuthUrl(totp.secret, 'cl-server', 'cl');
  const qrSvg = await QRCode.toString(otpauthUrl, { type: 'svg' });
  sendHtml(
    res,
    200,
    htmlPage(
      'cl server – Einrichtung',
      `<h1>Google Authenticator einrichten</h1>
<p>Scanne diesen QR-Code mit Google Authenticator (oder einer kompatiblen App):</p>
<div class="qr">${qrSvg}</div>
<p class="hint">Falls Scannen nicht moeglich ist, trage dieses Secret manuell ein:</p>
<p><code>${totp.secret}</code></p>
<p>Gib danach den 6-stelligen Code in der commander-App ein, um die Einrichtung abzuschliessen.</p>`,
    ),
  );
}

function handlePostAuthSetupConfirm(db: DatabaseSync, res: ServerResponse, bodyText: string): void {
  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: AuthCodeBody;
  try {
    body = parseAuthCodeBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const existing = getTotpSecret(db);
  if (!existing) {
    sendJson(res, 400, {
      error: 'Kein ausstehendes Setup vorhanden. Zuerst POST /auth/setup aufrufen.',
    });
    return;
  }
  if (existing.confirmed) {
    sendJson(res, 409, { error: 'Es ist bereits ein Google Authenticator aktiv.' });
    return;
  }
  if (!verifyTotp(existing.secret, body.code)) {
    sendJson(res, 401, { error: 'Ungueltiger Code.' });
    return;
  }
  confirmTotpSecret(db);
  const { token, expiresAt } = issueAuthToken(existing.jwtSecret);
  sendJson(res, 200, { message: 'Google Authenticator aktiviert.', token, expiresAt });
}

function handlePostAuthLogin(db: DatabaseSync, res: ServerResponse, bodyText: string): void {
  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: AuthCodeBody;
  try {
    body = parseAuthCodeBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const totp = getTotpSecret(db);
  if (!totp?.confirmed) {
    sendJson(res, 400, { error: 'Kein aktiver Google Authenticator eingerichtet.' });
    return;
  }
  if (!verifyTotp(totp.secret, body.code)) {
    sendJson(res, 401, { error: 'Ungueltiger Code.' });
    return;
  }
  const { token, expiresAt } = issueAuthToken(totp.jwtSecret);
  sendJson(res, 200, { token, expiresAt });
}

function handlePostAuthRefresh(db: DatabaseSync, req: IncomingMessage, res: ServerResponse): void {
  const totp = getTotpSecret(db);
  if (!totp?.confirmed) {
    sendJson(res, 400, { error: 'Kein aktiver Google Authenticator eingerichtet.' });
    return;
  }
  const header = req.headers.authorization;
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
    sendJson(res, 401, { error: 'JWT fehlt (Header "Authorization: Bearer <token>").' });
    return;
  }
  const token = header.slice('Bearer '.length).trim();
  if (!verifyJwt(token, totp.jwtSecret).valid) {
    sendJson(res, 401, { error: 'JWT ist ungueltig oder abgelaufen.' });
    return;
  }
  const issued = issueAuthToken(totp.jwtSecret);
  sendJson(res, 200, { token: issued.token, expiresAt: issued.expiresAt });
}

function handleGetHealth(res: ServerResponse): void {
  sendJson(res, 200, { status: 'ok', version: VERSION });
}

function handleGetStatus(res: ServerResponse): void {
  res.writeHead(204);
  res.end();
}

function handleGetAuthStatus(db: DatabaseSync, res: ServerResponse): void {
  const totp = getTotpSecret(db);
  sendJson(res, 200, {
    active: totp?.confirmed ?? false,
    pending: totp !== undefined && !totp.confirmed,
  });
}

function handleGetState(db: DatabaseSync, res: ServerResponse, id: string): void {
  const row = getCommand(db, id);
  if (!row) {
    sendJson(res, 404, { error: `Command "${id}" wurde nicht gefunden.` });
    return;
  }
  sendJson(res, 200, row);
}

/** Laufende Subprozesse je Command-ID, damit `POST /state/:id/stop` sie gezielt beenden kann. */
const runningProcesses = new Map<string, ChildProcess>();

/** IDs, deren Stop bereits angefordert wurde - der `exit`-Handler in `handlePostCommand`/`handlePostPathCommand` markiert den Command dann als "stopped" statt "failed", obwohl SIGTERM einen non-zero/null Exit-Code erzeugt. */
const stopRequestedIds = new Set<string>();

function handlePostStop(db: DatabaseSync, res: ServerResponse, id: string): void {
  const row = getCommand(db, id);
  if (!row) {
    sendJson(res, 404, { error: `Command "${id}" wurde nicht gefunden.` });
    return;
  }
  if (row.status !== 'running') {
    sendJson(res, 409, { error: `Command "${id}" laeuft nicht mehr.` });
    return;
  }
  const child = runningProcesses.get(id);
  if (!child) {
    sendJson(res, 409, { error: `Command "${id}" hat keinen aktiven Prozess auf diesem Server.` });
    return;
  }
  stopRequestedIds.add(id);
  child.kill('SIGTERM');
  sendJson(res, 202, { id });
}

const SSE_HEARTBEAT_MS = 15_000;

/** Pro Command-ID die offenen SSE-Antworten, die auf Output-Updates warten. */
const commandSubscribers = new Map<string, Set<ServerResponse>>();

function addCommandSubscriber(id: string, res: ServerResponse): void {
  let subscribers = commandSubscribers.get(id);
  if (!subscribers) {
    subscribers = new Set();
    commandSubscribers.set(id, subscribers);
  }
  subscribers.add(res);
}

function removeCommandSubscriber(id: string, res: ServerResponse): void {
  const subscribers = commandSubscribers.get(id);
  if (!subscribers) {
    return;
  }
  subscribers.delete(res);
  if (subscribers.size === 0) {
    commandSubscribers.delete(id);
  }
}

function writeCommandEvent(res: ServerResponse, row: CommandRow): void {
  res.write(`data: ${JSON.stringify(row)}\n\n`);
}

/** Schickt den aktuellen Stand an alle SSE-Abonnenten dieser Command-ID; schliesst deren Verbindung, sobald der Command nicht mehr "running" ist. */
function publishCommandUpdate(id: string, row: CommandRow): void {
  const subscribers = commandSubscribers.get(id);
  if (!subscribers) {
    return;
  }
  for (const res of subscribers) {
    writeCommandEvent(res, row);
    if (row.status !== 'running') {
      res.end();
    }
  }
  if (row.status !== 'running') {
    commandSubscribers.delete(id);
  }
}

function publishCommandState(db: DatabaseSync, id: string): void {
  const row = getCommand(db, id);
  if (row) {
    publishCommandUpdate(id, row);
  }
}

/** Wie oft ein laufender Command hoechstens per {@link createOutputPublisher} in die DB geschrieben/per SSE verteilt wird. */
const OUTPUT_PUBLISH_INTERVAL_MS = 250;

interface OutputPublisher {
  /** Meldet den aktuellen Gesamt-Output; schreibt/verteilt gedrosselt statt bei jedem einzelnen stdout/stderr-Chunk. */
  push: (output: string) => void;
  /** Verwirft einen noch ausstehenden verzoegerten Schreibvorgang (vor dem finalen `completeCommand`-Aufruf noetig). */
  cancel: () => void;
}

/**
 * `updateCommandOutput`/`publishCommandState` schreiben bei jedem Aufruf den kompletten bisherigen Output
 * synchron in die DB (node:sqlite kennt keine async API) und verteilen ihn per SSE an alle Abonnenten – bei
 * chattigen Prozessen (z. B. `claude`s Streaming-Output oder ein verbose `gradlew`-Build) kommen stdout/stderr-
 * Chunks oft mehrmals pro Sekunde an, mit wachsendem Output wird jeder einzelne Schreibvorgang teurer. Ohne
 * Drosselung blockiert das den einzigen Node-Event-Loop wiederholt und macht den Server (und damit `commander`,
 * das ueber denselben Prozess pollt/streamt) waehrend eines solchen Laufs spuerbar langsam. `push()` schreibt
 * daher hoechstens alle `OUTPUT_PUBLISH_INTERVAL_MS` einmal (Leading-Edge sofort nach einer Ruhephase, sonst
 * verzoegerte Trailing-Edge mit dem zuletzt gemeldeten Output).
 */
function createOutputPublisher(db: DatabaseSync, id: string): OutputPublisher {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let lastFlushAt = 0;
  let pendingOutput: string | undefined;

  const flush = (output: string): void => {
    lastFlushAt = Date.now();
    updateCommandOutput(db, id, output);
    publishCommandState(db, id);
  };

  const push = (output: string): void => {
    if (timer !== undefined) {
      pendingOutput = output;
      return;
    }
    const elapsed = Date.now() - lastFlushAt;
    if (elapsed >= OUTPUT_PUBLISH_INTERVAL_MS) {
      flush(output);
      return;
    }
    pendingOutput = output;
    timer = setTimeout(() => {
      timer = undefined;
      if (pendingOutput !== undefined) {
        const toFlush = pendingOutput;
        pendingOutput = undefined;
        flush(toFlush);
      }
    }, OUTPUT_PUBLISH_INTERVAL_MS - elapsed);
  };

  const cancel = (): void => {
    if (timer !== undefined) {
      clearTimeout(timer);
      timer = undefined;
    }
    pendingOutput = undefined;
  };

  return { push, cancel };
}

/**
 * Server-Sent Events statt WebSocket: Live-Output ist ein reiner Server->Client-Push (kein Client->Server-
 * Kanal noetig), SSE laeuft ueber eine gewoehnliche GET-Verbindung (kein Upgrade-Handshake, kein Framing-Code,
 * keine zusaetzliche Dependency wie `ws`) und die bestehende Bearer-Token-Authentifizierung greift unveraendert
 * ueber den Authorization-Header. GET /state/:id bleibt als Polling-Fallback bestehen (z.B. falls ein Proxy
 * lang laufende Verbindungen kappt).
 */
function handleGetStateStream(
  db: DatabaseSync,
  req: IncomingMessage,
  res: ServerResponse,
  id: string,
): void {
  const row = getCommand(db, id);
  if (!row) {
    sendJson(res, 404, { error: `Command "${id}" wurde nicht gefunden.` });
    return;
  }

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  writeCommandEvent(res, row);

  if (row.status !== 'running') {
    res.end();
    return;
  }

  addCommandSubscriber(id, res);
  const heartbeat = setInterval(() => {
    res.write(': heartbeat\n\n');
  }, SSE_HEARTBEAT_MS);
  const cleanup = (): void => {
    clearInterval(heartbeat);
    removeCommandSubscriber(id, res);
  };
  req.on('close', cleanup);
  res.on('error', cleanup);
}

function handleGetPaths(config: Config, res: ServerResponse): void {
  sendJson(res, 200, { paths: listPathNames(config) });
}

/** Default-Seitengroesse von `GET /commands/:pathName`, falls kein `?limit=` mitgegeben wird - `commander`s Verlauf-Screen fragt immer in 5er-Schritten ab. */
const DEFAULT_HISTORY_PAGE_SIZE = 5;

function handleGetCommands(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  limitParam: string | null,
  offsetParam: string | null,
): void {
  let pathEntry: PathEntry;
  try {
    pathEntry = resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let limit = DEFAULT_HISTORY_PAGE_SIZE;
  if (limitParam !== null) {
    const parsed = Number(limitParam);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      sendJson(res, 400, { error: 'Query-Parameter "limit" muss eine positive Ganzzahl sein.' });
      return;
    }
    limit = parsed;
  }

  let offset = 0;
  if (offsetParam !== null) {
    const parsed = Number(offsetParam);
    if (!Number.isInteger(parsed) || parsed < 0) {
      sendJson(res, 400, {
        error: 'Query-Parameter "offset" muss eine nicht-negative Ganzzahl sein.',
      });
      return;
    }
    offset = parsed;
  }

  const commands = listCommands(db, pathEntry.path, { limit, offset });
  const total = countCommands(db, pathEntry.path);
  sendJson(res, 200, {
    commands,
    total,
    limit,
    offset,
    hasMore: offset + commands.length < total,
  });
}

function handleGetStats(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  hoursParam: string | null,
): void {
  let pathEntry: PathEntry;
  try {
    pathEntry = resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let windowHours = DEFAULT_STATS_WINDOW_HOURS;
  if (hoursParam !== null) {
    const parsed = Number(hoursParam);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      sendJson(res, 400, { error: 'Query-Parameter "hours" muss eine positive Zahl sein.' });
      return;
    }
    windowHours = parsed;
  }

  const sinceIso = new Date(Date.now() - windowHours * 60 * 60 * 1000).toISOString();
  sendJson(res, 200, {
    runningAgents: countRunningAgents(db, pathEntry.path),
    agentsInWindow: countAgentsSince(db, pathEntry.path, sinceIso),
    windowHours,
    lastDebugBuildAt: findLatestBuildTimestamp(pathEntry.path, 'debug'),
    lastReleaseBuildAt: findLatestBuildTimestamp(pathEntry.path, 'release'),
  });
}

function handleGetManifest(config: Config, res: ServerResponse): void {
  sendJson(res, 200, {
    agents: listAgents(config),
    paths: listPathNames(config).map((name) => ({
      name,
      commands: listPathCommands(config, name),
      hosted: listHostedSummaries(config, name),
    })),
  });
}

interface ConfigState {
  current: Config;
}

const USAGE_CACHE_TTL_MS = 60_000;

interface UsageCacheState {
  entry?: { limits: UsageLimit[]; fetchedAt: number };
}

/** Cached, da jede Abfrage einen "claude"-Subprozess spawnt (~1-2s) - vermeidet wiederholte Spawns bei haeufigem Banner-Polling. */
async function handleGetUsage(cache: UsageCacheState, res: ServerResponse): Promise<void> {
  const cached = cache.entry;
  if (cached && Date.now() - cached.fetchedAt < USAGE_CACHE_TTL_MS) {
    sendJson(res, 200, { limits: cached.limits });
    return;
  }
  const limits = await getUsageLimits();
  cache.entry = { limits, fetchedAt: Date.now() };
  sendJson(res, 200, { limits });
}

/**
 * Aktualisiert die effektive Config im laufenden Server sofort, sodass ab dem naechsten Request
 * ueberall gelesen wird (Agents, Paths, Tasks, ticketAgent, contentPath, collection, Permissions).
 * Ausnahme: databaseDirectory - die offene SQLite-Verbindung wird nicht neu geoeffnet, dafuer ist
 * ein Neustart noetig (sonst wuerde die Versionshistorie unter sich selbst wegwechseln).
 */
function applyConfigReload(configState: ConfigState, newConfig: Config): { warning?: string } {
  const previousDatabaseDirectory = configState.current.databaseDirectory;
  configState.current = newConfig;
  if (newConfig.databaseDirectory !== previousDatabaseDirectory) {
    return {
      warning:
        'databaseDirectory wurde geaendert - fuer den Wechsel der Datenbank ist ein Server-Neustart noetig, die aktuelle Verbindung bleibt bis dahin bestehen.',
    };
  }
  return {};
}

function handleGetConfig(config: Config, res: ServerResponse): void {
  sendJson(res, 200, config);
}

function parsePutConfigBody(raw: unknown): Config {
  return parseConfig(raw);
}

function handlePutConfig(
  db: DatabaseSync,
  configState: ConfigState,
  res: ServerResponse,
  bodyText: string,
): void {
  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let newConfig: Config;
  try {
    newConfig = parsePutConfigBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const version = insertConfigVersion(db, JSON.stringify(newConfig));
  setConfigPointer(db, version.id);
  const { warning } = applyConfigReload(configState, newConfig);
  sendJson(res, 200, {
    versionId: version.id,
    createdAt: version.createdAt,
    config: newConfig,
    warning,
  });
}

function handleGetConfigVersions(db: DatabaseSync, res: ServerResponse): void {
  const versions: ConfigVersionSummary[] = listConfigVersions(db);
  sendJson(res, 200, { versions });
}

function handleGetConfigVersion(db: DatabaseSync, res: ServerResponse, idParam: string): void {
  const id = Number(idParam);
  if (!Number.isInteger(id)) {
    sendJson(res, 400, { error: 'Ungueltige Version-ID.' });
    return;
  }
  const version = getConfigVersion(db, id);
  if (!version) {
    sendJson(res, 404, { error: `Config-Version ${idParam} wurde nicht gefunden.` });
    return;
  }
  sendJson(res, 200, {
    id: version.id,
    createdAt: version.createdAt,
    config: JSON.parse(version.content) as unknown,
  });
}

function handleGetConfigPointer(db: DatabaseSync, res: ServerResponse): void {
  const pointer = getConfigPointer(db);
  sendJson(res, 200, { versionId: pointer?.versionId ?? null });
}

interface ConfigPointerBody {
  versionId: number | null;
}

function parseConfigPointerBody(raw: unknown): ConfigPointerBody {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Ungueltiger Body: erwarte { "versionId": number } oder { "embedded": true }.');
  }
  const record = raw as Record<string, unknown>;
  if (record.embedded === true) {
    return { versionId: null };
  }
  if (typeof record.versionId === 'number' && Number.isInteger(record.versionId)) {
    return { versionId: record.versionId };
  }
  throw new Error('Ungueltiger Body: erwarte { "versionId": number } oder { "embedded": true }.');
}

function handlePutConfigPointer(
  db: DatabaseSync,
  configState: ConfigState,
  res: ServerResponse,
  bodyText: string,
): void {
  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: ConfigPointerBody;
  try {
    body = parseConfigPointerBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let newConfig: Config;
  if (body.versionId === null) {
    newConfig = parseConfig(EMBEDDED_CONFIG);
  } else {
    const version = getConfigVersion(db, body.versionId);
    if (!version) {
      sendJson(res, 404, {
        error: `Config-Version ${String(body.versionId)} wurde nicht gefunden.`,
      });
      return;
    }
    newConfig = parseConfig(JSON.parse(version.content) as unknown);
  }

  setConfigPointer(db, body.versionId);
  const { warning } = applyConfigReload(configState, newConfig);
  sendJson(res, 200, { versionId: body.versionId, config: newConfig, warning });
}

function handleGetPathCommands(config: Config, res: ServerResponse, pathName: string): void {
  try {
    sendJson(res, 200, { commands: listPathCommands(config, pathName) });
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
  }
}

function handlePostPathCommand(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  key: string,
): void {
  let pathEntry: PathEntry;
  let pathCommand: PathCommandEntry;
  try {
    pathEntry = resolvePathEntry(config, pathName);
    pathCommand = resolvePathCommand(config, pathName, key);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const id = randomUUID();
  insertCommand(db, {
    id,
    agent: `path-command:${pathName}:${key}`,
    model: '-',
    command: pathCommand.command,
    path: pathEntry.path,
  });
  sendJson(res, 202, { id });

  const outputPublisher = createOutputPublisher(db, id);
  runShellCommand(
    pathCommand.command,
    pathEntry.path,
    (output) => {
      outputPublisher.push(output);
    },
    (child) => {
      runningProcesses.set(id, child);
    },
  )
    .then((result) => {
      outputPublisher.cancel();
      runningProcesses.delete(id);
      const stopped = stopRequestedIds.delete(id);
      completeCommand(
        db,
        id,
        stopped ? 'stopped' : result.exitCode === 0 ? 'completed' : 'failed',
        result.exitCode,
        result.output,
      );
      publishCommandState(db, id);
    })
    .catch((error: unknown) => {
      outputPublisher.cancel();
      runningProcesses.delete(id);
      stopRequestedIds.delete(id);
      const message = error instanceof Error ? error.message : String(error);
      completeCommand(db, id, 'failed', null, message);
      publishCommandState(db, id);
    });
}

async function handleGetRemoteSessions(
  config: Config,
  res: ServerResponse,
  pathName: string,
): Promise<void> {
  let pathEntry: PathEntry;
  try {
    pathEntry = resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const sessions = await listRemoteSessions(pathEntry.path);
  sendJson(res, 200, { sessions });
}

interface RemoteSessionCreateBody {
  name?: string;
}

function parseRemoteSessionCreateBody(raw: unknown): RemoteSessionCreateBody {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Body muss ein JSON-Objekt sein.');
  }
  const record = raw as Record<string, unknown>;
  if (record.name !== undefined && (typeof record.name !== 'string' || record.name.trim() === '')) {
    throw new Error('Feld "name" muss ein nicht-leerer String sein, falls angegeben.');
  }
  const body: RemoteSessionCreateBody = {};
  if (typeof record.name === 'string') {
    body.name = record.name;
  }
  return body;
}

async function handlePostRemoteSession(
  config: Config,
  res: ServerResponse,
  pathName: string,
  bodyText: string,
): Promise<void> {
  let pathEntry: PathEntry;
  try {
    pathEntry = resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: RemoteSessionCreateBody;
  try {
    body = parseRemoteSessionCreateBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const session = await startRemoteSession(pathEntry.path, body.name);
  sendJson(res, 201, session);
}

function sendFileDownload(res: ServerResponse, filePath: string): void {
  const stat = statSync(filePath);
  res.writeHead(200, {
    'Content-Type': mimeTypeFor(filePath),
    'Content-Length': stat.size,
    'Content-Disposition': `attachment; filename="${basename(filePath)}"`,
  });
  createReadStream(filePath).pipe(res);
}

function handleGetHostedNames(config: Config, res: ServerResponse, pathName: string): void {
  try {
    sendJson(res, 200, { hosted: listHostedNames(config, pathName) });
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
  }
}

function handleGetHostedEntry(
  config: Config,
  res: ServerResponse,
  pathName: string,
  hostedName: string,
): void {
  let entry: HostedEntry;
  try {
    entry = resolveHostedEntry(config, pathName, hostedName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  if (entry.type === 'file') {
    if (!existsSync(entry.path) || !statSync(entry.path).isFile()) {
      sendJson(res, 404, { error: `Datei "${entry.path}" wurde nicht gefunden.` });
      return;
    }
    sendFileDownload(res, entry.path);
    return;
  }

  if (!existsSync(entry.path) || !statSync(entry.path).isDirectory()) {
    sendJson(res, 404, { error: `Verzeichnis "${entry.path}" wurde nicht gefunden.` });
    return;
  }
  const files = readdirSync(entry.path, { withFileTypes: true })
    .filter((dirent) => dirent.isFile())
    .map((dirent) => ({
      name: dirent.name,
      timestamp: statSync(join(entry.path, dirent.name)).mtime.toISOString(),
    }));
  sendJson(res, 200, { files });
}

function handleGetHostedFile(
  config: Config,
  res: ServerResponse,
  pathName: string,
  hostedName: string,
  fileName: string,
): void {
  let entry: HostedEntry;
  try {
    entry = resolveHostedEntry(config, pathName, hostedName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  if (entry.type !== 'path') {
    sendJson(res, 404, { error: `"${hostedName}" ist keine Verzeichnis-Freigabe.` });
    return;
  }

  const directory = resolve(entry.path);
  const filePath = resolve(join(directory, fileName));
  if (filePath !== directory && !filePath.startsWith(directory + sep)) {
    sendJson(res, 400, { error: 'Ungueltiger Dateiname.' });
    return;
  }
  if (!existsSync(filePath) || !statSync(filePath).isFile()) {
    sendJson(res, 404, { error: `Datei "${fileName}" wurde nicht gefunden.` });
    return;
  }
  sendFileDownload(res, filePath);
}

function handleGetCollections(config: Config, res: ServerResponse): void {
  sendJson(res, 200, { files: listCollectedFiles(config.contentPath) });
}

function handleGetCollectionFile(config: Config, res: ServerResponse, name: string): void {
  let filePath: string;
  try {
    filePath = resolveCollectedFilePath(config.contentPath, name);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  if (!existsSync(filePath) || !statSync(filePath).isFile()) {
    sendJson(res, 404, { error: `Datei "${name}" wurde nicht gefunden.` });
    return;
  }
  sendFileDownload(res, filePath);
}

function handlePostCollect(config: Config, res: ServerResponse, bodyText: string): void {
  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: CollectRequestBody;
  try {
    body = parseCollectRequestBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  if (body.targetName === undefined) {
    sendJson(res, 200, collectAll(config));
    return;
  }
  try {
    sendJson(res, 200, collectOne(config, body.targetName));
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
  }
}

function handlePostCollectForPath(config: Config, res: ServerResponse, pathName: string): void {
  try {
    sendJson(res, 200, collectForPath(config, pathName));
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
  }
}

function handleGetFeedback(db: DatabaseSync, res: ServerResponse): void {
  sendJson(res, 200, { feedback: listFeedback(db) });
}

function handleGetFeedbackForPath(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
): void {
  try {
    resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  sendJson(res, 200, { feedback: listFeedback(db, pathName) });
}

function handlePostFeedback(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  bodyText: string,
): void {
  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: FeedbackTextBody;
  try {
    body = parseFeedbackTextBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const path =
    body.section === null ? null : (resolveCollectionPathForFileName(config, body.section) ?? null);
  sendJson(res, 201, insertFeedback(db, body.text, body.section, body.context, path));
}

function handlePatchFeedback(
  db: DatabaseSync,
  res: ServerResponse,
  idParam: string,
  bodyText: string,
): void {
  if (!/^\d+$/.test(idParam) || !getFeedback(db, Number(idParam))) {
    sendJson(res, 404, { error: `Feedback "${idParam}" wurde nicht gefunden.` });
    return;
  }

  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: FeedbackTextBody;
  try {
    body = parseFeedbackTextBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  sendJson(res, 200, updateFeedback(db, Number(idParam), body.text));
}

function handleDeleteFeedback(db: DatabaseSync, res: ServerResponse, idParam: string): void {
  if (!/^\d+$/.test(idParam) || !deleteFeedback(db, Number(idParam))) {
    sendJson(res, 404, { error: `Feedback "${idParam}" wurde nicht gefunden.` });
    return;
  }
  sendJson(res, 200, { message: `Feedback "${idParam}" wurde geloescht.` });
}

function getTicketInPath(
  db: DatabaseSync,
  pathName: string,
  idParam: string,
): TicketRow | undefined {
  if (!/^\d+$/.test(idParam)) {
    return undefined;
  }
  const ticket = getTicket(db, Number(idParam));
  return ticket?.pathName === pathName ? ticket : undefined;
}

function handleGetTickets(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  statusParam: string | null,
): void {
  try {
    resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  let status: TicketStatus | undefined;
  try {
    status = parseTicketStatusQuery(statusParam);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  sendJson(res, 200, { tickets: listTickets(db, pathName, status) });
}

function handleGetAllTickets(
  db: DatabaseSync,
  res: ServerResponse,
  statusParam: string | null,
): void {
  let status: TicketStatus | undefined;
  try {
    status = parseTicketStatusQuery(statusParam);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  sendJson(res, 200, { tickets: listAllTickets(db, status) });
}

function handlePostTicket(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  bodyText: string,
  ipAddress: string | null,
): void {
  let pathEntry: PathEntry;
  try {
    pathEntry = resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: TicketCreateBody;
  try {
    body = parseTicketCreateBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const ticket = insertGeneratingTicket(db, { pathName, originalRequest: body.text, ipAddress });
  sendJson(res, 201, ticket);

  void runTicketAgent(pathEntry.path, config.ticketAgent, body.text)
    .then((output: TicketAgentOutput) => {
      updateTicket(db, ticket.id, { ...output, status: 'open' });
    })
    .catch((error: unknown) => {
      updateTicket(db, ticket.id, {
        summary: `Ticket-Agent fehlgeschlagen: ${error instanceof Error ? error.message : String(error)}`,
        status: 'rejected',
      });
    });
}

function handleGetTicket(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  idParam: string,
): void {
  try {
    resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  const ticket = getTicketInPath(db, pathName, idParam);
  if (!ticket) {
    sendJson(res, 404, {
      error: `Ticket "${idParam}" wurde in Pfad "${pathName}" nicht gefunden.`,
    });
    return;
  }
  sendJson(res, 200, ticket);
}

function handlePatchTicket(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  idParam: string,
  bodyText: string,
): void {
  try {
    resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  const existing = getTicketInPath(db, pathName, idParam);
  if (!existing) {
    sendJson(res, 404, {
      error: `Ticket "${idParam}" wurde in Pfad "${pathName}" nicht gefunden.`,
    });
    return;
  }

  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let update: TicketUpdate;
  try {
    update = parseTicketUpdateBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  sendJson(res, 200, updateTicket(db, existing.id, update));
}

function handleDeleteTicket(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  idParam: string,
): void {
  try {
    resolvePathEntry(config, pathName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  const existing = getTicketInPath(db, pathName, idParam);
  if (!existing) {
    sendJson(res, 404, {
      error: `Ticket "${idParam}" wurde in Pfad "${pathName}" nicht gefunden.`,
    });
    return;
  }
  deleteTicket(db, existing.id);
  sendJson(res, 200, { message: `Ticket "${String(existing.id)}" wurde geloescht.` });
}

function triggerOnLastAgentFinishHook(db: DatabaseSync, pathEntry: PathEntry): void {
  const hookCommand = pathEntry.hooks?.onLastAgentFinish;
  if (hookCommand === undefined || countRunningAgents(db, pathEntry.path) > 0) {
    return;
  }

  const id = randomUUID();
  insertCommand(db, {
    id,
    agent: `hook:${pathEntry.name}:onLastAgentFinish`,
    model: '-',
    command: hookCommand,
    path: pathEntry.path,
  });
  publishCommandState(db, id);

  const outputPublisher = createOutputPublisher(db, id);
  runShellCommand(
    hookCommand,
    pathEntry.path,
    (output) => {
      outputPublisher.push(output);
    },
    (child) => {
      runningProcesses.set(id, child);
    },
  )
    .then((result) => {
      outputPublisher.cancel();
      runningProcesses.delete(id);
      const stopped = stopRequestedIds.delete(id);
      completeCommand(
        db,
        id,
        stopped ? 'stopped' : result.exitCode === 0 ? 'completed' : 'failed',
        result.exitCode,
        result.output,
      );
      publishCommandState(db, id);
    })
    .catch((error: unknown) => {
      outputPublisher.cancel();
      runningProcesses.delete(id);
      stopRequestedIds.delete(id);
      const message = error instanceof Error ? error.message : String(error);
      completeCommand(db, id, 'failed', null, message);
      publishCommandState(db, id);
      console.error(
        `onLastAgentFinish-Hook fuer Pfad "${pathEntry.name}" fehlgeschlagen:`,
        message,
      );
    });
}

function handlePostCommand(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  agentName: string | undefined,
  bodyText: string,
): void {
  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: CommandRequestBody;
  try {
    body = parseCommandRequestBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let agent: AgentDefinition;
  try {
    agent = resolveAgentFrom(config, agentName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let pathEntry: PathEntry;
  try {
    pathEntry = resolvePathEntry(config, body.path);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }
  const cwd = pathEntry.path;

  const model = body.model ?? agent.model;
  const permissions = body.permissions ?? agent.permissions;
  const id = randomUUID();
  const resolvedAgentName = agentName ?? 'main';

  insertCommand(db, { id, agent: resolvedAgentName, model, command: body.command, path: cwd });
  sendJson(res, 202, { id });

  const outputPublisher = createOutputPublisher(db, id);
  runHeadlessCommand(
    agent,
    model,
    body.command,
    cwd,
    (output) => {
      outputPublisher.push(output);
    },
    permissions,
    (child) => {
      runningProcesses.set(id, child);
    },
  )
    .then((result) => {
      outputPublisher.cancel();
      runningProcesses.delete(id);
      const stopped = stopRequestedIds.delete(id);
      completeCommand(
        db,
        id,
        stopped ? 'stopped' : result.exitCode === 0 ? 'completed' : 'failed',
        result.exitCode,
        result.output,
      );
      publishCommandState(db, id);
      triggerOnLastAgentFinishHook(db, pathEntry);
    })
    .catch((error: unknown) => {
      outputPublisher.cancel();
      runningProcesses.delete(id);
      stopRequestedIds.delete(id);
      const message = error instanceof Error ? error.message : String(error);
      completeCommand(db, id, 'failed', null, message);
      publishCommandState(db, id);
      triggerOnLastAgentFinishHook(db, pathEntry);
    });
}

async function handleRequest(
  db: DatabaseSync,
  configState: ConfigState,
  usageCache: UsageCacheState,
  req: IncomingMessage,
  res: ServerResponse,
): Promise<void> {
  const config = configState.current;
  const method = req.method ?? 'GET';
  const url = new URL(req.url ?? '/', 'http://localhost');
  const segments = url.pathname.split('/').filter((segment) => segment.length > 0);
  let bodyText: string | undefined;

  try {
    if (segments[0] === 'auth') {
      if (!isLocalNetworkAddress(req.socket.remoteAddress)) {
        sendJson(res, 404, { error: 'Route nicht gefunden.' });
      } else if (method === 'GET' && segments.length === 2 && segments[1] === 'setup') {
        await handleGetAuthSetup(db, res);
      } else if (method === 'POST' && segments.length === 2 && segments[1] === 'setup') {
        handlePostAuthSetup(db, res);
      } else if (
        method === 'POST' &&
        segments.length === 3 &&
        segments[1] === 'setup' &&
        segments[2] === 'confirm'
      ) {
        bodyText = await readRequestBody(req);
        handlePostAuthSetupConfirm(db, res, bodyText);
      } else if (method === 'POST' && segments.length === 2 && segments[1] === 'login') {
        bodyText = await readRequestBody(req);
        handlePostAuthLogin(db, res, bodyText);
      } else if (method === 'POST' && segments.length === 2 && segments[1] === 'refresh') {
        handlePostAuthRefresh(db, req, res);
      } else if (method === 'GET' && segments.length === 2 && segments[1] === 'status') {
        handleGetAuthStatus(db, res);
      } else {
        sendJson(res, 404, { error: 'Route nicht gefunden.' });
      }
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'health') {
      handleGetHealth(res);
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'status') {
      handleGetStatus(res);
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'collections') {
      handleGetCollections(config, res);
    } else if (
      method === 'GET' &&
      segments.length === 3 &&
      segments[0] === 'collections' &&
      segments[1] === 'get'
    ) {
      handleGetCollectionFile(config, res, segments[2] ?? '');
    } else if (method === 'POST' && segments.length === 1 && segments[0] === 'feedback') {
      bodyText = await readRequestBody(req);
      handlePostFeedback(db, config, res, bodyText);
    } else if (!authorizeRequest(db, req)) {
      sendJson(res, 401, {
        error: 'JWT fehlt oder ist ungueltig/abgelaufen (Header "Authorization: Bearer <token>").',
      });
    } else if (
      method === 'GET' &&
      segments.length === 3 &&
      segments[0] === 'state' &&
      segments[2] === 'stream'
    ) {
      handleGetStateStream(db, req, res, segments[1] ?? '');
    } else if (
      method === 'POST' &&
      segments.length === 3 &&
      segments[0] === 'state' &&
      segments[2] === 'stop'
    ) {
      handlePostStop(db, res, segments[1] ?? '');
    } else if (method === 'GET' && segments.length === 2 && segments[0] === 'state') {
      handleGetState(db, res, segments[1] ?? '');
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'paths') {
      handleGetPaths(config, res);
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'manifest') {
      handleGetManifest(config, res);
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'config') {
      handleGetConfig(config, res);
    } else if (method === 'PUT' && segments.length === 1 && segments[0] === 'config') {
      bodyText = await readRequestBody(req);
      handlePutConfig(db, configState, res, bodyText);
    } else if (
      method === 'GET' &&
      segments.length === 2 &&
      segments[0] === 'config' &&
      segments[1] === 'versions'
    ) {
      handleGetConfigVersions(db, res);
    } else if (
      method === 'GET' &&
      segments.length === 3 &&
      segments[0] === 'config' &&
      segments[1] === 'versions'
    ) {
      handleGetConfigVersion(db, res, segments[2] ?? '');
    } else if (
      method === 'GET' &&
      segments.length === 2 &&
      segments[0] === 'config' &&
      segments[1] === 'pointer'
    ) {
      handleGetConfigPointer(db, res);
    } else if (
      method === 'PUT' &&
      segments.length === 2 &&
      segments[0] === 'config' &&
      segments[1] === 'pointer'
    ) {
      bodyText = await readRequestBody(req);
      handlePutConfigPointer(db, configState, res, bodyText);
    } else if (method === 'GET' && segments.length === 2 && segments[0] === 'commands') {
      handleGetCommands(
        db,
        config,
        res,
        segments[1] ?? '',
        url.searchParams.get('limit'),
        url.searchParams.get('offset'),
      );
    } else if (method === 'GET' && segments.length === 2 && segments[0] === 'stats') {
      handleGetStats(db, config, res, segments[1] ?? '', url.searchParams.get('hours'));
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'usage') {
      await handleGetUsage(usageCache, res);
    } else if (
      method === 'GET' &&
      segments.length === 3 &&
      segments[0] === 'paths' &&
      segments[2] === 'commands'
    ) {
      handleGetPathCommands(config, res, segments[1] ?? '');
    } else if (
      method === 'POST' &&
      segments.length === 4 &&
      segments[0] === 'paths' &&
      segments[2] === 'commands'
    ) {
      handlePostPathCommand(db, config, res, segments[1] ?? '', segments[3] ?? '');
    } else if (
      method === 'GET' &&
      segments.length === 3 &&
      segments[0] === 'paths' &&
      segments[2] === 'remote-sessions'
    ) {
      await handleGetRemoteSessions(config, res, segments[1] ?? '');
    } else if (
      method === 'POST' &&
      segments.length === 3 &&
      segments[0] === 'paths' &&
      segments[2] === 'remote-sessions'
    ) {
      bodyText = await readRequestBody(req);
      await handlePostRemoteSession(config, res, segments[1] ?? '', bodyText);
    } else if (method === 'GET' && segments.length === 2 && segments[0] === 'files') {
      handleGetHostedNames(config, res, segments[1] ?? '');
    } else if (method === 'GET' && segments.length === 3 && segments[0] === 'files') {
      handleGetHostedEntry(config, res, segments[1] ?? '', segments[2] ?? '');
    } else if (method === 'GET' && segments.length === 4 && segments[0] === 'files') {
      handleGetHostedFile(config, res, segments[1] ?? '', segments[2] ?? '', segments[3] ?? '');
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'tickets') {
      handleGetAllTickets(db, res, url.searchParams.get('status'));
    } else if (method === 'GET' && segments.length === 2 && segments[0] === 'tickets') {
      handleGetTickets(db, config, res, segments[1] ?? '', url.searchParams.get('status'));
    } else if (method === 'POST' && segments.length === 2 && segments[0] === 'tickets') {
      bodyText = await readRequestBody(req);
      handlePostTicket(
        db,
        config,
        res,
        segments[1] ?? '',
        bodyText,
        req.socket.remoteAddress ?? null,
      );
    } else if (method === 'GET' && segments.length === 3 && segments[0] === 'tickets') {
      handleGetTicket(db, config, res, segments[1] ?? '', segments[2] ?? '');
    } else if (method === 'PATCH' && segments.length === 3 && segments[0] === 'tickets') {
      bodyText = await readRequestBody(req);
      handlePatchTicket(db, config, res, segments[1] ?? '', segments[2] ?? '', bodyText);
    } else if (method === 'DELETE' && segments.length === 3 && segments[0] === 'tickets') {
      handleDeleteTicket(db, config, res, segments[1] ?? '', segments[2] ?? '');
    } else if (method === 'POST' && segments.length === 1 && segments[0] === 'collect') {
      bodyText = await readRequestBody(req);
      handlePostCollect(config, res, bodyText);
    } else if (method === 'POST' && segments.length === 2 && segments[0] === 'collect') {
      handlePostCollectForPath(config, res, segments[1] ?? '');
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'feedback') {
      handleGetFeedback(db, res);
    } else if (method === 'GET' && segments.length === 2 && segments[0] === 'feedback') {
      handleGetFeedbackForPath(db, config, res, segments[1] ?? '');
    } else if (method === 'PATCH' && segments.length === 2 && segments[0] === 'feedback') {
      bodyText = await readRequestBody(req);
      handlePatchFeedback(db, res, segments[1] ?? '', bodyText);
    } else if (method === 'DELETE' && segments.length === 2 && segments[0] === 'feedback') {
      handleDeleteFeedback(db, res, segments[1] ?? '');
    } else if (method === 'POST' && segments.length <= 1) {
      bodyText = await readRequestBody(req);
      handlePostCommand(db, config, res, segments[0], bodyText);
    } else {
      sendJson(res, 404, { error: 'Route nicht gefunden.' });
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    sendJson(res, 500, { error: message });
  }

  console.log(
    `${new Date().toISOString()} ${method} ${url.pathname} -> ${res.statusCode.toString()}`,
  );
  logAccess(db, method, url.pathname, res.statusCode, bodyText);
}

function printEndpoints(config: Config, port: number): void {
  const base = `http://localhost:${port.toString()}`;
  console.log('Endpunkte:');
  console.log(
    '(ausser /health, /status und /auth/* verlangen alle den Header "Authorization: Bearer <jwt>")',
  );
  console.log(`  GET  ${base}/health                (kein Auth noetig)`);
  console.log(
    `  GET  ${base}/status                (kein Auth noetig, 204 leer, fuer Auto-Discovery)`,
  );
  console.log(
    `  GET  ${base}/auth/setup           (nur aus dem lokalen Netz, sonst 404 - zeigt QR-Code)`,
  );
  console.log(`  POST ${base}/auth/setup           (nur aus dem lokalen Netz, sonst 404)`);
  console.log(
    `  POST ${base}/auth/setup/confirm   (nur aus dem lokalen Netz, sonst 404 - liefert JWT)`,
  );
  console.log(
    `  POST ${base}/auth/login           (nur aus dem lokalen Netz, sonst 404 - liefert JWT)`,
  );
  console.log(
    `  POST ${base}/auth/refresh         (nur aus dem lokalen Netz, sonst 404 - verlaengert JWT)`,
  );
  console.log(`  GET  ${base}/auth/status          (nur aus dem lokalen Netz, sonst 404)`);
  console.log(`  POST ${base}/`);
  for (const agent of config.agents) {
    console.log(`  POST ${base}/${agent.name}`);
  }
  console.log(`  GET  ${base}/state/:id`);
  console.log(
    `  GET  ${base}/state/:id/stream (Server-Sent Events, Live-Output; Fallback: Polling ueber GET /state/:id)`,
  );
  console.log(`  POST ${base}/state/:id/stop    (beendet einen laufenden Command)`);
  console.log(`  GET  ${base}/paths`);
  console.log(`  GET  ${base}/manifest`);
  console.log(`  GET  ${base}/config`);
  console.log(
    `  PUT  ${base}/config                 (neue Version speichern, Zeiger setzen, sofort aktiv)`,
  );
  console.log(`  GET  ${base}/config/versions`);
  console.log(`  GET  ${base}/config/versions/:id`);
  console.log(`  GET  ${base}/config/pointer         (null = fest reinkompilierte Version aktiv)`);
  console.log(
    `  PUT  ${base}/config/pointer         ({ "versionId": number } oder { "embedded": true })`,
  );
  console.log(
    `  GET  ${base}/commands/:pathName     (optionale Query-Parameter ?limit=, Default ${String(DEFAULT_HISTORY_PAGE_SIZE)}, und ?offset=)`,
  );
  console.log(
    `  GET  ${base}/stats/:pathName        (optionaler Query-Parameter ?hours=, Default 24)`,
  );
  console.log(
    `  GET  ${base}/usage                  (Claude-Code-Nutzungslimits, ${(USAGE_CACHE_TTL_MS / 1000).toString()}s gecacht)`,
  );
  console.log(`  GET  ${base}/paths/:pathName/commands`);
  for (const pathEntry of config.paths) {
    for (const command of listPathCommands(config, pathEntry.name)) {
      console.log(`  POST ${base}/paths/${pathEntry.name}/commands/${command.key}`);
    }
  }
  console.log(`  GET  ${base}/paths/:pathName/remote-sessions`);
  console.log(
    `  POST ${base}/paths/:pathName/remote-sessions (startet eine "claude --bg --remote-control"-Session)`,
  );
  console.log(`  GET  ${base}/files/:pathName`);
  console.log(`  GET  ${base}/files/:pathName/:hostedName`);
  console.log(`  GET  ${base}/files/:pathName/:hostedName/:fileName`);
  console.log(`  GET  ${base}/tickets`);
  console.log(`  GET  ${base}/tickets/:pathName`);
  console.log(`  POST ${base}/tickets/:pathName`);
  console.log(`  GET  ${base}/tickets/:pathName/:id`);
  console.log(`  PATCH ${base}/tickets/:pathName/:id`);
  console.log(`  DELETE ${base}/tickets/:pathName/:id`);
  console.log(`  POST ${base}/collect`);
  console.log(`  POST ${base}/collect/:pathName      (sammelt nur die Eintraege dieses Pfads)`);
  console.log(`  GET  ${base}/collections            (kein Auth noetig)`);
  console.log(`  GET  ${base}/collections/get/:name  (kein Auth noetig)`);
  console.log(`  POST ${base}/feedback               (kein Auth noetig)`);
  console.log(`  GET  ${base}/feedback`);
  console.log(`  GET  ${base}/feedback/:pathName`);
  console.log(`  PATCH ${base}/feedback/:id`);
  console.log(`  DELETE ${base}/feedback/:id`);
}

export interface RunningServer {
  readonly port: number;
  ready: Promise<void>;
  close: () => Promise<void>;
}

export function startServer(
  config: Config,
  port: number,
  pathsOverride?: PathEntry[],
): RunningServer {
  const db = openDatabase(config.databaseDirectory);
  ensureConfigBootstrapped(db);
  let effectiveConfig = resolveEffectiveConfig(db);
  if (pathsOverride) {
    effectiveConfig = applyPathsOverride(effectiveConfig, pathsOverride);
  }
  const configState: ConfigState = { current: effectiveConfig };
  const usageCache: UsageCacheState = {};

  const server = createServer((req, res) => {
    handleRequest(db, configState, usageCache, req, res).catch((error: unknown) => {
      console.error(error instanceof Error ? error.message : error);
    });
  });

  let actualPort = port;

  const ready = new Promise<void>((resolve) => {
    server.listen(port, () => {
      const address = server.address();
      if (address !== null && typeof address === 'object') {
        actualPort = address.port;
      }
      console.log(`cl server laeuft auf http://localhost:${actualPort.toString()}`);
      printEndpoints(configState.current, actualPort);
      resolve();
    });
  });

  const close = async (): Promise<void> => {
    process.off('SIGINT', shutdown);
    process.off('SIGTERM', shutdown);
    await new Promise<void>((resolve, reject) => {
      server.close((error) => {
        if (error) {
          reject(error);
        } else {
          resolve();
        }
      });
    });
    db.close();
  };

  const shutdown = (): void => {
    close()
      .catch((error: unknown) => {
        console.error(error instanceof Error ? error.message : error);
      })
      .finally(() => {
        process.exit(0);
      });
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  return {
    get port() {
      return actualPort;
    },
    ready,
    close,
  };
}
