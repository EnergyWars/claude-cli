import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join } from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';

import { generateTotp } from './totp.js';
import { createMockAdb, pathWithMockAdb } from './test-support/mock-adb.js';
import { createFixtureRoot, type Fixture } from './test-support/fixture-config.js';
import { writeFakeGradlew } from './test-support/mock-gradlew.js';
import {
  createMockClaude,
  extractMockArgs,
  pathWithMock,
  type MockClaude,
} from './test-support/mock-claude.js';
import { runCli } from './test-support/run-cli.js';

async function setupAndConfirmTotp(baseUrl: string): Promise<string> {
  const setupRes = await fetch(`${baseUrl}/auth/setup`, { method: 'POST' });
  const { secret } = (await setupRes.json()) as { secret: string };
  await fetch(`${baseUrl}/auth/setup/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code: generateTotp(secret) }),
  });
  return secret;
}

let fixture: Fixture;
let mock: MockClaude;

before(() => {
  fixture = createFixtureRoot({
    main: { description: 'Main-Agent-Desc', model: 'sonnet' },
    agents: [{ name: 'dev', description: 'Dev-Agent-Desc', model: 'sonnet' }],
    contexts: { main: '# Main-Context\n' },
    tasks: [
      {
        name: 'mytask',
        description: 'Mytask-Desc',
        contexts: ['main'],
        model: 'sonnet',
        startCommand: 'mach den mytask-kram',
      },
    ],
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
    'auto',
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
    'auto',
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

  const serverFixture = createFixtureRoot({
    main: { description: 'Main-Agent-Desc', model: 'sonnet' },
    contexts: { main: '# Main-Context\n' },
  });

  const child = spawn(tsxBin, [entryPoint, 'server', '--port', '0'], {
    env: { ...process.env, CL_ROOT_DIR: serverFixture.rootDir, PATH: pathWithMock(mock.binDir) },
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
    const url = `http://localhost:${port}`;

    const secret = await setupAndConfirmTotp(url);
    const res = await fetch(`${url}/state/unknown`, {
      headers: { 'X-TOTP-Code': generateTotp(secret) },
    });
    assert.equal(res.status, 404);
  } finally {
    child.kill('SIGTERM');
    await new Promise((resolve) => {
      child.on('exit', resolve);
    });
    serverFixture.cleanup();
  }
});

test('cl server --paths-file: ueberschreibt die paths aus config.json vollstaendig', async () => {
  const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
  const tsxBin = join(projectRoot, 'node_modules', '.bin', 'tsx');
  const entryPoint = join(projectRoot, 'src', 'index.ts');

  const serverFixture = createFixtureRoot({
    main: { description: 'Main-Agent-Desc', model: 'sonnet' },
    contexts: { main: '# Main-Context\n' },
  });
  const pathsFileDir = mkdtempSync(join(tmpdir(), 'cl-paths-file-'));
  const pathsFile = join(pathsFileDir, 'paths.json');
  writeFileSync(pathsFile, JSON.stringify({ paths: [{ name: 'override', path: pathsFileDir }] }));

  const child = spawn(tsxBin, [entryPoint, 'server', '--port', '0', '--paths-file', pathsFile], {
    env: { ...process.env, CL_ROOT_DIR: serverFixture.rootDir, PATH: pathWithMock(mock.binDir) },
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
    const url = `http://localhost:${port}`;

    const secret = await setupAndConfirmTotp(url);
    const res = await fetch(`${url}/paths`, { headers: { 'X-TOTP-Code': generateTotp(secret) } });
    assert.equal(res.status, 200);
    const body = (await res.json()) as { paths: string[] };
    assert.deepEqual(body.paths, ['override']);
  } finally {
    child.kill('SIGTERM');
    await new Promise((resolve) => {
      child.on('exit', resolve);
    });
    rmSync(pathsFileDir, { recursive: true, force: true });
    serverFixture.cleanup();
  }
});

test('cl totp remove: ohne vorherigen Setup meldet, dass kein Authenticator eingerichtet war', async () => {
  const totpFixture = createFixtureRoot();
  try {
    const result = await runCli(['totp', 'remove'], {
      env: { CL_ROOT_DIR: totpFixture.rootDir, PATH: pathWithMock(mock.binDir) },
    });
    assert.equal(result.exitCode, 0);
    assert.match(result.stdout, /kein Google Authenticator eingerichtet/);
  } finally {
    totpFixture.cleanup();
  }
});

test('cl totp remove: entfernt einen aktiven Authenticator; Server-Endpunkte sind danach wieder gesperrt', async () => {
  const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
  const tsxBin = join(projectRoot, 'node_modules', '.bin', 'tsx');
  const entryPoint = join(projectRoot, 'src', 'index.ts');

  const totpFixture = createFixtureRoot({
    main: { description: 'Main-Agent-Desc', model: 'sonnet' },
    contexts: { main: '# Main-Context\n' },
  });
  const env = { CL_ROOT_DIR: totpFixture.rootDir, PATH: pathWithMock(mock.binDir) };

  const child = spawn(tsxBin, [entryPoint, 'server', '--port', '0'], {
    env: { ...process.env, ...env },
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
    const url = `http://localhost:${port}`;

    const secret = await setupAndConfirmTotp(url);
    const authorized = await fetch(`${url}/paths`, {
      headers: { 'X-TOTP-Code': generateTotp(secret) },
    });
    assert.equal(authorized.status, 200);
  } finally {
    child.kill('SIGTERM');
    await new Promise((resolve) => {
      child.on('exit', resolve);
    });
  }

  const removeResult = await runCli(['totp', 'remove'], { env });
  assert.equal(removeResult.exitCode, 0);
  assert.match(removeResult.stdout, /Google Authenticator entfernt/);

  const secondChild = spawn(tsxBin, [entryPoint, 'server', '--port', '0'], {
    env: { ...process.env, ...env },
  });
  let secondStdout = '';
  const secondReadyPromise = new Promise<void>((resolve) => {
    secondChild.stdout.on('data', (chunk: Buffer) => {
      secondStdout += chunk.toString('utf8');
      if (secondStdout.includes('Endpunkte:')) {
        resolve();
      }
    });
  });

  try {
    await secondReadyPromise;
    const portMatch = /http:\/\/localhost:(\d+)/.exec(secondStdout);
    assert.ok(portMatch);
    const [, port] = portMatch;
    assert.ok(port);

    const res = await fetch(`http://localhost:${port}/paths`);
    assert.equal(res.status, 401);
  } finally {
    secondChild.kill('SIGTERM');
    await new Promise((resolve) => {
      secondChild.on('exit', resolve);
    });
    totpFixture.cleanup();
  }
});

function validTicketAgentOutput(overrides: Partial<Record<string, unknown>> = {}): string {
  return JSON.stringify({
    title: 'CLI-Ticket-Titel',
    description: 'CLI-Ticket-Beschreibung',
    task: 'CLI-Ticket-Aufgabe',
    ...overrides,
  });
}

interface CliTicket {
  id: number;
  pathName: string;
  title: string;
  description: string;
  task: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

test('cl ticket from: legt per Ticket-Agent ein Ticket an und gibt es als JSON aus', async () => {
  const ticketFixture = createFixtureRoot();
  const ticketMock = createMockClaude({ outputChunks: [validTicketAgentOutput()], exitCode: 0 });
  try {
    const result = await runCli(['ticket', 'from', 'default', 'ein neues Feature'], {
      env: { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(ticketMock.binDir) },
    });
    assert.equal(result.exitCode, 0);
    const ticket = JSON.parse(result.stdout) as CliTicket;
    assert.equal(ticket.pathName, 'default');
    assert.equal(ticket.title, 'CLI-Ticket-Titel');
    assert.equal(ticket.description, 'CLI-Ticket-Beschreibung');
    assert.equal(ticket.task, 'CLI-Ticket-Aufgabe');
    assert.equal(ticket.status, 'open');
  } finally {
    ticketMock.cleanup();
    ticketFixture.cleanup();
  }
});

test('cl ticket from: unbekannter Pfad bricht mit Fehler und Exit != 0 ab', async () => {
  const ticketFixture = createFixtureRoot();
  try {
    const result = await runCli(['ticket', 'from', 'doesnotexist', 'text'], {
      env: { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(mock.binDir) },
    });
    assert.notEqual(result.exitCode, 0);
    assert.match(result.stderr, /doesnotexist/);
  } finally {
    ticketFixture.cleanup();
  }
});

test('cl ticket from: nicht-parsebare Agent-Antwort bricht mit Fehler und Exit != 0 ab', async () => {
  const ticketFixture = createFixtureRoot();
  const ticketMock = createMockClaude({ outputChunks: ['kein json'], exitCode: 0 });
  try {
    const result = await runCli(['ticket', 'from', 'default', 'text'], {
      env: { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(ticketMock.binDir) },
    });
    assert.notEqual(result.exitCode, 0);
    assert.match(result.stderr, /kein gueltiges Ticket-JSON/);
  } finally {
    ticketMock.cleanup();
    ticketFixture.cleanup();
  }
});

test('cl ticket get (ohne ID): listet nur offene Tickets, cl ticket get <id>: liefert genau dieses Ticket', async () => {
  const ticketFixture = createFixtureRoot();
  const ticketMock = createMockClaude({ outputChunks: [validTicketAgentOutput()], exitCode: 0 });
  const env = { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(ticketMock.binDir) };
  try {
    const created = await runCli(['ticket', 'from', 'default', 'text'], { env });
    const ticket = JSON.parse(created.stdout) as CliTicket;

    const closedResult = await runCli(['ticket', 'from', 'default', 'text'], { env });
    const closedTicket = JSON.parse(closedResult.stdout) as CliTicket;
    await runCli(['ticket', 'update', 'default', String(closedTicket.id), '--status', 'closed'], {
      env,
    });

    const listResult = await runCli(['ticket', 'get', 'default'], { env });
    assert.equal(listResult.exitCode, 0);
    const openTickets = JSON.parse(listResult.stdout) as CliTicket[];
    const ids = openTickets.map((t) => t.id);
    assert.ok(ids.includes(ticket.id));
    assert.ok(!ids.includes(closedTicket.id));

    const getResult = await runCli(['ticket', 'get', 'default', String(ticket.id)], { env });
    assert.equal(getResult.exitCode, 0);
    const fetched = JSON.parse(getResult.stdout) as CliTicket;
    assert.deepEqual(fetched, ticket);
  } finally {
    ticketMock.cleanup();
    ticketFixture.cleanup();
  }
});

test('cl ticket get <path> <id>: unbekannte ID bricht mit Fehler und Exit != 0 ab', async () => {
  const ticketFixture = createFixtureRoot();
  try {
    const result = await runCli(['ticket', 'get', 'default', '999999'], {
      env: { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(mock.binDir) },
    });
    assert.notEqual(result.exitCode, 0);
    assert.match(result.stderr, /999999/);
  } finally {
    ticketFixture.cleanup();
  }
});

test('cl ticket list --status: filtert, ohne --status werden alle Status geliefert', async () => {
  const ticketFixture = createFixtureRoot();
  const ticketMock = createMockClaude({ outputChunks: [validTicketAgentOutput()], exitCode: 0 });
  const env = { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(ticketMock.binDir) };
  try {
    const first = JSON.parse(
      (await runCli(['ticket', 'from', 'default', 'text'], { env })).stdout,
    ) as CliTicket;
    const second = JSON.parse(
      (await runCli(['ticket', 'from', 'default', 'text'], { env })).stdout,
    ) as CliTicket;
    await runCli(['ticket', 'update', 'default', String(second.id), '--status', 'in progress'], {
      env,
    });

    const all = JSON.parse(
      (await runCli(['ticket', 'list', 'default'], { env })).stdout,
    ) as CliTicket[];
    assert.equal(all.length, 2);

    const filtered = JSON.parse(
      (await runCli(['ticket', 'list', 'default', '--status', 'in progress'], { env })).stdout,
    ) as CliTicket[];
    assert.deepEqual(
      filtered.map((t) => t.id),
      [second.id],
    );
    assert.notEqual(first.id, second.id);
  } finally {
    ticketMock.cleanup();
    ticketFixture.cleanup();
  }
});

test('cl ticket list --status invalid: bricht mit Fehler und Exit != 0 ab', async () => {
  const ticketFixture = createFixtureRoot();
  try {
    const result = await runCli(['ticket', 'list', 'default', '--status', 'invalid'], {
      env: { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(mock.binDir) },
    });
    assert.notEqual(result.exitCode, 0);
  } finally {
    ticketFixture.cleanup();
  }
});

test('cl ticket update: aendert Titel/Beschreibung/Aufgabe/Status ausser der ID', async () => {
  const ticketFixture = createFixtureRoot();
  const ticketMock = createMockClaude({ outputChunks: [validTicketAgentOutput()], exitCode: 0 });
  const env = { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(ticketMock.binDir) };
  try {
    const ticket = JSON.parse(
      (await runCli(['ticket', 'from', 'default', 'text'], { env })).stdout,
    ) as CliTicket;

    const updateResult = await runCli(
      [
        'ticket',
        'update',
        'default',
        String(ticket.id),
        '--title',
        'Neuer Titel',
        '--status',
        'closed',
      ],
      { env },
    );
    assert.equal(updateResult.exitCode, 0);
    const updated = JSON.parse(updateResult.stdout) as CliTicket;
    assert.equal(updated.id, ticket.id);
    assert.equal(updated.title, 'Neuer Titel');
    assert.equal(updated.status, 'closed');
    assert.equal(updated.description, ticket.description);
  } finally {
    ticketMock.cleanup();
    ticketFixture.cleanup();
  }
});

test('cl ticket update: ohne jegliche Option bricht mit Fehler und Exit != 0 ab', async () => {
  const ticketFixture = createFixtureRoot();
  const ticketMock = createMockClaude({ outputChunks: [validTicketAgentOutput()], exitCode: 0 });
  const env = { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(ticketMock.binDir) };
  try {
    const ticket = JSON.parse(
      (await runCli(['ticket', 'from', 'default', 'text'], { env })).stdout,
    ) as CliTicket;
    const result = await runCli(['ticket', 'update', 'default', String(ticket.id)], { env });
    assert.notEqual(result.exitCode, 0);
  } finally {
    ticketMock.cleanup();
    ticketFixture.cleanup();
  }
});

test('cl ticket update: unbekannte ID bricht mit Fehler und Exit != 0 ab', async () => {
  const ticketFixture = createFixtureRoot();
  try {
    const result = await runCli(['ticket', 'update', 'default', '999999', '--title', 'x'], {
      env: { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(mock.binDir) },
    });
    assert.notEqual(result.exitCode, 0);
  } finally {
    ticketFixture.cleanup();
  }
});

test('cl ticket delete: loescht ein Ticket, danach ist "cl ticket get <id>" ein Fehler', async () => {
  const ticketFixture = createFixtureRoot();
  const ticketMock = createMockClaude({ outputChunks: [validTicketAgentOutput()], exitCode: 0 });
  const env = { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(ticketMock.binDir) };
  try {
    const ticket = JSON.parse(
      (await runCli(['ticket', 'from', 'default', 'text'], { env })).stdout,
    ) as CliTicket;

    const deleteResult = await runCli(['ticket', 'delete', 'default', String(ticket.id)], { env });
    assert.equal(deleteResult.exitCode, 0);
    assert.match(deleteResult.stdout, new RegExp(String(ticket.id)));

    const getResult = await runCli(['ticket', 'get', 'default', String(ticket.id)], { env });
    assert.notEqual(getResult.exitCode, 0);
  } finally {
    ticketMock.cleanup();
    ticketFixture.cleanup();
  }
});

test('cl ticket delete: unbekannte ID bricht mit Fehler und Exit != 0 ab', async () => {
  const ticketFixture = createFixtureRoot();
  try {
    const result = await runCli(['ticket', 'delete', 'default', '999999'], {
      env: { CL_ROOT_DIR: ticketFixture.rootDir, PATH: pathWithMock(mock.binDir) },
    });
    assert.notEqual(result.exitCode, 0);
  } finally {
    ticketFixture.cleanup();
  }
});

test('cl ticket --help: listet die konfigurierten Pfade auf', async () => {
  const result = await runCli(['ticket', '--help'], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /Pfade \(aus config\.json, paths\[\]\.name\)/);
});

test('cl task mytask: startet immer interaktiv, startCommand wird als Prompt ohne --print gesendet', async () => {
  const result = await runCli(['task', 'mytask'], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  const invokedArgs = extractMockArgs(result.stdout);
  assert.deepEqual(invokedArgs, [
    '--model',
    'sonnet',
    '--append-system-prompt',
    '# Main-Context\n',
    '--permission-mode',
    'auto',
    'mach den mytask-kram',
  ]);
});

test('cl task doesnotexist: unbekannter Task bricht mit Fehler und Exit != 0 ab', async () => {
  const result = await runCli(['task', 'doesnotexist'], { env: baseEnv() });
  assert.notEqual(result.exitCode, 0);
  assert.match(result.stderr, /doesnotexist/);
});

test('cl inst: baut per Gradle im Debug-Modus und installiert die APK auf allen gefundenen adb-Geraeten', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-cli-inst-'));
  writeFakeGradlew(cwd, { buildType: 'debug', steps: [{ exitCode: 0, createApk: true }] });
  const adb = createMockAdb({
    devicesOutput: 'List of devices attached\nemulator-5554\tdevice\n\n',
    deviceNames: { 'emulator-5554': 'sdk_gphone64_x86_64' },
  });
  try {
    const result = await runCli(['inst'], {
      env: { CL_ROOT_DIR: fixture.rootDir, PATH: pathWithMockAdb(adb.binDir) },
      cwd,
    });
    assert.equal(result.exitCode, 0);
    assert.match(result.stdout, /app-debug\.apk/);
    assert.match(result.stdout, /Installiert auf sdk_gphone64_x86_64 \(emulator-5554\)\./);
    assert.match(
      result.stdout,
      /Installiert auf 1 Geraet: sdk_gphone64_x86_64 \(emulator-5554\)$/m,
    );
    const log = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    assert.deepEqual(log, [
      'devices',
      '-s emulator-5554 shell getprop ro.product.model',
      '-s emulator-5554 install -r ' +
        join(cwd, 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
    ]);
  } finally {
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('cl instr: baut per Gradle im Release-Modus und installiert die APK auf allen gefundenen adb-Geraeten', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-cli-instr-'));
  writeFakeGradlew(cwd, { buildType: 'release', steps: [{ exitCode: 0, createApk: true }] });
  const adb = createMockAdb({
    devicesOutput: 'List of devices attached\nemulator-5554\tdevice\nABC123\tdevice\n\n',
    failSerials: ['ABC123'],
  });
  try {
    const result = await runCli(['instr'], {
      env: { CL_ROOT_DIR: fixture.rootDir, PATH: pathWithMockAdb(adb.binDir) },
      cwd,
    });
    assert.equal(result.exitCode, 0);
    assert.match(result.stdout, /Installiert auf emulator-5554 \(emulator-5554\)\./);
    assert.match(result.stderr, /Installation auf ABC123 \(ABC123\) fehlgeschlagen/);
    assert.match(result.stdout, /Installiert auf 1 Geraet: emulator-5554 \(emulator-5554\)$/m);
  } finally {
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('cl inst: Build-Fehler startet den Sonnet-Fix-Agent im Auto-Mode, danach erneuter Build + Install', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-cli-inst-fix-'));
  writeFakeGradlew(cwd, {
    buildType: 'debug',
    steps: [
      { exitCode: 1, stdout: 'e: MainActivity.kt: Unresolved reference: foo' },
      { exitCode: 0, createApk: true },
    ],
  });
  const claudeLogFile = join(cwd, 'claude.log');
  const fixAgent = createMockClaude({ exitCode: 0, logFile: claudeLogFile });
  const adb = createMockAdb({
    devicesOutput: 'List of devices attached\nemulator-5554\tdevice\n\n',
  });
  try {
    const result = await runCli(['inst'], {
      env: {
        CL_ROOT_DIR: fixture.rootDir,
        PATH: [fixAgent.binDir, adb.binDir, process.env.PATH ?? ''].join(delimiter),
      },
      cwd,
    });
    assert.equal(result.exitCode, 0);
    assert.match(result.stdout, /Installiert auf emulator-5554/);

    const invocations = readFileSync(claudeLogFile, 'utf8').trim().split('\n');
    assert.equal(invocations.length, 1);
    const [firstInvocation] = invocations;
    assert.ok(firstInvocation);
    const invokedArgs = JSON.parse(firstInvocation) as string[];
    assert.deepEqual(invokedArgs.slice(0, 2), ['--model', 'sonnet']);
    assert.ok(invokedArgs.some((arg) => arg.includes('Unresolved reference: foo')));
  } finally {
    fixAgent.cleanup();
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('cl inst: Build-Fehler ohne verfuegbaren Fix-Agent ("claude" fehlt) -> Exit != 0', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-cli-inst-fail-'));
  writeFakeGradlew(cwd, { buildType: 'debug', steps: [{ exitCode: 1, stdout: 'error' }] });
  const adb = createMockAdb();
  try {
    const result = await runCli(['inst'], {
      // Deliberately excludes the real PATH so a real "claude" binary on the test
      // machine can never be found/invoked here; /usr/bin + /bin are kept so the
      // fake gradlew's own shell built-ins resolve.
      env: {
        CL_ROOT_DIR: fixture.rootDir,
        PATH: [adb.binDir, '/usr/bin', '/bin'].join(delimiter),
      },
      cwd,
    });
    assert.notEqual(result.exitCode, 0);
  } finally {
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('cl --help: listet auch alle Tasks aus config.json auf', async () => {
  const result = await runCli(['--help'], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /Tasks \(aus config\.json\)/);
  assert.match(result.stdout, /cl task mytask\s+Mytask-Desc/);
});

test('cl task --help: listet alle Tasks aus config.json auf, Exit 0', async () => {
  const result = await runCli(['task', '--help'], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /Tasks \(aus config\.json\)/);
  assert.match(result.stdout, /cl task mytask\s+Mytask-Desc/);
});

test('cl <model> --help: listet alle Agents aus config.json auf, Exit 0', async () => {
  const result = await runCli(['sonnet', '--help'], { env: baseEnv() });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /Agents \(aus config\.json\)/);
  assert.match(result.stdout, /cl\s+Main-Agent-Desc/);
  assert.match(result.stdout, /cl dev\s+Dev-Agent-Desc/);
});

test('cl server --help: listet Agent-Endpunkte sowie Pfade mit Commands und Hosted-Eintraegen auf', async () => {
  const serverFixture = createFixtureRoot({
    main: { description: 'Main-Agent-Desc' },
    agents: [{ name: 'dev', description: 'Dev-Agent-Desc' }],
    paths: [
      {
        name: 'proj',
        path: '/tmp/proj',
        commands: [
          {
            key: 'clean',
            command: './gradlew clean',
            displayName: 'Clean',
            description: 'Clean-Desc',
          },
        ],
        hosted: [{ name: 'apk', path: 'app.apk', type: 'file' }],
      },
    ],
  });
  try {
    const result = await runCli(['server', '--help'], {
      env: { ...baseEnv(), CL_ROOT_DIR: serverFixture.rootDir },
    });
    assert.equal(result.exitCode, 0);
    assert.match(result.stdout, /POST \/\s+Main-Agent-Desc/);
    assert.match(result.stdout, /POST \/dev\s+Dev-Agent-Desc/);
    assert.match(result.stdout, /Pfade \(aus config\.json/);
    assert.match(result.stdout, /POST \/paths\/proj\/commands\/clean\s+Clean: Clean-Desc/);
    assert.match(result.stdout, /GET\s+\/files\/proj\/apk\s+\(file\)/);
  } finally {
    serverFixture.cleanup();
  }
});

test('cl task --help: ohne konfigurierte Tasks wird ein Hinweis angezeigt', async () => {
  const emptyFixture = createFixtureRoot({ tasks: [] });
  try {
    const result = await runCli(['task', '--help'], {
      env: { ...baseEnv(), CL_ROOT_DIR: emptyFixture.rootDir },
    });
    assert.equal(result.exitCode, 0);
    assert.match(result.stdout, /Keine Tasks konfiguriert\./);
  } finally {
    emptyFixture.cleanup();
  }
});
