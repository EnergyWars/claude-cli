import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
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
let hookedDir: string;
let pagedDir: string;
let authToken: string;

before(async () => {
  hostedDir = mkdtempSync(join(tmpdir(), 'cl-hosted-'));
  hookedDir = mkdtempSync(join(tmpdir(), 'cl-hooked-'));
  pagedDir = mkdtempSync(join(tmpdir(), 'cl-paged-'));
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
      {
        name: 'hooked',
        path: hookedDir,
        hooks: { onLastAgentFinish: 'touch hook-fired.txt' },
      },
      { name: 'paged', path: pagedDir },
    ],
    contentPath: join(hostedDir, 'content'),
    collection: [
      { sourcePath: join(hostedDir, 'notes.txt'), targetName: 'notes-collected', path: 'default' },
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
  rmSync(hookedDir, { recursive: true, force: true });
  rmSync(pagedDir, { recursive: true, force: true });
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

test('POST /state/<id>/stop: beendet einen laufenden Command und setzt Status auf "stopped"', async () => {
  const slowMock = createMockClaude({
    outputChunks: ['a\n', 'b\n', 'c\n', 'd\n', 'e\n'],
    chunkDelayMs: 200,
  });
  const previousMockPath = process.env.PATH;
  process.env.PATH = pathWithMock(slowMock.binDir);
  try {
    const postRes = await fetch(`${baseUrl()}/`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ command: 'lang laufender Befehl', path: 'default' }),
    });
    const { id } = (await postRes.json()) as { id: string };

    await sleep(100);
    const runningRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
    const runningState = (await runningRes.json()) as { status: string };
    assert.equal(runningState.status, 'running');

    const stopRes = await fetch(`${baseUrl()}/state/${id}/stop`, {
      method: 'POST',
      headers: authHeaders(),
    });
    assert.equal(stopRes.status, 202);
    const stopBody = (await stopRes.json()) as { id: string };
    assert.equal(stopBody.id, id);

    await sleep(400);
    const finalRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
    const finalState = (await finalRes.json()) as { status: string };
    assert.equal(finalState.status, 'stopped');
  } finally {
    process.env.PATH = previousMockPath;
    slowMock.cleanup();
  }
});

test('POST /state/<id>/stop: 404 bei unbekannter ID', async () => {
  const res = await fetch(`${baseUrl()}/state/unknown-id/stop`, {
    method: 'POST',
    headers: authHeaders(),
  });
  assert.equal(res.status, 404);
});

test('POST /state/<id>/stop: 409 wenn der Command bereits abgeschlossen ist', async () => {
  const postRes = await fetch(`${baseUrl()}/paths/default/commands/pwd`, {
    method: 'POST',
    headers: authHeaders(),
  });
  const { id } = (await postRes.json()) as { id: string };
  await sleep(300);

  const res = await fetch(`${baseUrl()}/state/${id}/stop`, {
    method: 'POST',
    headers: authHeaders(),
  });
  assert.equal(res.status, 409);
});

test('POST /state/<id>/stop: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/state/unknown-id/stop`, { method: 'POST' });
  assert.equal(res.status, 401);
});

test('GET /unbekannte-route: 404', async () => {
  const res = await fetch(`${baseUrl()}/a/b`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /paths: listet nur die Namen aus config.json', async () => {
  const res = await fetch(`${baseUrl()}/paths`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { paths: string[] };
  assert.deepEqual(body.paths, ['default', 'other', 'hooked', 'paged']);
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
      hosted: { name: string; type: string; timestamp: string | null }[];
    }[];
  };
  assert.deepEqual(body.agents, [
    { command: 'cl', description: 'Main' },
    { command: 'cl dev', description: 'Dev-Agent' },
    { command: 'cl permagent', description: 'Permission-Agent' },
  ]);
  assert.ok(!('tasks' in body));
  assert.equal(body.paths.length, 4);
  const [defaultPath] = body.paths;
  assert.ok(defaultPath);
  assert.equal(defaultPath.name, 'default');
  assert.deepEqual(
    defaultPath.commands.map((command) => command.key),
    ['pwd', 'fail'],
  );
  const [notes, docs] = defaultPath.hosted;
  assert.ok(notes);
  assert.ok(docs);
  assert.equal(notes.name, 'notes');
  assert.equal(notes.type, 'file');
  assert.match(notes.timestamp ?? '', /^\d{4}-\d{2}-\d{2}T/);
  assert.deepEqual(docs, { name: 'docs', type: 'path', timestamp: null });
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

test('GET /commands/:pathName: paginiert per ?limit=/?offset=, neueste zuerst, mit total/hasMore', async () => {
  const total = 7;
  const ids: string[] = [];
  for (let i = 0; i < total; i++) {
    const res = await fetch(`${baseUrl()}/`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ command: `pagination-test-${i.toString()}`, path: 'paged' }),
    });
    const { id } = (await res.json()) as { id: string };
    ids.push(id);
  }
  const expectedOrder = [...ids].reverse(); // neueste zuerst

  const defaultRes = await fetch(`${baseUrl()}/commands/paged`, { headers: authHeaders() });
  assert.equal(defaultRes.status, 200);
  const defaultBody = (await defaultRes.json()) as {
    commands: { id: string }[];
    total: number;
    limit: number;
    offset: number;
    hasMore: boolean;
  };
  assert.equal(defaultBody.limit, 5, 'Default-Seitengroesse ohne ?limit= ist 5');
  assert.equal(defaultBody.offset, 0);
  assert.equal(defaultBody.total, total);
  assert.equal(defaultBody.hasMore, true);
  assert.deepEqual(
    defaultBody.commands.map((c) => c.id),
    expectedOrder.slice(0, 5),
  );

  const secondPageRes = await fetch(`${baseUrl()}/commands/paged?limit=5&offset=5`, {
    headers: authHeaders(),
  });
  assert.equal(secondPageRes.status, 200);
  const secondPageBody = (await secondPageRes.json()) as {
    commands: { id: string }[];
    total: number;
    hasMore: boolean;
  };
  assert.equal(secondPageBody.total, total);
  assert.equal(secondPageBody.hasMore, false);
  assert.deepEqual(
    secondPageBody.commands.map((c) => c.id),
    expectedOrder.slice(5, 7),
  );
});

test('GET /commands/default?limit=abc: 400 bei ungueltigem limit', async () => {
  const res = await fetch(`${baseUrl()}/commands/default?limit=abc`, { headers: authHeaders() });
  assert.equal(res.status, 400);
});

test('GET /commands/default?limit=0: 400 bei nicht-positivem limit', async () => {
  const res = await fetch(`${baseUrl()}/commands/default?limit=0`, { headers: authHeaders() });
  assert.equal(res.status, 400);
});

test('GET /commands/default?offset=-1: 400 bei negativem offset', async () => {
  const res = await fetch(`${baseUrl()}/commands/default?offset=-1`, { headers: authHeaders() });
  assert.equal(res.status, 400);
});

test('GET /stats/default: Default-Zeitfenster ist 24h, lastDebugBuildAt/lastReleaseBuildAt sind zunaechst null', async () => {
  const res = await fetch(`${baseUrl()}/stats/default`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as {
    windowHours: number;
    lastDebugBuildAt: string | null;
    lastReleaseBuildAt: string | null;
  };
  assert.equal(body.windowHours, 24);
  assert.equal(body.lastDebugBuildAt, null);
  assert.equal(body.lastReleaseBuildAt, null);
});

test('GET /stats/default: lastDebugBuildAt ist der Zeitstempel der APK, ueber alle Pfad-Namen mit gleichem Verzeichnis geteilt', async () => {
  const debugApkDir = join(hostedDir, 'build', 'outputs', 'apk', 'debug');
  mkdirSync(debugApkDir, { recursive: true });
  const debugApkPath = join(debugApkDir, 'app-debug.apk');
  writeFileSync(debugApkPath, 'FAKE');
  const debugMtime = new Date('2026-02-01T08:00:00.000Z');
  utimesSync(debugApkPath, debugMtime, debugMtime);

  const defaultRes = await fetch(`${baseUrl()}/stats/default`, { headers: authHeaders() });
  const defaultBody = (await defaultRes.json()) as {
    lastDebugBuildAt: string | null;
    lastReleaseBuildAt: string | null;
  };
  assert.equal(defaultBody.lastDebugBuildAt, debugMtime.toISOString());
  assert.equal(defaultBody.lastReleaseBuildAt, null);

  const otherRes = await fetch(`${baseUrl()}/stats/other`, { headers: authHeaders() });
  const otherBody = (await otherRes.json()) as { lastDebugBuildAt: string | null };
  assert.equal(otherBody.lastDebugBuildAt, debugMtime.toISOString());
});

test('GET /stats/default: zaehlt laufende/kuerzlich gelaufene Agents, ohne Pfad-Commands', async () => {
  const beforeRes = await fetch(`${baseUrl()}/stats/default`, { headers: authHeaders() });
  const before = (await beforeRes.json()) as { runningAgents: number; agentsInWindow: number };

  const pathCommandRes = await fetch(`${baseUrl()}/paths/default/commands/pwd`, {
    method: 'POST',
    headers: authHeaders(),
  });
  await pathCommandRes.json();

  const afterPathCommandRes = await fetch(`${baseUrl()}/stats/default`, { headers: authHeaders() });
  const afterPathCommand = (await afterPathCommandRes.json()) as { agentsInWindow: number };
  assert.equal(afterPathCommand.agentsInWindow, before.agentsInWindow);

  const postRes = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'stats-agent-lauf', path: 'default' }),
  });
  await postRes.json();

  const runningRes = await fetch(`${baseUrl()}/stats/default`, { headers: authHeaders() });
  const running = (await runningRes.json()) as { runningAgents: number; agentsInWindow: number };
  assert.equal(running.runningAgents, before.runningAgents + 1);
  assert.equal(running.agentsInWindow, before.agentsInWindow + 1);

  await sleep(300);

  const completedRes = await fetch(`${baseUrl()}/stats/default`, { headers: authHeaders() });
  const completed = (await completedRes.json()) as {
    runningAgents: number;
    agentsInWindow: number;
  };
  assert.equal(completed.runningAgents, before.runningAgents);
  assert.equal(completed.agentsInWindow, before.agentsInWindow + 1);
});

test('GET /stats/default?hours=0.001: schmales Zeitfenster liefert 0 fuer laengst abgeschlossene Laeufe', async () => {
  const res = await fetch(`${baseUrl()}/stats/default?hours=0.0000001`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { agentsInWindow: number; windowHours: number };
  assert.equal(body.agentsInWindow, 0);
  assert.equal(body.windowHours, 0.0000001);
});

test('GET /stats/default?hours=abc: 400 bei ungueltigem Zeitfenster', async () => {
  const res = await fetch(`${baseUrl()}/stats/default?hours=abc`, { headers: authHeaders() });
  assert.equal(res.status, 400);
});

test('GET /stats/default?hours=-1: 400 bei nicht-positivem Zeitfenster', async () => {
  const res = await fetch(`${baseUrl()}/stats/default?hours=-1`, { headers: authHeaders() });
  assert.equal(res.status, 400);
});

test('GET /stats/doesnotexist: 404 bei unbekanntem Pfad', async () => {
  const res = await fetch(`${baseUrl()}/stats/doesnotexist`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /stats/default: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/stats/default`);
  assert.equal(res.status, 401);
});

test('GET /usage: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/usage`);
  assert.equal(res.status, 401);
});

function usageJsonOutput(result: string): string {
  return JSON.stringify({ type: 'result', result });
}

const SAMPLE_USAGE_RESULT = [
  'Current session: 42% used · resets Aug 27, 5:40pm (Europe/Berlin)',
  'Current week (all models): 10% used · resets Aug 29, 9pm (Europe/Berlin)',
].join('\n');

test('GET /usage: liefert die aus "claude --print /usage" geparsten Limits', async () => {
  const usageMock = createMockClaude({
    outputChunks: [usageJsonOutput(SAMPLE_USAGE_RESULT)],
    exitCode: 0,
  });
  const previous = process.env.PATH;
  process.env.PATH = pathWithMock(usageMock.binDir);
  const server = startServer(loadConfig(), 0);
  try {
    await server.ready;
    const res = await fetch(`http://localhost:${server.port.toString()}/usage`, {
      headers: authHeaders(),
    });
    assert.equal(res.status, 200);
    const body = (await res.json()) as {
      limits: { label: string; percentUsed: number; resetsAt: string }[];
    };
    assert.deepEqual(body.limits, [
      { label: 'Current session', percentUsed: 42, resetsAt: 'Aug 27, 5:40pm (Europe/Berlin)' },
      {
        label: 'Current week (all models)',
        percentUsed: 10,
        resetsAt: 'Aug 29, 9pm (Europe/Berlin)',
      },
    ]);
  } finally {
    await server.close();
    process.env.PATH = previous;
    usageMock.cleanup();
  }
});

test('GET /usage: gecacht - ein zweiter Aufruf innerhalb der TTL liefert weiterhin den ersten Stand', async () => {
  const firstMock = createMockClaude({
    outputChunks: [usageJsonOutput('Current session: 5% used · resets X')],
    exitCode: 0,
  });
  const previous = process.env.PATH;
  process.env.PATH = pathWithMock(firstMock.binDir);
  const server = startServer(loadConfig(), 0);
  try {
    await server.ready;
    const url = `http://localhost:${server.port.toString()}/usage`;
    const firstRes = await fetch(url, { headers: authHeaders() });
    const firstBody = (await firstRes.json()) as { limits: { percentUsed: number }[] };
    assert.equal(firstBody.limits[0]?.percentUsed, 5);

    const secondMock = createMockClaude({
      outputChunks: [usageJsonOutput('Current session: 99% used · resets Y')],
      exitCode: 0,
    });
    process.env.PATH = pathWithMock(secondMock.binDir);
    try {
      const secondRes = await fetch(url, { headers: authHeaders() });
      assert.equal(secondRes.status, 200);
      const secondBody = (await secondRes.json()) as { limits: { percentUsed: number }[] };
      assert.equal(secondBody.limits[0]?.percentUsed, 5);
    } finally {
      secondMock.cleanup();
    }
  } finally {
    await server.close();
    process.env.PATH = previous;
    firstMock.cleanup();
  }
});

test('GET /usage: 500 wenn "claude --print /usage" fehlschlaegt', async () => {
  const failMock = createMockClaude({ outputChunks: ['kaputt'], exitCode: 1 });
  const previous = process.env.PATH;
  process.env.PATH = pathWithMock(failMock.binDir);
  const server = startServer(loadConfig(), 0);
  try {
    await server.ready;
    const res = await fetch(`http://localhost:${server.port.toString()}/usage`, {
      headers: authHeaders(),
    });
    assert.equal(res.status, 500);
  } finally {
    await server.close();
    process.env.PATH = previous;
    failMock.cleanup();
  }
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

test('GET /files/default/docs: hosted-Typ "path" listet die Dateien im Verzeichnis inkl. Timestamp', async () => {
  const res = await fetch(`${baseUrl()}/files/default/docs`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { files: { name: string; timestamp: string }[] };
  const sorted = [...body.files].sort((a, b) => a.name.localeCompare(b.name));
  assert.deepEqual(
    sorted.map((f) => f.name),
    ['a.txt', 'b.txt'],
  );
  for (const file of sorted) {
    assert.match(file.timestamp, /^\d{4}-\d{2}-\d{2}T/);
  }
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

test('POST /: fuehrt den onLastAgentFinish-Hook des Pfads aus, sobald der letzte Agent fertig ist', async () => {
  const res = await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', path: 'hooked' }),
  });
  const { id } = (await res.json()) as { id: string };

  await sleep(300);

  const stateRes = await fetch(`${baseUrl()}/state/${id}`, { headers: authHeaders() });
  const state = (await stateRes.json()) as { status: string };
  assert.equal(state.status, 'completed');
  assert.ok(existsSync(join(hookedDir, 'hook-fired.txt')));
});

test('POST /: der onLastAgentFinish-Hook erscheint als eigener Eintrag im Verlauf (GET /commands/:pathName)', async () => {
  await fetch(`${baseUrl()}/`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ command: 'x', path: 'hooked' }),
  });

  await sleep(300);

  const res = await fetch(`${baseUrl()}/commands/hooked`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as {
    commands: { agent: string; command: string; status: string }[];
  };
  const hookEntry = body.commands.find((command) => command.agent === 'hook:hooked:onLastAgentFinish');
  assert.ok(hookEntry);
  assert.equal(hookEntry.command, 'touch hook-fired.txt');
  assert.equal(hookEntry.status, 'completed');
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

test('POST /collect/:pathName: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/collect/default`, { method: 'POST' });
  assert.equal(res.status, 401);
});

test('POST /collect/:pathName: sammelt nur die Eintraege dieses Pfads', async () => {
  const res = await fetch(`${baseUrl()}/collect/default`, {
    method: 'POST',
    headers: authHeaders(),
  });
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

test('POST /collect/:pathName: leeres Ergebnis fuer Pfad ohne Collection-Eintraege', async () => {
  const res = await fetch(`${baseUrl()}/collect/other`, { method: 'POST', headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { results: unknown[]; errors: unknown[] };
  assert.deepEqual(body, { results: [], errors: [] });
});

test('POST /collect/:pathName: 404 bei unbekanntem Pfad', async () => {
  const res = await fetch(`${baseUrl()}/collect/doesnotexist`, {
    method: 'POST',
    headers: authHeaders(),
  });
  assert.equal(res.status, 404);
});

test('POST /feedback: kein Auth noetig, legt einen Eintrag an', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'Bitte Dark Mode.' }),
  });
  assert.equal(res.status, 201);
  const body = (await res.json()) as {
    id: number;
    text: string;
    section: string | null;
    context: string | null;
  };
  assert.equal(body.text, 'Bitte Dark Mode.');
  assert.equal(body.section, null);
  assert.equal(body.context, null);
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

test('POST /feedback: speichert den optionalen Kontext', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      text: 'App stuerzt ab.',
      context: 'periodical-debug.apk (2026-08-26T10:00:00.000Z)',
    }),
  });
  assert.equal(res.status, 201);
  const body = (await res.json()) as { context: string | null };
  assert.equal(body.context, 'periodical-debug.apk (2026-08-26T10:00:00.000Z)');
});

test('POST /feedback: leerer Kontext wird zu null', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'Text', context: '   ' }),
  });
  assert.equal(res.status, 201);
  const body = (await res.json()) as { context: string | null };
  assert.equal(body.context, null);
});

test('POST /feedback: 400 wenn "context" kein String ist', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'Text', context: 42 }),
  });
  assert.equal(res.status, 400);
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

test('POST /feedback: section, die zu einem Collection-Eintrag passt, setzt den zugehoerigen Pfad', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'App stuerzt ab.', section: 'notes-collected.txt' }),
  });
  assert.equal(res.status, 201);
  const body = (await res.json()) as { path: string | null };
  assert.equal(body.path, 'default');
});

test('POST /feedback: section ohne passenden Collection-Eintrag laesst den Pfad leer', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'App stuerzt ab.', section: 'unbekannte-section' }),
  });
  assert.equal(res.status, 201);
  const body = (await res.json()) as { path: string | null };
  assert.equal(body.path, null);
});

test('POST /feedback: ohne section bleibt der Pfad leer', async () => {
  const res = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'Text' }),
  });
  assert.equal(res.status, 201);
  const body = (await res.json()) as { path: string | null };
  assert.equal(body.path, null);
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

test('GET /feedback/:pathName: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/feedback/default`);
  assert.equal(res.status, 401);
});

test('GET /feedback/:pathName: 404 bei unbekanntem Pfad', async () => {
  const res = await fetch(`${baseUrl()}/feedback/doesnotexist`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('GET /feedback/:pathName: listet nur Eintraege dieses Pfads', async () => {
  const withPathRes = await fetch(`${baseUrl()}/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: 'Betrifft default.', section: 'notes-collected.txt' }),
  });
  const withPath = (await withPathRes.json()) as { id: number };
  const withoutPath = await createFeedback('Ohne Pfad-Zuordnung.');

  const res = await fetch(`${baseUrl()}/feedback/default`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { feedback: { id: number }[] };
  const ids = body.feedback.map((entry) => entry.id);
  assert.ok(ids.includes(withPath.id));
  assert.ok(!ids.includes(withoutPath.id));
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

test('GET /config: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/config`);
  assert.equal(res.status, 401);
});

test('GET /config: liefert die aktuell aktive Config (aus der lokalen config.json bootstrapped)', async () => {
  const res = await fetch(`${baseUrl()}/config`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { main: { description: string } };
  assert.equal(body.main.description, 'Main');
});

test('GET /config/pointer: zeigt initial auf die aus der lokalen Datei importierte Version 1', async () => {
  const res = await fetch(`${baseUrl()}/config/pointer`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { versionId: number | null };
  assert.equal(body.versionId, 1);
});

test('GET /config/versions: listet mindestens die Bootstrap-Version 1', async () => {
  const res = await fetch(`${baseUrl()}/config/versions`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { versions: { id: number; createdAt: string }[] };
  assert.ok(body.versions.some((version) => version.id === 1));
});

test('GET /config/versions/:id: liefert den vollen Config-Inhalt dieser Version', async () => {
  const res = await fetch(`${baseUrl()}/config/versions/1`, { headers: authHeaders() });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { id: number; config: { main: { description: string } } };
  assert.equal(body.id, 1);
  assert.equal(body.config.main.description, 'Main');
});

test('GET /config/versions/:id: 404 bei unbekannter Version', async () => {
  const res = await fetch(`${baseUrl()}/config/versions/999999`, { headers: authHeaders() });
  assert.equal(res.status, 404);
});

test('PUT /config: 400 bei ungueltiger Config', async () => {
  const res = await fetch(`${baseUrl()}/config`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ nope: true }),
  });
  assert.equal(res.status, 400);
});

test('PUT /config: neue Version wird gespeichert und ist sofort (ohne Neustart) aktiv', async () => {
  const currentRes = await fetch(`${baseUrl()}/config`, { headers: authHeaders() });
  const current = (await currentRes.json()) as Record<string, unknown> & {
    main: { description: string };
  };

  const putRes = await fetch(`${baseUrl()}/config`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ ...current, main: { ...current.main, description: 'Geaendert' } }),
  });
  assert.equal(putRes.status, 200);
  const putBody = (await putRes.json()) as { versionId: number; warning?: string };
  assert.equal(putBody.warning, undefined);
  const newVersionId = putBody.versionId;
  assert.ok(newVersionId > 1);

  try {
    const configRes = await fetch(`${baseUrl()}/config`, { headers: authHeaders() });
    const configBody = (await configRes.json()) as { main: { description: string } };
    assert.equal(configBody.main.description, 'Geaendert');

    const manifestRes = await fetch(`${baseUrl()}/manifest`, { headers: authHeaders() });
    const manifestBody = (await manifestRes.json()) as { agents: { description: string }[] };
    assert.equal(manifestBody.agents[0]?.description, 'Geaendert');

    const pointerRes = await fetch(`${baseUrl()}/config/pointer`, { headers: authHeaders() });
    const pointerBody = (await pointerRes.json()) as { versionId: number | null };
    assert.equal(pointerBody.versionId, newVersionId);
  } finally {
    await fetch(`${baseUrl()}/config/pointer`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ versionId: 1 }),
    });
  }
});

test('PUT /config: Wechsel von databaseDirectory liefert eine Warnung (Reload greift erst nach Neustart)', async () => {
  const currentRes = await fetch(`${baseUrl()}/config`, { headers: authHeaders() });
  const current = (await currentRes.json()) as Record<string, unknown> & {
    databaseDirectory: string;
  };

  const putRes = await fetch(`${baseUrl()}/config`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ ...current, databaseDirectory: `${current.databaseDirectory}-andere` }),
  });
  assert.equal(putRes.status, 200);
  const putBody = (await putRes.json()) as { warning?: string };
  assert.match(putBody.warning ?? '', /Server-Neustart/);

  await fetch(`${baseUrl()}/config/pointer`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ versionId: 1 }),
  });
});

test('PUT /config/pointer: {embedded:true} aktiviert die fest reinkompilierte Version, Rollback per versionId', async () => {
  try {
    const embeddedRes = await fetch(`${baseUrl()}/config/pointer`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ embedded: true }),
    });
    assert.equal(embeddedRes.status, 200);
    const embeddedBody = (await embeddedRes.json()) as { versionId: number | null };
    assert.equal(embeddedBody.versionId, null);

    const pointerRes = await fetch(`${baseUrl()}/config/pointer`, { headers: authHeaders() });
    assert.equal(((await pointerRes.json()) as { versionId: number | null }).versionId, null);
  } finally {
    const rollbackRes = await fetch(`${baseUrl()}/config/pointer`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ versionId: 1 }),
    });
    assert.equal(rollbackRes.status, 200);
  }

  const pointerRes = await fetch(`${baseUrl()}/config/pointer`, { headers: authHeaders() });
  assert.equal(((await pointerRes.json()) as { versionId: number | null }).versionId, 1);
});

test('PUT /config/pointer: 404 bei unbekannter Version, 400 bei ungueltigem Body', async () => {
  const notFoundRes = await fetch(`${baseUrl()}/config/pointer`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ versionId: 999999 }),
  });
  assert.equal(notFoundRes.status, 404);

  const badRes = await fetch(`${baseUrl()}/config/pointer`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ nope: true }),
  });
  assert.equal(badRes.status, 400);
});

test('PUT /config/pointer: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/config/pointer`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ versionId: 1 }),
  });
  assert.equal(res.status, 401);
});
