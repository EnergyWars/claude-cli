import assert from 'node:assert/strict';
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
let pendingSecret = '';

before(async () => {
  fixture = createFixtureRoot({
    main: { description: 'Main' },
    contexts: { main: '# Main-Context\n' },
  });
  mock = createMockClaude({ outputChunks: ['ok'] });

  previousRootDir = process.env.CL_ROOT_DIR;
  previousPath = process.env.PATH;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  process.env.PATH = pathWithMock(mock.binDir);

  running = startServer(loadConfig(), 0);
  await running.ready;
});

after(async () => {
  await running.close();
  mock.cleanup();
  fixture.cleanup();
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

test('GET /paths: 401, solange kein Google Authenticator eingerichtet ist', async () => {
  const res = await fetch(`${baseUrl()}/paths`);
  assert.equal(res.status, 401);
});

test('GET /auth/status: {active:false,pending:false}, solange nichts eingerichtet ist', async () => {
  const res = await fetch(`${baseUrl()}/auth/status`);
  assert.equal(res.status, 200);
  const body = (await res.json()) as { active: boolean; pending: boolean };
  assert.deepEqual(body, { active: false, pending: false });
});

test('POST /auth/setup: liefert ein neues Secret + otpauthUrl', async () => {
  const res = await fetch(`${baseUrl()}/auth/setup`, { method: 'POST' });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { secret: string; otpauthUrl: string };
  assert.match(body.secret, /^[A-Z2-7]{32}$/);
  assert.match(body.otpauthUrl, /^otpauth:\/\/totp\//);
  assert.match(body.otpauthUrl, new RegExp(`secret=${body.secret}`));
  pendingSecret = body.secret;
});

test('GET /paths: bleibt 401, solange das Setup nicht bestaetigt ist', async () => {
  const res = await fetch(`${baseUrl()}/paths`);
  assert.equal(res.status, 401);
});

test('GET /auth/status: {active:false,pending:true} bei ausstehendem, unbestaetigtem Setup', async () => {
  const res = await fetch(`${baseUrl()}/auth/status`);
  assert.equal(res.status, 200);
  const body = (await res.json()) as { active: boolean; pending: boolean };
  assert.deepEqual(body, { active: false, pending: true });
});

test('POST /auth/setup/confirm: 400 bei fehlendem "code"', async () => {
  const res = await fetch(`${baseUrl()}/auth/setup/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({}),
  });
  assert.equal(res.status, 400);
});

test('POST /auth/setup/confirm: 401 bei falschem Code', async () => {
  const res = await fetch(`${baseUrl()}/auth/setup/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: '000000' }),
  });
  assert.equal(res.status, 401);
});

test('POST /auth/setup: kann waehrend eines ausstehenden Setups erneut aufgerufen werden (neues Secret)', async () => {
  const res = await fetch(`${baseUrl()}/auth/setup`, { method: 'POST' });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { secret: string };
  pendingSecret = body.secret;
});

let issuedToken = '';

test('POST /auth/setup/confirm: 200 bei korrektem Code, aktiviert den Authenticator und liefert ein JWT', async () => {
  const code = generateTotp(pendingSecret);
  const res = await fetch(`${baseUrl()}/auth/setup/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { message: string; token: string; expiresAt: string };
  assert.equal(body.message, 'Google Authenticator aktiviert.');
  assert.match(body.token, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  assert.equal(Number.isNaN(Date.parse(body.expiresAt)), false);
  issuedToken = body.token;
});

test('GET /auth/status: {active:true,pending:false} nach erfolgreicher Bestaetigung', async () => {
  const res = await fetch(`${baseUrl()}/auth/status`);
  assert.equal(res.status, 200);
  const body = (await res.json()) as { active: boolean; pending: boolean };
  assert.deepEqual(body, { active: true, pending: false });
});

test('POST /auth/setup: 409, wenn bereits ein Authenticator aktiv ist', async () => {
  const res = await fetch(`${baseUrl()}/auth/setup`, { method: 'POST' });
  assert.equal(res.status, 409);
});

test('POST /auth/setup/confirm: 409, wenn bereits ein Authenticator aktiv ist', async () => {
  const res = await fetch(`${baseUrl()}/auth/setup/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: generateTotp(pendingSecret) }),
  });
  assert.equal(res.status, 409);
});

test('GET /paths: 401 ohne Authorization-Header', async () => {
  const res = await fetch(`${baseUrl()}/paths`);
  assert.equal(res.status, 401);
});

test('GET /paths: 401 bei manipuliertem/ungueltigem Token', async () => {
  const res = await fetch(`${baseUrl()}/paths`, {
    headers: { Authorization: 'Bearer nicht.ein.jwt' },
  });
  assert.equal(res.status, 401);
});

test('GET /paths: 401 bei fehlendem "Bearer "-Praefix', async () => {
  const res = await fetch(`${baseUrl()}/paths`, { headers: { Authorization: issuedToken } });
  assert.equal(res.status, 401);
});

test('GET /paths: 200 bei gueltigem JWT (und dasselbe Token kann fuer mehrere Requests wiederverwendet werden)', async () => {
  const first = await fetch(`${baseUrl()}/paths`, {
    headers: { Authorization: `Bearer ${issuedToken}` },
  });
  assert.equal(first.status, 200);
  const second = await fetch(`${baseUrl()}/paths`, {
    headers: { Authorization: `Bearer ${issuedToken}` },
  });
  assert.equal(second.status, 200);
});

test('POST /auth/login: 401 bei falschem Code', async () => {
  const res = await fetch(`${baseUrl()}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: '000000' }),
  });
  assert.equal(res.status, 401);
});

test('POST /auth/login: 200 bei korrektem Code, liefert ein frisches JWT', async () => {
  const code = generateTotp(pendingSecret);
  const res = await fetch(`${baseUrl()}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
  assert.equal(res.status, 200);
  const body = (await res.json()) as { token: string; expiresAt: string };
  assert.match(body.token, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  assert.equal(Number.isNaN(Date.parse(body.expiresAt)), false);

  const authed = await fetch(`${baseUrl()}/paths`, {
    headers: { Authorization: `Bearer ${body.token}` },
  });
  assert.equal(authed.status, 200);
});

test('GET /auth/setup: liefert HTML mit Hinweis "bereits aktiv" statt QR, wenn schon eingerichtet', async () => {
  const res = await fetch(`${baseUrl()}/auth/setup`);
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type') ?? '', /text\/html/);
  const body = await res.text();
  assert.match(body, /bereits ein Google Authenticator aktiv/);
  assert.doesNotMatch(body, /<svg/);
});

test('GET /auth/setup: zeigt QR-Code (SVG) + Secret, solange noch kein Authenticator aktiv ist', async () => {
  const fixtureForSetup = createFixtureRoot({
    main: { description: 'Main' },
    contexts: { main: '# Main-Context\n' },
  });
  const previousRoot = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixtureForSetup.rootDir;
  const setupServer = startServer(loadConfig(), 0);
  await setupServer.ready;
  try {
    const res = await fetch(`http://localhost:${setupServer.port.toString()}/auth/setup`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') ?? '', /text\/html/);
    const body = await res.text();
    assert.match(body, /<svg/);
    assert.match(body, /[A-Z2-7]{32}/);
  } finally {
    await setupServer.close();
    fixtureForSetup.cleanup();
    if (previousRoot === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previousRoot;
    }
  }
});
