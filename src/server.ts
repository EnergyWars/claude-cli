import { randomUUID } from 'node:crypto';
import { createReadStream, existsSync, readdirSync, statSync } from 'node:fs';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { basename, extname, join, resolve, sep } from 'node:path';
import type { DatabaseSync } from 'node:sqlite';

import QRCode from 'qrcode';

import {
  completeCommand,
  confirmTotpSecret,
  deleteTicket,
  getCommand,
  getTicket,
  getTotpSecret,
  insertCommand,
  insertTicket,
  isTicketStatus,
  listAllTickets,
  listTickets,
  logAccess,
  openDatabase,
  setPendingTotpSecret,
  TICKET_STATUSES,
  updateCommandOutput,
  updateTicket,
  type TicketRow,
  type TicketStatus,
  type TicketUpdate,
} from './db.js';
import {
  type AgentDefinition,
  type Config,
  type HostedEntry,
  type PathCommandEntry,
  type PathEntry,
  listAgents,
  listHostedNames,
  listHostedSummaries,
  listPathCommands,
  listPathNames,
  resolveAgent,
  resolveHostedEntry,
  resolvePath,
  resolvePathCommand,
  resolvePathEntry,
} from './config.js';
import { signJwt, verifyJwt } from './jwt.js';
import { runHeadlessCommand, runShellCommand } from './launch.js';
import { isLocalNetworkAddress } from './network.js';
import { runTicketAgent, type TicketAgentOutput } from './ticket.js';
import { buildOtpAuthUrl, generateSecret, verifyTotp } from './totp.js';
import { VERSION } from './version.js';

const JWT_TTL_SECONDS = 60 * 60;

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

function handleGetPaths(config: Config, res: ServerResponse): void {
  sendJson(res, 200, { paths: listPathNames(config) });
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

  runShellCommand(pathCommand.command, pathEntry.path, (output) => {
    updateCommandOutput(db, id, output);
  })
    .then((result) => {
      completeCommand(
        db,
        id,
        result.exitCode === 0 ? 'completed' : 'failed',
        result.exitCode,
        result.output,
      );
    })
    .catch((error: unknown) => {
      const message = error instanceof Error ? error.message : String(error);
      completeCommand(db, id, 'failed', null, message);
    });
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
    .map((dirent) => dirent.name);
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

async function handlePostTicket(
  db: DatabaseSync,
  config: Config,
  res: ServerResponse,
  pathName: string,
  bodyText: string,
  ipAddress: string | null,
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

  let body: TicketCreateBody;
  try {
    body = parseTicketCreateBody(parsedBody);
  } catch (error) {
    sendJson(res, 400, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let output: TicketAgentOutput;
  try {
    output = await runTicketAgent(pathEntry.path, config.ticketAgent, body.text);
  } catch (error) {
    sendJson(res, 502, {
      error: `Ticket-Agent fehlgeschlagen: ${error instanceof Error ? error.message : String(error)}`,
    });
    return;
  }
  const ticket = insertTicket(db, { pathName, originalRequest: body.text, ipAddress, ...output });
  sendJson(res, 201, ticket);
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
    agent = resolveAgent(agentName);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  let cwd: string;
  try {
    cwd = resolvePath(config, body.path);
  } catch (error) {
    sendJson(res, 404, { error: error instanceof Error ? error.message : String(error) });
    return;
  }

  const model = body.model ?? agent.model;
  const permissions = body.permissions ?? agent.permissions;
  const id = randomUUID();
  const resolvedAgentName = agentName ?? 'main';

  insertCommand(db, { id, agent: resolvedAgentName, model, command: body.command, path: cwd });
  sendJson(res, 202, { id });

  runHeadlessCommand(
    agent,
    model,
    body.command,
    cwd,
    (output) => {
      updateCommandOutput(db, id, output);
    },
    permissions,
  )
    .then((result) => {
      completeCommand(
        db,
        id,
        result.exitCode === 0 ? 'completed' : 'failed',
        result.exitCode,
        result.output,
      );
    })
    .catch((error: unknown) => {
      const message = error instanceof Error ? error.message : String(error);
      completeCommand(db, id, 'failed', null, message);
    });
}

async function handleRequest(
  db: DatabaseSync,
  config: Config,
  req: IncomingMessage,
  res: ServerResponse,
): Promise<void> {
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
      } else if (method === 'GET' && segments.length === 2 && segments[1] === 'status') {
        handleGetAuthStatus(db, res);
      } else {
        sendJson(res, 404, { error: 'Route nicht gefunden.' });
      }
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'health') {
      handleGetHealth(res);
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'status') {
      handleGetStatus(res);
    } else if (!authorizeRequest(db, req)) {
      sendJson(res, 401, {
        error: 'JWT fehlt oder ist ungueltig/abgelaufen (Header "Authorization: Bearer <token>").',
      });
    } else if (method === 'GET' && segments.length === 2 && segments[0] === 'state') {
      handleGetState(db, res, segments[1] ?? '');
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'paths') {
      handleGetPaths(config, res);
    } else if (method === 'GET' && segments.length === 1 && segments[0] === 'manifest') {
      handleGetManifest(config, res);
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
      await handlePostTicket(
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
  console.log(`  GET  ${base}/auth/status          (nur aus dem lokalen Netz, sonst 404)`);
  console.log(`  POST ${base}/`);
  for (const agent of config.agents) {
    console.log(`  POST ${base}/${agent.name}`);
  }
  console.log(`  GET  ${base}/state/:id`);
  console.log(`  GET  ${base}/paths`);
  console.log(`  GET  ${base}/manifest`);
  console.log(`  GET  ${base}/paths/:pathName/commands`);
  for (const pathEntry of config.paths) {
    for (const command of pathEntry.commands ?? []) {
      console.log(`  POST ${base}/paths/${pathEntry.name}/commands/${command.key}`);
    }
  }
  console.log(`  GET  ${base}/files/:pathName`);
  console.log(`  GET  ${base}/files/:pathName/:hostedName`);
  console.log(`  GET  ${base}/files/:pathName/:hostedName/:fileName`);
  console.log(`  GET  ${base}/tickets`);
  console.log(`  GET  ${base}/tickets/:pathName`);
  console.log(`  POST ${base}/tickets/:pathName`);
  console.log(`  GET  ${base}/tickets/:pathName/:id`);
  console.log(`  PATCH ${base}/tickets/:pathName/:id`);
  console.log(`  DELETE ${base}/tickets/:pathName/:id`);
}

export interface RunningServer {
  readonly port: number;
  ready: Promise<void>;
  close: () => Promise<void>;
}

export function startServer(config: Config, port: number): RunningServer {
  const db = openDatabase(config.databaseDirectory);

  const server = createServer((req, res) => {
    handleRequest(db, config, req, res).catch((error: unknown) => {
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
      printEndpoints(config, actualPort);
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
