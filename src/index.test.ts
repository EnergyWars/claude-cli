import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { createFixtureRoot, type Fixture } from './test-support/fixture-config.js';
import {
  createMockClaude,
  extractMockArgs,
  pathWithMock,
  type MockClaude,
} from './test-support/mock-claude.js';
import { runCli } from './test-support/run-cli.js';

let fixture: Fixture;
let mock: MockClaude;

before(() => {
  fixture = createFixtureRoot({
    main: { description: 'Main-Agent-Desc', model: 'sonnet' },
    agents: [{ name: 'dev', description: 'Dev-Agent-Desc', model: 'sonnet' }],
    contexts: { main: '# Main-Context\n' },
    tasks: [{ name: 'mytask', contexts: ['main'], tasks: ['mytask'], model: 'sonnet' }],
    taskFiles: { mytask: '# Mytask-Content\n' },
  });
  mock = createMockClaude({ outputChunks: ['ok'], exitCode: 0 });
});

after(() => {
  mock.cleanup();
  fixture.cleanup();
});

function baseEnv(): NodeJS.ProcessEnv {
  return {
    CL_ROOT_DIR: fixture.rootDir,
    PATH: pathWithMock(mock.binDir),
  };
}

test('cl --help: listet Agents und Model-Override dynamisch auf, Exit 0', async () => {
  const result = await runCli(['--help'], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /Agents \(aus config\.json\)/);
  assert.match(result.stdout, /cl dev\s+Dev-Agent-Desc/);
  assert.match(result.stdout, /Model-Override/);
  assert.match(result.stdout, /haiku\|h/);
});

test('cl --version: gibt eine Semver-Version aus, Exit 0', async () => {
  const result = await runCli(['--version'], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout.trim(), /^\d+\.\d+\.\d+$/);
});

test('cl (ohne Argumente): startet main-Agent interaktiv (kein --print)', async () => {
  const result = await runCli([], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  const invokedArgs = extractMockArgs(result.stdout);
  assert.deepEqual(invokedArgs, [
    '--model',
    'sonnet',
    '--append-system-prompt',
    '# Main-Context\n',
    '--permission-mode',
    'acceptEdits',
  ]);
});

test('cl <model> <agent> -h "<prompt>": headless mit Model-Override und Agent', async () => {
  const result = await runCli(['opus', 'dev', '-h', 'mache irgendwas cooles'], {
    env: baseEnv(),
  });
  assert.equal(result.exitCode, 0);
  const invokedArgs = extractMockArgs(result.stdout);
  assert.deepEqual(invokedArgs, [
    '--model',
    'opus',
    '--append-system-prompt',
    '# Main-Context\n',
    '--permission-mode',
    'acceptEdits',
    '--print',
    'mache irgendwas cooles',
  ]);
});

test('cl <agent> -h (ohne Wert): fragt Prompt interaktiv ueber stdin ab', async () => {
  const result = await runCli(['dev', '-h'], {
    env: baseEnv(),
    input: 'gebe mir eine Zusammenfassung\n',
  });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /Prompt:/);
  const invokedArgs = extractMockArgs(result.stdout);
  assert.deepEqual(invokedArgs.slice(-2), ['--print', 'gebe mir eine Zusammenfassung']);
});

test('cl doesnotexist: unbekannter Agent bricht mit Fehler und Exit != 0 ab', async () => {
  const result = await runCli(['doesnotexist'], { env: baseEnv() });
  assert.notEqual(result.exitCode, 0);
  assert.match(result.stderr, /doesnotexist/);
});

test('Startup-Crash: Agent-Name kollidiert mit reserviertem Model-Command', async () => {
  const collidingFixture = createFixtureRoot({ agents: [{ name: 'opus' }] });
  try {
    const result = await runCli(['--version'], {
      env: { CL_ROOT_DIR: collidingFixture.rootDir, PATH: pathWithMock(mock.binDir) },
    });
    assert.notEqual(result.exitCode, 0);
    assert.match(result.stderr, /reservierten Commands/);
  } finally {
    collidingFixture.cleanup();
  }
});

test('cl server: startet, druckt Endpunkte, beantwortet Requests, beendet sich auf SIGTERM', async () => {
  const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
  const tsxBin = join(projectRoot, 'node_modules', '.bin', 'tsx');
  const entryPoint = join(projectRoot, 'src', 'index.ts');

  const child = spawn(tsxBin, [entryPoint, 'server', '--port', '0'], {
    env: { ...process.env, ...baseEnv() },
  });

  let stdout = '';
  const readyPromise = new Promise<void>((resolve) => {
    child.stdout.on('data', (chunk: Buffer) => {
      stdout += chunk.toString('utf8');
      if (stdout.includes('Endpunkte:')) {
        resolve();
      }
    });
  });

  try {
    await readyPromise;
    const portMatch = /http:\/\/localhost:(\d+)/.exec(stdout);
    assert.ok(portMatch);
    const [, port] = portMatch;
    assert.ok(port);

    const res = await fetch(`http://localhost:${port}/state/unknown`);
    assert.equal(res.status, 404);
  } finally {
    child.kill('SIGTERM');
    await new Promise((resolve) => {
      child.on('exit', resolve);
    });
  }
});

test('cl server --paths-file: ueberschreibt die paths aus config.json vollstaendig', async () => {
  const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
  const tsxBin = join(projectRoot, 'node_modules', '.bin', 'tsx');
  const entryPoint = join(projectRoot, 'src', 'index.ts');

  const pathsFileDir = mkdtempSync(join(tmpdir(), 'cl-paths-file-'));
  const pathsFile = join(pathsFileDir, 'paths.json');
  writeFileSync(pathsFile, JSON.stringify({ paths: [{ name: 'override', path: pathsFileDir }] }));

  const child = spawn(tsxBin, [entryPoint, 'server', '--port', '0', '--paths-file', pathsFile], {
    env: { ...process.env, ...baseEnv() },
  });

  let stdout = '';
  const readyPromise = new Promise<void>((resolve) => {
    child.stdout.on('data', (chunk: Buffer) => {
      stdout += chunk.toString('utf8');
      if (stdout.includes('Endpunkte:')) {
        resolve();
      }
    });
  });

  try {
    await readyPromise;
    const portMatch = /http:\/\/localhost:(\d+)/.exec(stdout);
    assert.ok(portMatch);
    const [, port] = portMatch;
    assert.ok(port);

    const res = await fetch(`http://localhost:${port}/paths`);
    assert.equal(res.status, 200);
    const body = (await res.json()) as { paths: string[] };
    assert.deepEqual(body.paths, ['override']);
  } finally {
    child.kill('SIGTERM');
    await new Promise((resolve) => {
      child.on('exit', resolve);
    });
    rmSync(pathsFileDir, { recursive: true, force: true });
  }
});

test('cl task mytask: fuehrt den Task headless aus, Output wird live geloggt', async () => {
  const result = await runCli(['task', 'mytask'], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /ok/);
  const invokedArgs = extractMockArgs(result.stdout);
  assert.deepEqual(invokedArgs, [
    '--model',
    'sonnet',
    '--append-system-prompt',
    '# Main-Context\n',
    '--permission-mode',
    'acceptEdits',
    '--print',
    '# Mytask-Content\n',
  ]);
});

test('cl task doesnotexist: unbekannter Task bricht mit Fehler und Exit != 0 ab', async () => {
  const result = await runCli(['task', 'doesnotexist'], { env: baseEnv() });
  assert.notEqual(result.exitCode, 0);
  assert.match(result.stderr, /doesnotexist/);
});

test('cl task mytask -d: kehrt sofort zurueck, ohne auf den Task zu warten', async () => {
  const slowMock = createMockClaude({ outputChunks: ['langsam'], chunkDelayMs: 1000 });
  const start = Date.now();
  try {
    const result = await runCli(['task', 'mytask', '-d'], {
      env: { CL_ROOT_DIR: fixture.rootDir, PATH: pathWithMock(slowMock.binDir) },
    });
    assert.equal(result.exitCode, 0);
    assert.ok(Date.now() - start < 900);
  } finally {
    slowMock.cleanup();
  }
});
