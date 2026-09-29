import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import { createEmptyBinDir, createMockClaude, pathWithMock } from './test-support/mock-claude.js';
import {
  isProcessAlive,
  isSessionActive,
  listRemoteSessions,
  parseBackgroundSessionId,
  startRemoteSession,
} from './remote-session.js';

function readFirstLoggedArgs(logFile: string): string[] {
  return JSON.parse(readFileSync(logFile, 'utf8').trim().split('\n')[0] ?? '[]') as string[];
}

test('parseBackgroundSessionId: liest die ID aus der "--bg"-Ausgabe', () => {
  const output = [
    'backgrounded · 1771997d (idle — send a prompt to start)',
    '  claude agents             list sessions',
    '  claude attach 1771997d    open in this terminal',
  ].join('\n');
  assert.equal(parseBackgroundSessionId(output), '1771997d');
});

test('parseBackgroundSessionId: findet die ID auch mit vorangestelltem Text', () => {
  const output =
    'Starting background service…\nbackgrounded · abc123f9 (idle — send a prompt to start)';
  assert.equal(parseBackgroundSessionId(output), 'abc123f9');
});

test('parseBackgroundSessionId: wirft ohne passendes Muster', () => {
  assert.throws(() => parseBackgroundSessionId('irgendwas anderes'), /Konnte die Session-ID/);
});

test('startRemoteSession: spawnt "claude --bg --remote-control" und liefert die ID', async () => {
  const mock = createMockClaude({
    outputChunks: ['backgrounded · abc123f9 (idle — send a prompt to start)\n'],
    exitCode: 0,
  });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const session = await startRemoteSession('/tmp');
    assert.equal(session.id, 'abc123f9');
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('startRemoteSession: haengt einen uebergebenen Namen als "--remote-control=<name>" an', async () => {
  const logDir = mkdtempSync(join(tmpdir(), 'cl-remote-session-log-'));
  const logFile = join(logDir, 'args.log');
  const mock = createMockClaude({
    outputChunks: ['backgrounded · xyz98765 (idle — send a prompt to start)\n'],
    exitCode: 0,
    logFile,
  });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await startRemoteSession('/tmp', 'mein-name');
    assert.deepEqual(readFirstLoggedArgs(logFile), ['--bg', '--remote-control=mein-name']);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
    rmSync(logDir, { recursive: true, force: true });
  }
});

test('startRemoteSession: haengt einen Prompt nach "--" an', async () => {
  const logDir = mkdtempSync(join(tmpdir(), 'cl-remote-session-log-'));
  const logFile = join(logDir, 'args.log');
  const mock = createMockClaude({
    outputChunks: ['backgrounded · xyz98765 (idle — send a prompt to start)\n'],
    exitCode: 0,
    logFile,
  });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await startRemoteSession('/tmp', 'mein-name', '/goal Tu etwas');
    assert.deepEqual(readFirstLoggedArgs(logFile), [
      '--bg',
      '--remote-control=mein-name',
      '--',
      '/goal Tu etwas',
    ]);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
    rmSync(logDir, { recursive: true, force: true });
  }
});

test('startRemoteSession: wirft bei nicht-null Exit-Code', async () => {
  const mock = createMockClaude({ outputChunks: ['kaputt'], exitCode: 1 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await assert.rejects(() => startRemoteSession('/tmp'), /ist fehlgeschlagen \(Exit-Code 1\)/);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('startRemoteSession: rejected wenn "claude" nicht im PATH gefunden wird', async () => {
  const empty = createEmptyBinDir();
  const previousPath = process.env.PATH;
  process.env.PATH = empty.binDir;
  try {
    await assert.rejects(() => startRemoteSession('/tmp'));
  } finally {
    process.env.PATH = previousPath;
    empty.cleanup();
  }
});

const SAMPLE_SESSIONS = [
  {
    pid: 123,
    cwd: '/home/user/project',
    kind: 'interactive',
    startedAt: 1_700_000_000_000,
    sessionId: 'a1b2c3',
    name: 'project-a1',
    status: 'idle',
  },
  {
    pid: 456,
    id: '1771997d',
    cwd: '/home/user/project',
    kind: 'background',
    startedAt: 1_700_000_100_000,
    sessionId: '1771997d-e1ab-4ed7-9d04-79696f05ec1d',
    name: '1771997d',
    status: 'idle',
    state: 'blocked',
  },
];

test('listRemoteSessions: spawnt "claude agents --json" und parsed die Sessions', async () => {
  const mock = createMockClaude({ rawOutput: JSON.stringify(SAMPLE_SESSIONS), exitCode: 0 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const sessions = await listRemoteSessions();
    assert.deepEqual(sessions, SAMPLE_SESSIONS);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('listRemoteSessions: filtert Eintraege, die nicht dem Schema entsprechen', async () => {
  const mock = createMockClaude({
    rawOutput: JSON.stringify([...SAMPLE_SESSIONS, { pid: 'not-a-number' }]),
    exitCode: 0,
  });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const sessions = await listRemoteSessions();
    assert.equal(sessions.length, 2);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('listRemoteSessions: akzeptiert Background-Sessions ohne pid', async () => {
  const withoutPid = {
    id: 'ad2b3a5d',
    cwd: '/home/user/project',
    kind: 'background',
    startedAt: 1_700_000_200_000,
    sessionId: 'ad2b3a5d-9993-46bf-b9cb-cfa16b966c8e',
    name: 'goal:path:folder/G01.md',
    state: 'working',
  };
  const mock = createMockClaude({ rawOutput: JSON.stringify([withoutPid]), exitCode: 0 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const sessions = await listRemoteSessions();
    assert.deepEqual(sessions, [withoutPid]);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('listRemoteSessions: haengt "--cwd <cwd>" an, wenn ein cwd uebergeben wird', async () => {
  const logDir = mkdtempSync(join(tmpdir(), 'cl-remote-session-cwd-log-'));
  const logFile = join(logDir, 'args.log');
  const mock = createMockClaude({ rawOutput: '[]', exitCode: 0, logFile });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await listRemoteSessions('/tmp/project');
    assert.deepEqual(readFirstLoggedArgs(logFile), ['agents', '--json', '--cwd', '/tmp/project']);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
    rmSync(logDir, { recursive: true, force: true });
  }
});

test('listRemoteSessions: wirft bei nicht-null Exit-Code', async () => {
  const mock = createMockClaude({ outputChunks: ['kaputt'], exitCode: 1 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await assert.rejects(() => listRemoteSessions(), /ist fehlgeschlagen \(Exit-Code 1\)/);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('listRemoteSessions: wirft bei ungueltigem JSON', async () => {
  const mock = createMockClaude({ rawOutput: 'kein json', exitCode: 0 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await assert.rejects(() => listRemoteSessions(), /kein gueltiges JSON/);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('listRemoteSessions: wirft, wenn das JSON kein Array ist', async () => {
  const mock = createMockClaude({ rawOutput: JSON.stringify({ foo: 'bar' }), exitCode: 0 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await assert.rejects(() => listRemoteSessions(), /kein Array/);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

const BASE_SESSION = {
  cwd: '/p',
  kind: 'background',
  startedAt: 1,
  sessionId: 's1',
  name: 'n',
};

test('isSessionActive: nur "working" und andere laufende Zustaende zaehlen als aktiv', () => {
  for (const state of ['working', 'starting', 'queued', 'running']) {
    assert.equal(isSessionActive({ ...BASE_SESSION, state }), true, state);
  }
  for (const state of ['blocked', 'done', 'failed', 'idle', 'stopped']) {
    assert.equal(isSessionActive({ ...BASE_SESSION, state }), false, state);
  }
});

test('isSessionActive: ohne state entscheidet status, ohne beides gilt die Session als aktiv', () => {
  assert.equal(isSessionActive({ ...BASE_SESSION, status: 'idle' }), false);
  assert.equal(isSessionActive({ ...BASE_SESSION, status: 'busy' }), true);
  assert.equal(isSessionActive({ ...BASE_SESSION }), true);
});

test('isSessionActive: tote PID ist nie aktiv, lebende PID folgt dem Zustand', () => {
  assert.equal(isSessionActive({ ...BASE_SESSION, pid: 1, state: 'working' }, () => false), false);
  assert.equal(isSessionActive({ ...BASE_SESSION, pid: 1, state: 'working' }, () => true), true);
  assert.equal(isSessionActive({ ...BASE_SESSION, pid: 1, state: 'blocked' }, () => true), false);
});

test('isProcessAlive: eigener Prozess lebt, unbelegte PID nicht', () => {
  assert.equal(isProcessAlive(process.pid), true);
  assert.equal(isProcessAlive(2_147_483_646), false);
});
