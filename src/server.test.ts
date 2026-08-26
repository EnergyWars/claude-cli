import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';

import { loadConfig } from './config.js';
import { startServer, type RunningServer } from './server.js';
import { generateTotp } from './totp.js';
import { createFixtureRoot, type Fixture } from './test-support/fixture-config.js';
import {
  createMockClaude,
  extractMockArgs,
  pathWithMock,
  type MockClaude,
} from './test-support/mock-claude.js';

let fixture: Fixture;
let mock: MockClaude;
let running: RunningServer;
let previousRootDir: string | undefined;
let previousPath: string | undefined;
let hostedDir: string;
let authToken: string;

before(async () => {
  hostedDir = mkdtempSync(join(tmpdir(), 'cl-hosted-'));
  writeFileSync(join(hostedDir, 'notes.txt'), 'hosted-file-inhalt');
  mkdirSync(join(hostedDir, 'docs'));
  writeFileSync(join(hostedDir, 'docs', 'a.txt'), 'a-inhalt');
  writeFileSync(join(hostedDir, 'docs', 'b.txt'), 'b-inhalt');
  mkdirSync(join(hostedDir, 'docs', 'subdir'));

  fixture = createFixtureRoot({
    main: { description: 'Main' },
    agents: [
      { name: 'dev', description: 'Dev-Agent' },
      { name: 'permagent', description: 'Permission-Agent', permissions: ['Bash(gradle *)'] },
    ],
    contexts: { main: '# Main-Context\n' },
    paths: [
      {
        name: 'default',
        path: hostedDir,
        hosted: [
          { name: 'notes', path: 'notes.txt', type: 'file' },
          { name: 'docs', path: 'docs', type: 'path' },
        ],
        commands: [
          {
            key: 'pwd',
            command: 'pwd',
            displayName: 'Pwd',
            description: 'Zeigt das Arbeitsverzeichnis',
          },
          { key: 'fail', command: 'exit 5', displayName: 'Fail', description: 'Schlaegt fehl' },
        ],
      },
      { name: 'other', path: hostedDir },
    ],
    contentPath: join(hostedDir, 'content'),
    collection: [{ sourcePath: join(hostedDir, 'notes.txt'), targetName: 'notes-collected' }],
  });
  mock = createMockClaude({ outputChunks: ['erste Zeile\n', 'zweite Zeile\n'], chunkDelayMs: 30 });

  previousRootDir = process.env.CL_ROOT_DIR;
  previousPath = process.env.PATH;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  process.env.PATH = pathWithMock(mock.binDir);

  running = startServer(loadConfig(), 0);
  await running.ready;

  const setupRes = await fetch(`${baseUrl()}/auth/setup`, { method: 'POST' });
  const setupBody = (await setupRes.json()) as { secret: string };
  const confirmRes = await fetch(`${baseUrl()}/auth/setup/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: generateTotp(setupBody.secret) }),
  });
  const confirmBody = (await confirmRes.json()) as { token: string };
  authToken = confirmBody.token;
});

after(async () => {
  await running.close();
  mock.cleanup();
  fixture.cleanup();
  rmSync(hostedDir, { recursive: true, force: true });
  if (previousRootDir === undefined) {
    delete process.env.CL_ROOT_DIR;
  } else {
    process.env.CL_ROOT_DIR = previousRootDir;
  }
  process.env.PATH = previousPath;
});

function baseUrl(): string {
  return `http://localhost:${running.port.toString()}`;
}

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return { Authorization: `Bearer ${authToken}`, ...extra };
}

async function sleep(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

test('GET /health: 200 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/health`);
  assert.equal(res.status, 200);
  const body = (await res.json()) as { status: string; version: string };
  assert.equal(body.status, 'ok');
  assert.equal(typeof body.version, 'string');
});

test('GET /status: 204 ohne Body und ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/status`);
  assert.equal(res.status, 204);
  const body = await res.text();
  assert.equal(body, '');
});

test('POST /: startet den main-Agent, antwortet sofort mit 202 + id', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'mache etwas', path: 'default' }),
  });
  assert.equal(res.status, 202);
  const body = (await res.json()) as { id: string };
  assert.match(body.id, /^[0-9a-f-]{36}$/);
});

test('POST /: 401 ohne gueltigen Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ command: 'mache etwas', path: 'default' }),
  });
  assert.equal(res.status, 401);
});

test('POST /dev: startet den benannten Agent', async () => {
  const res = await fetch(`${baseUrl()}/dev`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', path: 'default' }),
  });
  assert.equal(res.status, 202);
  const body = (await res.json()) as { id: string };
  assert.ok(body.id.length > 0);
});

test('POST /doesnotexist: 404 bei unbekanntem Agent', async () => {
  const res = await fetch(`${baseUrl()}/doesnotexist`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', path: 'default' }),
  });
  assert.equal(res.status, 404);
  const body = (await res.json()) as { error: string };
  assert.match(body.error, /doesnotexist/);
});

test('POST /: 400 bei fehlendem "command"', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ path: 'default' }),
  });
  assert.equal(res.status, 400);
});

test('POST /: 400 bei fehlendem "path"', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x' }),
  });
  assert.equal(res.status, 400);
});

test('POST /: 404 bei unbekanntem "path"', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', path: 'doesnotexist' }),
  });
  assert.equal(res.status, 404);
  const body = (await res.json()) as { error: string };
  assert.match(body.error, /doesnotexist/);
});

test('POST /: 400 bei ungueltigem JSON', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: '{not json',
  });
  assert.equal(res.status, 400);
});

test('GET /state/<id>: 404 bei unbekannter ID', async () => {
  const res = await fetch(`${baseUrl()}/state/unknown-id`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /unbekannte-route: 404', async () => {
  const res = await fetch(`${baseUrl()}/a/b`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /paths: listet nur die Namen aus config.json', async () => {
  const res = await fetch(`${baseUrl()}/paths`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { paths: string[] };
  assert.deepEqual(body.paths, ['default', 'other']);
});

test('GET /paths: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/paths`);
  assert.equal(res.status, 401);
});

test('GET /manifest: liefert Agents und Paths inkl. Commands/Hosted (keine Tasks)', async () => {
  const res = await fetch(`${baseUrl()}/manifest`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as {
    agents: { command: string; description: string }[];
    paths: {
      name: string;
      commands: { key: string }[];
      hosted: { name: string; type: string }[];
    }[];
  };
  assert.deepEqual(body.agents, [
    { command: 'cl', description: 'Main' },
    { command: 'cl dev', description: 'Dev-Agent' },
    { command: 'cl permagent', description: 'Permission-Agent' },
  ]);
  assert.ok(!('tasks' in body));
  assert.equal(body.paths.length, 2);
  const [defaultPath] = body.paths;
  assert.ok(defaultPath);
  assert.equal(defaultPath.name, 'default');
  assert.deepEqual(
    defaultPath.commands.map((command) => command.key),
    ['pwd', 'fail'],
  );
  assert.deepEqual(defaultPath.hosted, [
    { name: 'notes', type: 'file' },
    { name: 'docs', type: 'path' },
  ]);
});

test('GET /manifest: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/manifest`);
  assert.equal(res.status, 401);
});

test('GET /commands/default: listet Commands dieses Pfads, neueste zuerst', async () => {
  const postRes = await fetch(`${baseUrl()}/paths/default/commands/pwd`, {
    method: 'POST',
    headers: authHeaders(),
  });
  const { id } = (await postRes.json()) as { id: string };

  const res = await fetch(`${baseUrl()}/commands/default`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as {
    commands: { id: string; command: string; createdAt: string }[];
  };
  const entry = body.commands.find((command) => command.id === id);
  assert.ok(entry);
  assert.equal(entry.command, 'pwd');
  const createdAtTimestamps = body.commands.map((command) => new Date(command.createdAt).getTime());
  for (let i = 1; i < createdAtTimestamps.length; i++) {
    assert.ok((createdAtTimestamps[i - 1] ?? 0) >= (createdAtTimestamps[i] ?? 0));
  }
});

test('GET /commands/doesnotexist: 404 bei unbekanntem Pfad', async () => {
  const res = await fetch(`${baseUrl()}/commands/doesnotexist`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /commands/default: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/commands/default`);
  assert.equal(res.status, 401);
});

test('GET /files/default: listet die hosted-Namen des Pfads', async () => {
  const res = await fetch(`${baseUrl()}/files/default`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { hosted: string[] };
  assert.deepEqual(body.hosted, ['notes', 'docs']);
});

test('GET /files/doesnotexist: 404 bei unbekanntem Pfad-Namen', async () => {
  const res = await fetch(`${baseUrl()}/files/doesnotexist`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /files/default/notes: hosted-Typ "file" laedt direkt herunter', async () => {
  const res = await fetch(`${baseUrl()}/files/default/notes`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-disposition') ?? '', /notes\.txt/);
  assert.equal(await res.text(), 'hosted-file-inhalt');
});

test('GET /files/default/docs: hosted-Typ "path" listet die Dateien im Verzeichnis', async () => {
  const res = await fetch(`${baseUrl()}/files/default/docs`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { files: string[] };
  assert.deepEqual(body.files.sort(), ['a.txt', 'b.txt']);
});

test('GET /files/default/doesnotexist: 404 bei unbekanntem Hosted-Namen', async () => {
  const res = await fetch(`${baseUrl()}/files/default/doesnotexist`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /files/default/docs/a.txt: laedt die einzelne Datei aus dem Verzeichnis herunter', async () => {
  const res = await fetch(`${baseUrl()}/files/default/docs/a.txt`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-disposition') ?? '', /a\.txt/);
  assert.equal(await res.text(), 'a-inhalt');
});

test('GET /files/default/docs/doesnotexist.txt: 404 bei unbekannter Datei', async () => {
  const res = await fetch(`${baseUrl()}/files/default/docs/doesnotexist.txt`, {
    headers: authHeaders(),
  });
  assert.equal(res.status, 404);
});

test('GET /files/default/notes/anything: 404, da "notes" eine Datei und kein Verzeichnis ist', async () => {
  const res = await fetch(`${baseUrl()}/files/default/notes/anything`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('POST + GET /state/<id>: running -> completed mit vollstaendigem Output', async () => {
  const postRes = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'teste live output', path: 'default' }),
  });
  const { id } = (await postRes.json()) as { id: string };

  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  assert.equal(stateRes.status, 200);
  const state = (await stateRes.json()) as {
    status: string;
    agent: string;
    model: string;
    command: string;
  };
  assert.equal(state.agent, 'main');
  assert.equal(state.model, 'sonnet');
  assert.equal(state.command, 'teste live output');
  assert.ok(state.status === 'running' || state.status === 'completed');

  await sleep(300);

  const finalRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  const final = (await finalRes.json()) as { status: string; output: string; exitCode: number };
  assert.equal(final.status, 'completed');
  assert.equal(final.exitCode, 0);
  assert.match(final.output, /erste Zeile/);
  assert.match(final.output, /zweite Zeile/);
});

function parseSseEvents(text: string): { status: string; output: string }[] {
  return text
    .split('\n\n')
    .filter((chunk) => chunk.startsWith('data: '))
    .map((chunk) => JSON.parse(chunk.slice('data: '.length)) as { status: string; output: string });
}

test('GET /state/<id>/stream: 401 ohne gueltigen Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/state/anything/stream`);
  assert.equal(res.status, 401);
});

test('GET /state/<id>/stream: 404 bei unbekannter ID', async () => {
  const res = await fetch(`${baseUrl()}/state/unknown-id/stream`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /state/<id>/stream: liefert Live-Events bis der Command abgeschlossen ist, dann schliesst die Verbindung', async () => {
  const postRes = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'teste sse', path: 'default' }),
  });
  const { id } = (await postRes.json()) as { id: string };

  const streamRes = await fetch(`${baseUrl()}/state/${id}/stream`, { headers: authHeaders() });
  assert.equal(streamRes.status, 200);
  assert.match(streamRes.headers.get('content-type') ?? '', /text\/event-stream/);

  const events = parseSseEvents(await streamRes.text());
  assert.ok(events.length >= 2, 'erwartet mindestens ein Zwischen- und ein Abschluss-Event');
  const last = events.at(-1);
  assert.ok(last);
  assert.equal(last.status, 'completed');
  assert.match(last.output, /erste Zeile/);
  assert.match(last.output, /zweite Zeile/);
});

test('GET /state/<id>/stream: bereits abgeschlossener Command liefert genau ein Event und schliesst sofort', async () => {
  const postRes = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', path: 'default' }),
  });
  const { id } = (await postRes.json()) as { id: string };
  await sleep(300);

  const streamRes = await fetch(`${baseUrl()}/state/${id}/stream`, { headers: authHeaders() });
  const events = parseSseEvents(await streamRes.text());
  assert.equal(events.length, 1);
  const [only] = events;
  assert.ok(only);
  assert.equal(only.status, 'completed');
});

test('POST /: model im Body ueberschreibt config.json-Default', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', model: 'opus', path: 'default' }),
  });
  const { id } = (await res.json()) as { id: string };
  await sleep(300);
  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  const state = (await stateRes.json()) as { model: string };
  assert.equal(state.model, 'opus');
});

test('POST /permagent: ohne "permissions" im Body werden die Default-Permissions aus config.json verwendet', async () => {
  const res = await fetch(`${baseUrl()}/permagent`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', path: 'default' }),
  });
  const { id } = (await res.json()) as { id: string };
  await sleep(300);
  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  const state = (await stateRes.json()) as { output: string };
  const invokedArgs = extractMockArgs(state.output);
  assert.deepEqual(invokedArgs.slice(-2), ['--allowedTools', 'Bash(gradle *)']);
});

test('POST /permagent: "permissions" im Body ueberschreibt vollstaendig die Default-Permissions aus config.json', async () => {
  const res = await fetch(`${baseUrl()}/permagent`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      command: 'x',
      path: 'default',
      permissions: ['Bash(./gradlew *)', 'Bash(gradlew *)'],
    }),
  });
  const { id } = (await res.json()) as { id: string };
  await sleep(300);
  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  const state = (await stateRes.json()) as { output: string };
  const invokedArgs = extractMockArgs(state.output);
  assert.deepEqual(invokedArgs.slice(-3), [
    '--allowedTools',
    'Bash(./gradlew *)',
    'Bash(gradlew *)',
  ]);
});

test('POST /: 400 wenn "permissions" kein Array von Strings ist', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', path: 'default', permissions: 'Bash(gradle *)' }),
  });
  assert.equal(res.status, 400);
  const body = (await res.json()) as { error: string };
  assert.match(body.error, /permissions/);
});

test('POST /task/mytask: 404 - Tasks sind nie ueber die API aufrufbar', async () => {
  const res = await fetch(`${baseUrl()}/task/mytask`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ path: 'default' }),
  });
  assert.equal(res.status, 404);
});

test('GET /paths/default/commands: listet die konfigurierten Commands', async () => {
  const res = await fetch(`${baseUrl()}/paths/default/commands`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as {
    commands: { key: string; command: string; displayName: string; description: string }[];
  };
  assert.deepEqual(
    body.commands.map((c) => c.key),
    ['pwd', 'fail'],
  );
});

test('GET /paths/doesnotexist/commands: 404 bei unbekanntem Pfad', async () => {
  const res = await fetch(`${baseUrl()}/paths/doesnotexist/commands`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('POST /paths/default/commands/pwd: fuehrt den Command im Pfad-Verzeichnis aus', async () => {
  const res = await fetch(`${baseUrl()}/paths/default/commands/pwd`, {
    method: 'POST',
    headers: authHeaders(),
  });
  assert.equal(res.status, 202);
  const { id } = (await res.json()) as { id: string };

  await sleep(300);

  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  const state = (await stateRes.json()) as { status: string; output: string; exitCode: number };
  assert.equal(state.status, 'completed');
  assert.equal(state.exitCode, 0);
  assert.match(state.output.trim(), new RegExp(hostedDir.replace(/[/\\^$*+?.()|[\]{}]/g, '\\$&')));
});

test('POST /paths/default/commands/fail: nicht-null Exit-Code wird als "failed" gespeichert', async () => {
  const res = await fetch(`${baseUrl()}/paths/default/commands/fail`, {
    method: 'POST',
    headers: authHeaders(),
  });
  const { id } = (await res.json()) as { id: string };

  await sleep(300);

  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  const state = (await stateRes.json()) as { status: string; exitCode: number };
  assert.equal(state.status, 'failed');
  assert.equal(state.exitCode, 5);
});

test('POST /paths/default/commands/doesnotexist: 404 bei unbekanntem Command-Key', async () => {
  const res = await fetch(`${baseUrl()}/paths/default/commands/doesnotexist`, {
    method: 'POST',
    headers: authHeaders(),
  });
  assert.equal(res.status, 404);
});

interface TicketBody {
  id: number;
  pathName: string;
  originalRequest: string;
  summary: string;
  claudeInstruction: string;
  category: string;
  status: string;
  ipAddress: string | null;
  createdAt: string;
  updatedAt: string;
}

async function withTicketAgentOutput<T>(outputChunks: string[], fn: () => Promise<T>): Promise<T> {
  const ticketMock = createMockClaude({ outputChunks, exitCode: 0 });
  const previous = process.env.PATH;
  process.env.PATH = pathWithMock(ticketMock.binDir);
  try {
    return await fn();
  } finally {
    process.env.PATH = previous;
    ticketMock.cleanup();
  }
}

function validTicketAgentOutput(overrides: Partial<Record<string, unknown>> = {}): string {
  return JSON.stringify({
    summary: 'Ticket-Zusammenfassung',
    claudeInstruction: 'Ticket-Claude-Anweisung',
    category: 'Backend',
    ...overrides,
  });
}

async function waitForTicketReady(
  pathName: string,
  id: number,
  timeoutMs = 5000,
): Promise<TicketBody> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const res = await fetch(`${baseUrl()}/tickets/${pathName}/${String(id)}`, {
      headers: authHeaders(),
    });
    const ticket = (await res.json()) as TicketBody;
    if (ticket.status !== 'generating') {
      return ticket;
    }
    if (Date.now() > deadline) {
      throw new Error(`Timeout beim Warten auf die Ticket-Generierung (id ${String(id)}).`);
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}

async function createTicket(pathName = 'default', text = 'ein neues Feature'): Promise<TicketBody> {
  return withTicketAgentOutput([validTicketAgentOutput()], async () => {
    const res = await fetch(`${baseUrl()}/tickets/${pathName}`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ text }),
    });
    assert.equal(res.status, 201);
    const created = (await res.json()) as TicketBody;
    assert.equal(created.status, 'generating');
    return waitForTicketReady(pathName, created.id);
  });
}

test('POST /tickets/default: legt sofort ein leeres Ticket im Status "generating" an', async () => {
  await withTicketAgentOutput([validTicketAgentOutput()], async () => {
    const res = await fetch(`${baseUrl()}/tickets/default`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ text: 'ein neues Feature' }),
    });
    assert.equal(res.status, 201);
    const ticket = (await res.json()) as TicketBody;
    assert.equal(typeof ticket.id, 'number');
    assert.equal(ticket.pathName, 'default');
    assert.equal(ticket.originalRequest, 'ein neues Feature');
    assert.equal(ticket.summary, '');
    assert.equal(ticket.claudeInstruction, '');
    assert.equal(ticket.category, '');
    assert.equal(ticket.status, 'generating');
    assert.equal(typeof ticket.ipAddress, 'string');
    assert.ok(ticket.ipAddress && ticket.ipAddress.length > 0);
    await waitForTicketReady('default', ticket.id);
  });
});

test('POST /tickets/default: Ticket-Agent laeuft im Hintergrund, Ticket wechselt danach auf Status "open"', async () => {
  const ticket = await createTicket('default', 'ein neues Feature');
  assert.equal(ticket.pathName, 'default');
  assert.equal(ticket.originalRequest, 'ein neues Feature');
  assert.equal(ticket.summary, 'Ticket-Zusammenfassung');
  assert.equal(ticket.claudeInstruction, 'Ticket-Claude-Anweisung');
  assert.equal(ticket.category, 'Backend');
  assert.equal(ticket.status, 'open');
});

test('POST /tickets/default: 400 bei fehlendem "text"', async () => {
  const res = await fetch(`${baseUrl()}/tickets/default`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({}),
  });
  assert.equal(res.status, 400);
});

test('POST /tickets/default: 400 bei ungueltigem JSON-Body', async () => {
  const res = await fetch(`${baseUrl()}/tickets/default`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: '{invalid',
  });
  assert.equal(res.status, 400);
});

test('POST /tickets/doesnotexist: 404 bei unbekanntem Pfad', async () => {
  const res = await fetch(`${baseUrl()}/tickets/doesnotexist`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ text: 'x' }),
  });
  assert.equal(res.status, 404);
});

test('POST /tickets/default: Ticket wechselt auf Status "rejected", wenn die Agent-Antwort kein gueltiges Ticket-JSON enthaelt', async () => {
  await withTicketAgentOutput(['kein json hier'], async () => {
    const res = await fetch(`${baseUrl()}/tickets/default`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ text: 'x' }),
    });
    assert.equal(res.status, 201);
    const created = (await res.json()) as TicketBody;
    assert.equal(created.status, 'generating');
    const failed = await waitForTicketReady('default', created.id);
    assert.equal(failed.status, 'rejected');
    assert.ok(failed.summary.includes('Ticket-Agent fehlgeschlagen'));
  });
});

test('POST /tickets/default: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/tickets/default`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'x' }),
  });
  assert.equal(res.status, 401);
});

test('GET /tickets/default: listet alle Tickets des Pfads ohne Status-Filter', async () => {
  const first = await createTicket();
  const second = await createTicket();

  const res = await fetch(`${baseUrl()}/tickets/default`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { tickets: TicketBody[] };
  const ids = body.tickets.map((t) => t.id);
  assert.ok(ids.includes(first.id));
  assert.ok(ids.includes(second.id));
});

test('GET /tickets/default?status=open: filtert nach Status', async () => {
  const openTicket = await createTicket();
  const toClose = await createTicket();
  await fetch(`${baseUrl()}/tickets/default/${String(toClose.id)}`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ status: 'done' }),
  });

  const res = await fetch(`${baseUrl()}/tickets/default?status=open`, { headers: authHeaders() });
  const body = (await res.json()) as { tickets: TicketBody[] };
  const ids = body.tickets.map((t) => t.id);
  assert.ok(ids.includes(openTicket.id));
  assert.ok(!ids.includes(toClose.id));
});

test('GET /tickets: listet Tickets ueber alle Pfade hinweg', async () => {
  const first = await createTicket('default');
  const second = await createTicket('other');

  const res = await fetch(`${baseUrl()}/tickets`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { tickets: TicketBody[] };
  const ids = body.tickets.map((t) => t.id);
  assert.ok(ids.includes(first.id));
  assert.ok(ids.includes(second.id));
});

test('GET /tickets?status=rejected: filtert global nach Status', async () => {
  const rejected = await createTicket();
  await fetch(`${baseUrl()}/tickets/default/${String(rejected.id)}`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ status: 'rejected' }),
  });
  const stillOpen = await createTicket();

  const res = await fetch(`${baseUrl()}/tickets?status=rejected`, { headers: authHeaders() });
  const body = (await res.json()) as { tickets: TicketBody[] };
  const ids = body.tickets.map((t) => t.id);
  assert.ok(ids.includes(rejected.id));
  assert.ok(!ids.includes(stillOpen.id));
});

test('GET /tickets?status=invalid: 400 bei ungueltigem Status', async () => {
  const res = await fetch(`${baseUrl()}/tickets?status=invalid`, { headers: authHeaders() });
  assert.equal(res.status, 400);
});

test('GET /tickets: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/tickets`);
  assert.equal(res.status, 401);
});

test('GET /tickets/default?status=invalid: 400 bei ungueltigem Status', async () => {
  const res = await fetch(`${baseUrl()}/tickets/default?status=invalid`, {
    headers: authHeaders(),
  });
  assert.equal(res.status, 400);
});

test('GET /tickets/doesnotexist: 404 bei unbekanntem Pfad', async () => {
  const res = await fetch(`${baseUrl()}/tickets/doesnotexist`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /tickets/default: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/tickets/default`);
  assert.equal(res.status, 401);
});

test('GET /tickets/default/:id: liefert genau dieses Ticket', async () => {
  const ticket = await createTicket();
  const res = await fetch(`${baseUrl()}/tickets/default/${String(ticket.id)}`, {
    headers: authHeaders(),
  });
  assert.equal(res.status, 200);
  const body = (await res.json()) as TicketBody;
  assert.deepEqual(body, ticket);
});

test('GET /tickets/default/:id: 404 bei unbekannter ID', async () => {
  const res = await fetch(`${baseUrl()}/tickets/default/999999`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /tickets/default/:id: 404 wenn die ID zu einem anderen Pfad gehoert', async () => {
  const otherTicket = await createTicket('other');
  const res = await fetch(`${baseUrl()}/tickets/default/${String(otherTicket.id)}`, {
    headers: authHeaders(),
  });
  assert.equal(res.status, 404);
});

test('PATCH /tickets/default/:id: aktualisiert Felder und liefert das aktualisierte Ticket', async () => {
  const ticket = await createTicket();
  const res = await fetch(`${baseUrl()}/tickets/default/${String(ticket.id)}`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ summary: 'Neue Zusammenfassung', status: 'in progress' }),
  });
  assert.equal(res.status, 200);
  const body = (await res.json()) as TicketBody;
  assert.equal(body.summary, 'Neue Zusammenfassung');
  assert.equal(body.status, 'in progress');
  assert.equal(body.claudeInstruction, ticket.claudeInstruction);
});

test('PATCH /tickets/default/:id: 400 bei ungueltigem Status', async () => {
  const ticket = await createTicket();
  const res = await fetch(`${baseUrl()}/tickets/default/${String(ticket.id)}`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ status: 'invalid' }),
  });
  assert.equal(res.status, 400);
});

test('PATCH /tickets/default/:id: 400 ohne jegliches Feld', async () => {
  const ticket = await createTicket();
  const res = await fetch(`${baseUrl()}/tickets/default/${String(ticket.id)}`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({}),
  });
  assert.equal(res.status, 400);
});

test('PATCH /tickets/default/:id: 404 bei unbekannter ID', async () => {
  const res = await fetch(`${baseUrl()}/tickets/default/999999`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ summary: 'x' }),
  });
  assert.equal(res.status, 404);
});

test('PATCH /tickets/doesnotexist/:id: 404 bei unbekanntem Pfad', async () => {
  const res = await fetch(`${baseUrl()}/tickets/doesnotexist/1`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ summary: 'x' }),
  });
  assert.equal(res.status, 404);
});

test('DELETE /tickets/default/:id: loescht das Ticket, danach 404 beim erneuten Abruf', async () => {
  const ticket = await createTicket();
  const res = await fetch(`${baseUrl()}/tickets/default/${String(ticket.id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  assert.equal(res.status, 200);

  const getRes = await fetch(`${baseUrl()}/tickets/default/${String(ticket.id)}`, {
    headers: authHeaders(),
  });
  assert.equal(getRes.status, 404);
});

test('DELETE /tickets/default/:id: 404 bei unbekannter ID', async () => {
  const res = await fetch(`${baseUrl()}/tickets/default/999999`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  assert.equal(res.status, 404);
});

test('POST /collect: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/collect`, { method: 'POST' });
  assert.equal(res.status, 401);
});

test('POST /collect: sammelt alle konfigurierten Eintraege', async () => {
  const res = await fetch(`${baseUrl()}/collect`, { method: 'POST', headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as {
    results: { targetName: string; fileName: string }[];
    errors: unknown[];
  };
  assert.deepEqual(body.errors, []);
  assert.deepEqual(body.results, [
    { targetName: 'notes-collected', fileName: 'notes-collected.txt', status: 'ok' },
  ]);
});

test('POST /collect: sammelt nur den angegebenen targetName', async () => {
  const res = await fetch(`${baseUrl()}/collect`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ targetName: 'notes-collected' }),
  });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { results: { targetName: string }[] };
  assert.equal(body.results.length, 1);
  assert.equal(body.results[0]?.targetName, 'notes-collected');
});

test('POST /collect: 404 bei unbekanntem targetName', async () => {
  const res = await fetch(`${baseUrl()}/collect`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ targetName: 'doesnotexist' }),
  });
  assert.equal(res.status, 404);
});

test('GET /collections: kein Auth noetig, listet gesammelte Dateien', async () => {
  await fetch(`${baseUrl()}/collect`, { method: 'POST', headers: authHeaders() });

  const res = await fetch(`${baseUrl()}/collections`);
  assert.equal(res.status, 200);
  const body = (await res.json()) as { files: { name: string; timestamp: string }[] };
  const names = body.files.map((f) => f.name);
  assert.ok(names.includes('notes-collected.txt'));
});

test('GET /collections/get/notes-collected.txt: kein Auth noetig, laedt die Datei herunter', async () => {
  await fetch(`${baseUrl()}/collect`, { method: 'POST', headers: authHeaders() });

  const res = await fetch(`${baseUrl()}/collections/get/notes-collected.txt`);
  assert.equal(res.status, 200);
  assert.equal(await res.text(), 'hosted-file-inhalt');
});

test('GET /collections/get/:name: 404 bei unbekanntem Namen', async () => {
  const res = await fetch(`${baseUrl()}/collections/get/doesnotexist.txt`);
  assert.equal(res.status, 404);
});

test('POST /feedback: kein Auth noetig, legt einen Eintrag an', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'Bitte Dark Mode.' }),
  });
  assert.equal(res.status, 201);
  const body = (await res.json()) as { id: number; text: string; section: string | null };
  assert.equal(body.text, 'Bitte Dark Mode.');
  assert.equal(body.section, null);
  assert.equal(typeof body.id, 'number');
});

test('POST /feedback: speichert den optionalen Abschnitt', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'App stuerzt ab.', section: 'periodical-debug' }),
  });
  assert.equal(res.status, 201);
  const body = (await res.json()) as { section: string | null };
  assert.equal(body.section, 'periodical-debug');
});

test('POST /feedback: 400 bei leerem Text', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: '  ' }),
  });
  assert.equal(res.status, 400);
});

test('POST /feedback: 400 wenn "section" kein String ist', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'Text', section: 42 }),
  });
  assert.equal(res.status, 400);
});

async function createFeedback(text = 'Feedback-Text'): Promise<{ id: number; text: string }> {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  return (await res.json()) as { id: number; text: string };
}

test('GET /feedback: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/feedback`);
  assert.equal(res.status, 401);
});

test('GET /feedback: listet Eintraege, authentifiziert', async () => {
  const created = await createFeedback();
  const res = await fetch(`${baseUrl()}/feedback`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { feedback: { id: number }[] };
  assert.ok(body.feedback.some((entry) => entry.id === created.id));
});

test('PATCH /feedback/:id: aktualisiert den Text', async () => {
  const created = await createFeedback('Alt');
  const res = await fetch(`${baseUrl()}/feedback/${String(created.id)}`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ text: 'Neu' }),
  });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { text: string };
  assert.equal(body.text, 'Neu');
});

test('PATCH /feedback/:id: 400 bei fehlendem Text', async () => {
  const created = await createFeedback();
  const res = await fetch(`${baseUrl()}/feedback/${String(created.id)}`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({}),
  });
  assert.equal(res.status, 400);
});

test('PATCH /feedback/:id: 404 bei unbekannter ID', async () => {
  const res = await fetch(`${baseUrl()}/feedback/999999`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ text: 'x' }),
  });
  assert.equal(res.status, 404);
});

test('DELETE /feedback/:id: loescht den Eintrag, danach 404 beim erneuten Zugriff', async () => {
  const created = await createFeedback();
  const res = await fetch(`${baseUrl()}/feedback/${String(created.id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  assert.equal(res.status, 200);

  const patchRes = await fetch(`${baseUrl()}/feedback/${String(created.id)}`, {
    method: 'PATCH',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ text: 'x' }),
  });
  assert.equal(patchRes.status, 404);
});

test('DELETE /feedback/:id: 404 bei unbekannter ID', async () => {
  const res = await fetch(`${baseUrl()}/feedback/999999`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  assert.equal(res.status, 404);
});
