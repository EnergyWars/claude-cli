import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import { createEmptyBinDir, createMockClaude, pathWithMock } from './test-support/mock-claude.js';
import {
  isProcessAlive,
  isSessionActive,
  isSessionRunning,
  killRemoteSession,
  listRemoteSessions,
  parseBackgroundSessionId,
  sessionActivity,
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
    await startRemoteSession('/tmp', { name: 'mein-name' });
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
    await startRemoteSession('/tmp', { name: 'mein-name', prompt: '/goal Tu etwas' });
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

test('startRemoteSession: uebergibt ein Modell als "--model <model>" vor dem Prompt', async () => {
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
    await startRemoteSession('/tmp', { name: 'mein-name', prompt: '/goal Tu etwas', model: 'sonnet' });
    assert.deepEqual(readFirstLoggedArgs(logFile), [
      '--bg',
      '--remote-control=mein-name',
      '--model',
      'sonnet',
      '--',
      '/goal Tu etwas',
    ]);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
    rmSync(logDir, { recursive: true, force: true });
  }
});

test('startRemoteSession: laesst "--model" bei leerem Modell weg', async () => {
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
    await startRemoteSession('/tmp', { model: '  ' });
    assert.deepEqual(readFirstLoggedArgs(logFile), ['--bg', '--remote-control']);
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

test('sessionActivity: waitingFor, status "waiting" oder state "blocked" bedeuten Warten auf Antwort', () => {
  assert.equal(sessionActivity({ ...BASE_SESSION, waitingFor: 'permission' }), 'waiting');
  assert.equal(sessionActivity({ ...BASE_SESSION, status: 'waiting' }), 'waiting');
  assert.equal(sessionActivity({ ...BASE_SESSION, state: 'blocked' }), 'waiting');
});

test('sessionActivity: laufende Zustaende bedeuten Arbeiten', () => {
  for (const state of ['working', 'starting', 'queued', 'running']) {
    assert.equal(sessionActivity({ ...BASE_SESSION, state }), 'working', state);
  }
});

test('sessionActivity: Warten hat Vorrang vor Arbeiten', () => {
  assert.equal(sessionActivity({ ...BASE_SESSION, state: 'working', waitingFor: 'input' }), 'waiting');
});

test('sessionActivity: ohne Hinweis oder im Leerlauf gilt die Session als fertig', () => {
  assert.equal(sessionActivity({ ...BASE_SESSION }), 'idle');
  assert.equal(sessionActivity({ ...BASE_SESSION, status: 'idle', state: 'idle' }), 'idle');
});

test('isSessionRunning: mit PID entscheidet, ob der Prozess lebt', () => {
  assert.equal(isSessionRunning({ ...BASE_SESSION, pid: 5 }, () => true), true);
  assert.equal(isSessionRunning({ ...BASE_SESSION, pid: 5 }, () => false), false);
});

test('isSessionRunning: ohne PID zaehlen nur beendete Status als nicht laufend', () => {
  assert.equal(isSessionRunning({ ...BASE_SESSION }), true);
  assert.equal(isSessionRunning({ ...BASE_SESSION, status: 'idle' }), true);
  for (const status of ['done', 'failed', 'stopped']) {
    assert.equal(isSessionRunning({ ...BASE_SESSION, status }), false, status);
  }
});

test('killRemoteSession: sendet SIGTERM an die PID einer Session ohne Kurz-ID', async () => {
  const calls: [number, string][] = [];
  await killRemoteSession({ ...BASE_SESSION, kind: 'interactive', pid: 4242 }, (pid, signal) => {
    calls.push([pid, signal]);
  });
  assert.deepEqual(calls, [[4242, 'SIGTERM']]);
});

test('killRemoteSession: ignoriert ESRCH (Prozess existiert nicht mehr)', async () => {
  await killRemoteSession({ ...BASE_SESSION, pid: 4242 }, () => {
    throw Object.assign(new Error('gone'), { code: 'ESRCH' });
  });
});

test('killRemoteSession: reicht andere Fehler weiter', async () => {
  await assert.rejects(
    () =>
      killRemoteSession({ ...BASE_SESSION, pid: 4242 }, () => {
        throw Object.assign(new Error('denied'), { code: 'EPERM' });
      }),
    /denied/,
  );
});

test('killRemoteSession: verweigert Sessions ohne Kurz-ID und ohne gueltige PID', async () => {
  const neverCalled = (): void => {
    assert.fail('kill darf nicht aufgerufen werden');
  };
  await assert.rejects(() => killRemoteSession({ ...BASE_SESSION }, neverCalled), /kann nicht beendet werden/);
  await assert.rejects(() => killRemoteSession({ ...BASE_SESSION, pid: 1 }, neverCalled), /kann nicht beendet werden/);
  await assert.rejects(() => killRemoteSession({ ...BASE_SESSION, pid: 0 }, neverCalled), /kann nicht beendet werden/);
  await assert.rejects(() => killRemoteSession({ ...BASE_SESSION, pid: 1.5 }, neverCalled), /kann nicht beendet werden/);
});

test('killRemoteSession: Sessions mit Kurz-ID werden ueber "claude stop <id>" beendet', async () => {
  const logDir = mkdtempSync(join(tmpdir(), 'cl-remote-session-kill-log-'));
  const logFile = join(logDir, 'args.log');
  const mock = createMockClaude({ rawOutput: '', exitCode: 0, logFile });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await killRemoteSession({ ...BASE_SESSION, id: '1771997d', pid: 99 }, () => {
      assert.fail('PID-Kill darf nicht verwendet werden');
    });
    assert.deepEqual(readFirstLoggedArgs(logFile), ['stop', '1771997d']);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
    rmSync(logDir, { recursive: true, force: true });
  }
});

test('killRemoteSession: wirft, wenn "claude stop" fehlschlaegt', async () => {
  const mock = createMockClaude({ rawOutput: 'nope', exitCode: 1 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await assert.rejects(
      () => killRemoteSession({ ...BASE_SESSION, id: '1771997d' }),
      /ist fehlgeschlagen \(Exit-Code 1\)/,
    );
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});
