import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { test } from 'node:test';

import {
  buildAndInstall,
  findApk,
  findLatestBuildTimestamp,
  formatInstallSummary,
  parseAdbDevices,
} from './gradle-install.js';
import { createMockAdb, pathWithMockAdb } from './test-support/mock-adb.js';
import { createMockClaude } from './test-support/mock-claude.js';
import { readGradlewCallCount, writeFakeGradlew } from './test-support/mock-gradlew.js';

test('parseAdbDevices: parst Serials aus normaler Ausgabe', () => {
  const output = 'List of devices attached\nemulator-5554\tdevice\nABC123\tdevice\n\n';
  assert.deepEqual(parseAdbDevices(output), ['emulator-5554', 'ABC123']);
});

test('parseAdbDevices: erhaelt Leerzeichen in mDNS-Geraetenamen (adb-tls-connect)', () => {
  const output =
    'List of devices attached\nadb-S8TKOBCAX8DAGQMN-NtwqFp (2)._adb-tls-connect._tcp\tdevice\n\n';
  assert.deepEqual(parseAdbDevices(output), [
    'adb-S8TKOBCAX8DAGQMN-NtwqFp (2)._adb-tls-connect._tcp',
  ]);
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

test('formatInstallSummary: nennt Anzahl und Namen der Geraete', () => {
  assert.equal(
    formatInstallSummary([
      { serial: 'ABC123', name: 'Pixel 7' },
      { serial: 'emulator-5554', name: 'sdk_gphone64_x86_64' },
    ]),
    'Installiert auf 2 Geraeten: Pixel 7 (ABC123), sdk_gphone64_x86_64 (emulator-5554)',
  );
});

test('formatInstallSummary: Singular bei einem Geraet', () => {
  assert.equal(
    formatInstallSummary([{ serial: 'ABC123', name: 'Pixel 7' }]),
    'Installiert auf 1 Geraet: Pixel 7 (ABC123)',
  );
});

test('formatInstallSummary: ohne Installation', () => {
  assert.equal(formatInstallSummary([]), 'Auf keinem Geraet installiert.');
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

test('findLatestBuildTimestamp: liefert den ISO-Zeitstempel der APK', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-latest-apk-'));
  try {
    const apkDir = join(cwd, 'app', 'build', 'outputs', 'apk', 'debug');
    mkdirSync(apkDir, { recursive: true });
    const apkPath = join(apkDir, 'app-debug.apk');
    writeFileSync(apkPath, 'FAKE');
    const mtime = new Date('2026-01-15T10:00:00.000Z');
    utimesSync(apkPath, mtime, mtime);
    assert.equal(findLatestBuildTimestamp(cwd, 'debug'), mtime.toISOString());
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('findLatestBuildTimestamp: liefert den Zeitstempel der zuletzt geaenderten APK bei mehreren Treffern', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-latest-apk-multi-'));
  try {
    const olderDir = join(cwd, 'module-a', 'build', 'outputs', 'apk', 'debug');
    const newerDir = join(cwd, 'module-b', 'build', 'outputs', 'apk', 'debug');
    mkdirSync(olderDir, { recursive: true });
    mkdirSync(newerDir, { recursive: true });
    const olderApk = join(olderDir, 'a-debug.apk');
    const newerApk = join(newerDir, 'b-debug.apk');
    writeFileSync(olderApk, 'FAKE');
    writeFileSync(newerApk, 'FAKE');
    const olderMtime = new Date('2026-01-10T10:00:00.000Z');
    const newerMtime = new Date('2026-01-20T10:00:00.000Z');
    utimesSync(olderApk, olderMtime, olderMtime);
    utimesSync(newerApk, newerMtime, newerMtime);
    assert.equal(findLatestBuildTimestamp(cwd, 'debug'), newerMtime.toISOString());
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('findLatestBuildTimestamp: null ohne gefundene APK', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-latest-apk-missing-'));
  try {
    assert.equal(findLatestBuildTimestamp(cwd, 'release'), null);
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
      .filter((line) => line.includes(' install '))
      .map((line) => line.split(' ')[1])
      .sort();
    assert.deepEqual(installedSerials, ['ABC123', 'emulator-5554']);
  } finally {
    process.env.PATH = previousPath;
    adb.cleanup();
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('buildAndInstall: gibt am Ende Anzahl und Namen der installierten Geraete aus', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-build-install-summary-'));
  writeFakeGradlew(cwd, { buildType: 'debug', steps: [{ exitCode: 0, createApk: true }] });
  const adb = createMockAdb({
    devicesOutput:
      'List of devices attached\nemulator-5554\tdevice\nABC123\tdevice\nDEF456\tdevice\n\n',
    deviceNames: { ABC123: 'Pixel 7', 'emulator-5554': 'sdk_gphone64_x86_64' },
    failSerials: ['emulator-5554'],
  });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMockAdb(adb.binDir);
  const logged: string[] = [];
  const originalLog = console.log;
  console.log = (...args: unknown[]) => {
    logged.push(args.map(String).join(' '));
  };
  try {
    await buildAndInstall('debug', cwd);
    assert.ok(logged.includes('Installiert auf Pixel 7 (ABC123).'));
    assert.ok(logged.includes('Installiert auf DEF456 (DEF456).'));
    assert.equal(logged.at(-1), 'Installiert auf 2 Geraeten: Pixel 7 (ABC123), DEF456 (DEF456)');
  } finally {
    console.log = originalLog;
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
      .filter((line) => line.includes(' install '))
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
    assert.equal(adbLog.filter((line) => line.includes(' install ')).length, 1);
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
