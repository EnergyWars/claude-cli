import { randomUUID } from 'node:crypto';
import { createReadStream, existsSync, readdirSync, statSync } from 'node:fs';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { basename, extname, join, resolve, sep } from 'node:path';
import type { DatabaseSync } from 'node:sqlite';

import {
  completeCommand,
  confirmTotpSecret,
  getCommand,
  getTotpSecret,
  insertCommand,
  logAccess,
  openDatabase,
  setPendingTotpSecret,
  updateCommandOutput,
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
import { runHeadlessCommand, runShellCommand } from './launch.js';
import { isLocalNetworkAddress } from './network.js';
import { buildOtpAuthUrl, generateSecret, verifyTotp } from './totp.js';

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
}

interface AuthSetupConfirmBody {
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
  return typeof record.model === 'string'
    ? { command: record.command, model: record.model, path: record.path }
    : { command: record.command, path: record.path };
}

function parseAuthSetupConfirmBody(raw: unknown): AuthSetupConfirmBody {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('Body muss ein JSON-Objekt sein.');
  }
  const record = raw as Record<string, unknown>;
  if (typeof record.code !== 'string' || record.code.trim() === '') {
    throw new Error('Feld "code" (nicht-leerer String) ist erforderlich.');
  }
  return { code: record.code };
}

function isRequestAuthorized(db: DatabaseSync, req: IncomingMessage): boolean {
  const totp = getTotpSecret(db);
  if (!totp?.confirmed) {
    return false;
  }
  const header = req.headers['x-totp-code'];
  const code = Array.isArray(header) ? header[0] : header;
  if (typeof code !== 'string') {
    return false;
  }
  return verifyTotp(totp.secret, code);
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

function handlePostAuthSetupConfirm(db: DatabaseSync, res: ServerResponse, bodyText: string): void {
  let parsedBody: unknown;
  try {
    parsedBody = bodyText.length > 0 ? JSON.parse(bodyText) : {};
  } catch {
    sendJson(res, 400, { error: 'Body ist kein gueltiges JSON.' });
    return;
  }

  let body: AuthSetupConfirmBody;
  try {
    body = parseAuthSetupConfirmBody(parsedBody);
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
  sendJson(res, 200, { message: 'Google Authenticator aktiviert.' });
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
  const id = randomUUID();
  const resolvedAgentName = agentName ?? 'main';

  insertCommand(db, { id, agent: resolvedAgentName, model, command: body.command, path: cwd });
  sendJson(res, 202, { id });

  runHeadlessCommand(agent, model, body.command, cwd, (output) => {
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
      } else {
        sendJson(res, 404, { error: 'Route nicht gefunden.' });
      }
    } else if (!isRequestAuthorized(db, req)) {
      sendJson(res, 401, {
        error: 'TOTP-Code fehlt oder ist ungueltig (Header "X-TOTP-Code").',
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
  console.log('(ausser /auth/setup* verlangen alle den Header "X-TOTP-Code")');
  console.log(`  POST ${base}/auth/setup           (nur aus dem lokalen Netz, sonst 404)`);
  console.log(`  POST ${base}/auth/setup/confirm   (nur aus dem lokalen Netz, sonst 404)`);
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
