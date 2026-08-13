import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';

import { loadConfig } from './config.js';
import { startServer, type RunningServer } from './server.js';
import { generateTotp } from './totp.js';
import { createFixtureRoot, type Fixture } from './test-support/fixture-config.js';
import { createMockClaude, pathWithMock, type MockClaude } from './test-support/mock-claude.js';

let fixture: Fixture;
let mock: MockClaude;
let running: RunningServer;
let previousRootDir: string | undefined;
let previousPath: string | undefined;
let hostedDir: string;
let totpSecret: string;

before(async () => {
  hostedDir = mkdtempSync(join(tmpdir(), 'cl-hosted-'));
  writeFileSync(join(hostedDir, 'notes.txt'), 'hosted-file-inhalt');
  mkdirSync(join(hostedDir, 'docs'));
  writeFileSync(join(hostedDir, 'docs', 'a.txt'), 'a-inhalt');
  writeFileSync(join(hostedDir, 'docs', 'b.txt'), 'b-inhalt');
  mkdirSync(join(hostedDir, 'docs', 'subdir'));

  fixture = createFixtureRoot({
    main: { description: 'Main' },
    agents: [{ name: 'dev', description: 'Dev-Agent' }],
    contexts: { main: '# Main-Context\n' },
    tasks: [{ name: 'mytask', contexts: ['main'], tasks: ['mytask'] }],
    taskFiles: { mytask: '# Mytask-Inhalt\n' },
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
    ],
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
  totpSecret = setupBody.secret;
  await fetch(`${baseUrl()}/auth/setup/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: generateTotp(totpSecret) }),
  });
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
  return { 'X-TOTP-Code': generateTotp(totpSecret), ...extra };
}

async function sleep(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

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

test('POST /: 401 ohne gueltigen "X-TOTP-Code"-Header', async () => {
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
  assert.deepEqual(body.paths, ['default']);
});

test('GET /paths: 401 ohne "X-TOTP-Code"-Header', async () => {
  const res = await fetch(`${baseUrl()}/paths`);
  assert.equal(res.status, 401);
});

test('GET /manifest: liefert Agents, Tasks und Paths inkl. Commands/Hosted', async () => {
  const res = await fetch(`${baseUrl()}/manifest`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as {
    agents: { command: string; description: string }[];
    tasks: { name: string; model: string }[];
    paths: {
      name: string;
      commands: { key: string }[];
      hosted: { name: string; type: string }[];
    }[];
  };
  assert.deepEqual(body.agents, [
    { command: 'cl', description: 'Main' },
    { command: 'cl dev', description: 'Dev-Agent' },
  ]);
  assert.deepEqual(body.tasks, [{ name: 'mytask', model: 'sonnet' }]);
  assert.equal(body.paths.length, 1);
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

test('GET /manifest: 401 ohne "X-TOTP-Code"-Header', async () => {
  const res = await fetch(`${baseUrl()}/manifest`);
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

test('POST /task/mytask: startet den Task, antwortet sofort mit 202 + id, wird wie ein Agent abgefragt', async () => {
  const res = await fetch(`${baseUrl()}/task/mytask`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ path: 'default' }),
  });
  assert.equal(res.status, 202);
  const { id } = (await res.json()) as { id: string };
  assert.match(id, /^[0-9a-f-]{36}$/);

  await sleep(300);

  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  assert.equal(stateRes.status, 200);
  const state = (await stateRes.json()) as {
    status: string;
    agent: string;
    model: string;
    output: string;
    exitCode: number;
  };
  assert.equal(state.agent, 'task:mytask');
  assert.equal(state.status, 'completed');
  assert.equal(state.exitCode, 0);
  assert.match(state.output, /erste Zeile/);
});

test('POST /task/mytask: model im Body ueberschreibt config.json-Default', async () => {
  const res = await fetch(`${baseUrl()}/task/mytask`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ path: 'default', model: 'opus' }),
  });
  const { id } = (await res.json()) as { id: string };
  await sleep(300);
  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  const state = (await stateRes.json()) as { model: string };
  assert.equal(state.model, 'opus');
});

test('POST /task/doesnotexist: 404 bei unbekanntem Task', async () => {
  const res = await fetch(`${baseUrl()}/task/doesnotexist`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ path: 'default' }),
  });
  assert.equal(res.status, 404);
  const body = (await res.json()) as { error: string };
  assert.match(body.error, /doesnotexist/);
});

test('POST /task/mytask: 400 bei fehlendem "path"', async () => {
  const res = await fetch(`${baseUrl()}/task/mytask`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({}),
  });
  assert.equal(res.status, 400);
});

test('POST /task/mytask: 404 bei unbekanntem "path"', async () => {
  const res = await fetch(`${baseUrl()}/task/mytask`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ path: 'doesnotexist' }),
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
