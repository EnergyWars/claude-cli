import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { test } from 'node:test';

import { buildAndInstall, findApk, parseAdbDevices } from './gradle-install.js';
import { createMockAdb, pathWithMockAdb } from './test-support/mock-adb.js';
import { createMockClaude } from './test-support/mock-claude.js';
import { readGradlewCallCount, writeFakeGradlew } from './test-support/mock-gradlew.js';

test('parseAdbDevices: parst Serials aus normaler Ausgabe', () => {
  const output = 'List of devices attached\nemulator-5554\tdevice\nABC123\tdevice\n\n';
  assert.deepEqual(parseAdbDevices(output), ['emulator-5554', 'ABC123']);
});

test('parseAdbDevices: ignoriert Daemon-Startmeldungen vor dem Header', () => {
  const output =
    '* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\nemulator-5554\tdevice\n\n';
  assert.deepEqual(parseAdbDevices(output), ['emulator-5554']);
});

test('parseAdbDevices: leere Geraeteliste liefert leeres Array', () => {
  assert.deepEqual(parseAdbDevices('List of devices attached\n\n'), []);
});

test('parseAdbDevices: fehlender Header liefert leeres Array', () => {
  assert.deepEqual(parseAdbDevices('irgendwas unerwartetes\n'), []);
});

test('findApk: findet APK unterhalb von build/outputs/apk/<type>', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-find-apk-'));
  try {
    const apkDir = join(cwd, 'app', 'build', 'outputs', 'apk', 'debug');
    mkdirSync(apkDir, { recursive: true });
    writeFileSync(join(apkDir, 'app-debug.apk'), 'FAKE');
    assert.equal(findApk(cwd, 'debug'), join(apkDir, 'app-debug.apk'));
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('findApk: durchsucht nur den passenden Build-Typ', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-find-apk-type-'));
  try {
    const debugDir = join(cwd, 'app', 'build', 'outputs', 'apk', 'debug');
    mkdirSync(debugDir, { recursive: true });
    writeFileSync(join(debugDir, 'app-debug.apk'), 'FAKE');
    assert.throws(() => findApk(cwd, 'release'));
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('findApk: wirft wenn keine APK gefunden wird', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-find-apk-missing-'));
  try {
    assert.throws(() => findApk(cwd, 'release'));
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('buildAndInstall: baut, findet APK und installiert auf allen gefundenen Geraeten', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-build-install-'));
  writeFakeGradlew(cwd, { buildType: 'debug', steps: [{ exitCode: 0, createApk: true }] });
  const adb = createMockAdb({
    devicesOutput: 'List of devices attached\nemulator-5554\tdevice\nABC123\tdevice\n\n',
  });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMockAdb(adb.binDir);
  try {
    await buildAndInstall('debug', cwd);
    const log = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    assert.equal(log[0], 'devices');
    const installedSerials = log
      .slice(1)
      .map((line) => line.split(' ')[1])
      .sort();
    assert.deepEqual(installedSerials, ['ABC123', 'emulator-5554']);
  } finally {
    process.env.PATH = previousPath;
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('buildAndInstall: Installationsfehler auf einem Geraet bricht die anderen nicht ab', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-build-install-partial-fail-'));
  writeFakeGradlew(cwd, { buildType: 'debug', steps: [{ exitCode: 0, createApk: true }] });
  const adb = createMockAdb({
    devicesOutput: 'List of devices attached\nemulator-5554\tdevice\nABC123\tdevice\n\n',
    failSerials: ['emulator-5554'],
  });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMockAdb(adb.binDir);
  try {
    await buildAndInstall('debug', cwd);
    const log = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    const installedSerials = log
      .slice(1)
      .map((line) => line.split(' ')[1])
      .sort();
    assert.deepEqual(installedSerials, ['ABC123', 'emulator-5554']);
  } finally {
    process.env.PATH = previousPath;
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('buildAndInstall: ohne gefundene Geraete wird kein Install versucht', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-build-install-no-devices-'));
  writeFakeGradlew(cwd, { buildType: 'release', steps: [{ exitCode: 0, createApk: true }] });
  const adb = createMockAdb({ devicesOutput: 'List of devices attached\n\n' });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMockAdb(adb.binDir);
  try {
    await buildAndInstall('release', cwd);
    const log = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    assert.deepEqual(log, ['devices']);
  } finally {
    process.env.PATH = previousPath;
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('buildAndInstall: startet bei Build-Fehler einen Sonnet-Fix-Agent im Auto-Mode und versucht den Build danach erneut', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-build-fix-error-'));
  writeFakeGradlew(cwd, {
    buildType: 'debug',
    steps: [
      { exitCode: 1, stdout: 'e: MainActivity.kt: Unresolved reference: foo' },
      { exitCode: 0, createApk: true },
    ],
  });
  const claudeLogFile = join(cwd, 'claude.log');
  const claude = createMockClaude({ exitCode: 0, logFile: claudeLogFile });
  const adb = createMockAdb({
    devicesOutput: 'List of devices attached\nemulator-5554\tdevice\n\n',
  });
  const previousPath = process.env.PATH;
  process.env.PATH = [claude.binDir, adb.binDir, previousPath ?? ''].join(delimiter);
  try {
    await buildAndInstall('debug', cwd);

    const invocations = readFileSync(claudeLogFile, 'utf8').trim().split('\n');
    assert.equal(invocations.length, 1);
    const [firstInvocation] = invocations;
    assert.ok(firstInvocation);
    const invokedArgs = JSON.parse(firstInvocation) as string[];
    assert.deepEqual(invokedArgs.slice(0, 2), ['--model', 'sonnet']);
    const permissionModeIndex = invokedArgs.indexOf('--permission-mode');
    assert.equal(invokedArgs[permissionModeIndex + 1], 'auto');
    assert.ok(invokedArgs.includes('--print'));
    assert.ok(invokedArgs.some((arg) => arg.includes('Unresolved reference: foo')));

    assert.equal(readGradlewCallCount(cwd), 2);

    const adbLog = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    assert.equal(adbLog[0], 'devices');
    assert.equal(adbLog.length, 2);
  } finally {
    process.env.PATH = previousPath;
    claude.cleanup();
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('buildAndInstall: startet den Fix-Agent auch bei erfolgreichem Build mit Warnings', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-build-fix-warnings-'));
  writeFakeGradlew(cwd, {
    buildType: 'debug',
    steps: [
      { exitCode: 0, stdout: 'w: Foo.kt: unused variable bar', createApk: true },
      { exitCode: 0, createApk: true },
    ],
  });
  const claudeLogFile = join(cwd, 'claude.log');
  const claude = createMockClaude({ exitCode: 0, logFile: claudeLogFile });
  const adb = createMockAdb({ devicesOutput: 'List of devices attached\n\n' });
  const previousPath = process.env.PATH;
  process.env.PATH = [claude.binDir, adb.binDir, previousPath ?? ''].join(delimiter);
  try {
    await buildAndInstall('debug', cwd);

    const invocations = readFileSync(claudeLogFile, 'utf8').trim().split('\n');
    assert.equal(invocations.length, 1);
    const [firstInvocation] = invocations;
    assert.ok(firstInvocation);
    const invokedArgs = JSON.parse(firstInvocation) as string[];
    assert.ok(invokedArgs.some((arg) => arg.includes('unused variable bar')));

    assert.equal(readGradlewCallCount(cwd), 2);
  } finally {
    process.env.PATH = previousPath;
    claude.cleanup();
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('buildAndInstall: wiederholt Build+Fix-Agent so oft bis der Build fehlerfrei ist', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-build-fix-repeat-'));
  writeFakeGradlew(cwd, {
    buildType: 'debug',
    steps: [
      { exitCode: 1, stdout: 'error 1' },
      { exitCode: 1, stdout: 'error 2' },
      { exitCode: 0, createApk: true },
    ],
  });
  const claudeLogFile = join(cwd, 'claude.log');
  const claude = createMockClaude({ exitCode: 0, logFile: claudeLogFile });
  const adb = createMockAdb({ devicesOutput: 'List of devices attached\n\n' });
  const previousPath = process.env.PATH;
  process.env.PATH = [claude.binDir, adb.binDir, previousPath ?? ''].join(delimiter);
  try {
    await buildAndInstall('debug', cwd);
    const invocations = readFileSync(claudeLogFile, 'utf8').trim().split('\n');
    assert.equal(invocations.length, 2);
    assert.equal(readGradlewCallCount(cwd), 3);
  } finally {
    process.env.PATH = previousPath;
    claude.cleanup();
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('buildAndInstall: wirft wenn "claude" fuer den Fix-Agent nicht gefunden wird', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-build-fix-no-claude-'));
  writeFakeGradlew(cwd, { buildType: 'debug', steps: [{ exitCode: 1, stdout: 'error' }] });
  const adb = createMockAdb();
  const previousPath = process.env.PATH;
  // Deliberately excludes the real PATH (unlike pathWithMockAdb) so a real "claude"
  // binary that might be installed on the test machine can never be found/invoked
  // here; /usr/bin + /bin are kept so the fake gradlew's own shell built-ins resolve.
  process.env.PATH = [adb.binDir, '/usr/bin', '/bin'].join(delimiter);
  try {
    await assert.rejects(() => buildAndInstall('debug', cwd));
  } finally {
    process.env.PATH = previousPath;
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});
