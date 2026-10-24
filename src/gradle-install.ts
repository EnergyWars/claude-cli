import { spawn } from 'node:child_process';
import { existsSync, readdirSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve as resolvePath } from 'node:path';

import { connectConfiguredAdb } from './adb-connect.js';
import {
  hasGodotErrors,
  isGodotProject,
  readAndroidExportPreset,
  resolveGodotExecutable,
  runGodotExport,
} from './godot-build.js';
import { buildClaudeArgs } from './launch.js';
import { type CapturedProcessResult, isOnPath, runCapturedProcess } from './process-utils.js';

export type GradleBuildType = 'debug' | 'release';

export type ProjectType = 'gradle' | 'godot';

export function detectProjectType(cwd: string): ProjectType {
  return isGodotProject(cwd) ? 'godot' : 'gradle';
}

const GRADLE_TASKS: Record<GradleBuildType, string> = {
  debug: 'assembleDebug',
  release: 'assembleRelease',
};

const FIX_AGENT_MODEL = 'sonnet';

const MAX_FIX_ATTEMPTS = 10;

const FIX_AGENT_SYSTEM_PROMPT =
  'Du bist ein Android-Build-Fix-Agent. Du bekommst die Ausgabe eines fehlgeschlagenen oder ' +
  'Warnings enthaltenden Gradle-Builds. Behebe die Ursache im Code, sodass ein erneuter ' +
  'Gradle-Build fehlerfrei und ohne Warnings durchlaeuft.';

const GODOT_FIX_AGENT_SYSTEM_PROMPT =
  'Du bist ein Godot-Build-Fix-Agent. Du bekommst die Ausgabe eines fehlgeschlagenen oder ' +
  'Fehlermeldungen enthaltenden headless Godot-Android-Exports. Behebe die Ursache im Projekt ' +
  '(GDScript, Szenen, Export-Preset), sodass ein erneuter Export fehlerfrei durchlaeuft.';

const GRADLE_DEPRECATION_FOOTER_PATTERN =
  /^(Deprecated Gradle features were used|You can use '--warning-mode all'|For more on this, please refer to https:\/\/docs\.gradle\.org\/.*command_line_warnings).*$/;

function hasWarnings(output: string): boolean {
  const lines = output.split('\n').filter((line) => !GRADLE_DEPRECATION_FOOTER_PATTERN.test(line.trim()));
  // Kotlinc emits "w: <file>: <message>" per line instead of the word "warning".
  return lines.some((line) => /warning/i.test(line) || /^\s*w:\s/.test(line));
}

function defaultSdkDirs(): string[] {
  const home = homedir();
  if (process.platform === 'darwin') {
    return [join(home, 'Library', 'Android', 'sdk')];
  }
  if (process.platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA ?? join(home, 'AppData', 'Local');
    return [join(localAppData, 'Android', 'Sdk')];
  }
  return [join(home, 'Android', 'Sdk')];
}

/** Loest den adb-Pfad auf: PATH hat Vorrang, sonst ANDROID_HOME/ANDROID_SDK_ROOT/Standard-SDK-Verzeichnis. */
export function resolveAdbExecutable(): string {
  if (isOnPath('adb')) {
    return 'adb';
  }
  const adbBinaryName = process.platform === 'win32' ? 'adb.exe' : 'adb';
  const sdkDirs = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, ...defaultSdkDirs()].filter(
    (dir): dir is string => typeof dir === 'string' && dir.length > 0,
  );
  for (const sdkDir of sdkDirs) {
    const candidate = join(sdkDir, 'platform-tools', adbBinaryName);
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  return 'adb';
}

export function parseAdbDevices(output: string): string[] {
  const lines = output.split('\n');
  const headerIndex = lines.findIndex((line) => line.trim() === 'List of devices attached');
  const deviceLines = headerIndex === -1 ? [] : lines.slice(headerIndex + 1);
  return deviceLines
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => line.split('\t')[0] ?? line);
}

function listApks(cwd: string, buildType: GradleBuildType): string[] {
  const targetSuffix = join('build', 'outputs', 'apk', buildType);
  const matches: string[] = [];

  function walk(dir: string): void {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith('.')) {
        continue;
      }
      const full = join(dir, entry.name);
      if (full.endsWith(targetSuffix)) {
        for (const file of readdirSync(full)) {
          if (file.endsWith('.apk')) {
            matches.push(join(full, file));
          }
        }
        continue;
      }
      walk(full);
    }
  }

  walk(cwd);
  return matches;
}

export function findApk(cwd: string, buildType: GradleBuildType): string {
  const [first] = listApks(cwd, buildType);
  if (first === undefined) {
    const targetSuffix = join('build', 'outputs', 'apk', buildType);
    throw new Error(`Keine APK gefunden unter **/${targetSuffix}/*.apk in ${cwd}.`);
  }
  return first;
}

/** ISO-Zeitstempel der zuletzt geaenderten APK unter `**\/build/outputs/apk/<buildType>/*.apk`, `null` ohne Treffer. */
export function findLatestBuildTimestamp(cwd: string, buildType: GradleBuildType): string | null {
  const matches = listApks(cwd, buildType);
  let latestMtimeMs: number | null = null;
  for (const match of matches) {
    const { mtimeMs } = statSync(match);
    if (latestMtimeMs === null || mtimeMs > latestMtimeMs) {
      latestMtimeMs = mtimeMs;
    }
  }
  return latestMtimeMs === null ? null : new Date(latestMtimeMs).toISOString();
}

interface FixLoopSpec {
  actionLabel: string;
  commandText: string;
  problemLabel: string;
  fixAgentSystemPrompt: string;
  run: () => Promise<CapturedProcessResult>;
  hasProblems: (output: string) => boolean;
}

function runGradleTask(cwd: string, task: string): Promise<CapturedProcessResult> {
  return runCapturedProcess('./gradlew', [task], cwd);
}

async function runWithFixLoop(cwd: string, spec: FixLoopSpec): Promise<void> {
  const { actionLabel, commandText, problemLabel } = spec;
  for (let attempt = 1; attempt <= MAX_FIX_ATTEMPTS; attempt += 1) {
    const { exitCode, output } = await spec.run();
    if (exitCode === 0 && !spec.hasProblems(output)) {
      return;
    }

    if (attempt === MAX_FIX_ATTEMPTS) {
      throw new Error(
        `${actionLabel} (${commandText}) ist nach ${String(MAX_FIX_ATTEMPTS)} Durchlaeufen weiterhin fehlgeschlagen oder enthaelt ${problemLabel}.`,
      );
    }

    const reason =
      exitCode !== 0
        ? `${actionLabel} (${commandText}) ist fehlgeschlagen (Exit-Code ${String(exitCode)}).`
        : `${actionLabel} (${commandText}) war erfolgreich, enthaelt aber ${problemLabel}.`;
    console.log(
      `${reason} Starte Claude (${FIX_AGENT_MODEL}, Auto-Mode) zur Behebung (Durchlauf ${String(attempt)}/${String(MAX_FIX_ATTEMPTS)})...`,
    );
    await runFixAgent(cwd, spec.fixAgentSystemPrompt, `${reason}\n\nOutput:\n${output}`);
    console.log('Fix-Agent beendet, starte den Durchlauf erneut...');
  }
}

function runTaskWithFixLoop(cwd: string, task: string, actionLabel: string): Promise<void> {
  return runWithFixLoop(cwd, {
    actionLabel,
    commandText: `./gradlew ${task}`,
    problemLabel: 'Warnings',
    fixAgentSystemPrompt: FIX_AGENT_SYSTEM_PROMPT,
    run: () => runGradleTask(cwd, task),
    hasProblems: hasWarnings,
  });
}

async function buildGodotApk(cwd: string, buildType: GradleBuildType): Promise<string> {
  const preset = readAndroidExportPreset(cwd);
  const godotExecutable = resolveGodotExecutable();
  const exportFlag = buildType === 'debug' ? '--export-debug' : '--export-release';
  await runWithFixLoop(cwd, {
    actionLabel: 'Der Godot-Export',
    commandText: `godot ${exportFlag} "${preset.name}"`,
    problemLabel: 'Fehlermeldungen',
    fixAgentSystemPrompt: GODOT_FIX_AGENT_SYSTEM_PROMPT,
    run: () => runGodotExport(cwd, buildType, godotExecutable, preset),
    hasProblems: hasGodotErrors,
  });
  const apkPath = resolvePath(cwd, preset.exportPath);
  if (!existsSync(apkPath)) {
    throw new Error(`Godot-Export hat keine APK unter ${apkPath} erzeugt.`);
  }
  return apkPath;
}

function runFixAgent(cwd: string, systemPrompt: string, message: string): Promise<void> {
  const args = buildClaudeArgs(FIX_AGENT_MODEL, systemPrompt, message);
  return new Promise((resolve, reject) => {
    const child = spawn('claude', args, { cwd, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', () => {
      resolve();
    });
  });
}

function listAdbDevices(adbExecutable: string): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const child = spawn(adbExecutable, ['devices']);
    let output = '';
    child.stdout.on('data', (chunk: Buffer) => {
      output += chunk.toString('utf8');
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) {
        resolve(parseAdbDevices(output));
      } else {
        reject(new Error(`adb devices beendet mit Exit-Code ${String(code)}.`));
      }
    });
  });
}

function readDeviceProp(adbExecutable: string, serial: string, prop: string): Promise<string | null> {
  return new Promise((resolve) => {
    const child = spawn(adbExecutable, ['-s', serial, 'shell', 'getprop', prop]);
    let output = '';
    child.stdout.on('data', (chunk: Buffer) => {
      output += chunk.toString('utf8');
    });
    child.on('error', () => {
      resolve(null);
    });
    child.on('exit', (code) => {
      const value = output.trim();
      resolve(code === 0 && value.length > 0 ? value : null);
    });
  });
}

export function connectionPriority(serial: string): number {
  if (serial.includes('._adb-tls-connect')) {
    return 2;
  }
  return serial.includes(':') ? 1 : 0;
}

export function dedupeDevices<T extends { serial: string; hardwareId: string }>(
  devices: readonly T[],
): T[] {
  const best = new Map<string, T>();
  for (const device of devices) {
    const current = best.get(device.hardwareId);
    if (current === undefined || connectionPriority(device.serial) < connectionPriority(current.serial)) {
      best.set(device.hardwareId, device);
    }
  }
  return [...best.values()];
}

export function formatInstallSummary(
  installed: readonly { serial: string; name: string }[],
): string {
  if (installed.length === 0) {
    return 'Auf keinem Geraet installiert.';
  }
  const list = installed.map(({ serial, name }) => `${name} (${serial})`).join(', ');
  const noun = installed.length === 1 ? 'Geraet' : 'Geraeten';
  return `Installiert auf ${String(installed.length)} ${noun}: ${list}`;
}

function installApk(adbExecutable: string, serial: string, apkPath: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(adbExecutable, ['-s', serial, 'install', '-r', apkPath], { stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`adb install beendet mit Exit-Code ${String(code)}.`));
      }
    });
  });
}

export async function buildAndInstall(
  buildType: GradleBuildType,
  cwd: string = process.cwd(),
): Promise<void> {
  let apkPath: string;
  if (detectProjectType(cwd) === 'godot') {
    apkPath = await buildGodotApk(cwd, buildType);
  } else {
    await runTaskWithFixLoop(cwd, GRADLE_TASKS[buildType], 'Der Gradle-Build');
    apkPath = findApk(cwd, buildType);
  }
  console.log(`APK: ${apkPath}`);

  const adbExecutable = resolveAdbExecutable();
  await connectConfiguredAdb(adbExecutable);
  const devices = await listAdbDevices(adbExecutable);
  if (devices.length === 0) {
    console.log('Keine adb-Geraete gefunden.');
    return;
  }

  const identified = await Promise.all(
    devices.map(async (serial) => {
      const name = (await readDeviceProp(adbExecutable, serial, 'ro.product.model')) ?? serial;
      const hardwareId = (await readDeviceProp(adbExecutable, serial, 'ro.serialno')) ?? serial;
      return { serial, name, hardwareId };
    }),
  );
  const uniqueDevices = dedupeDevices(identified);
  for (const skipped of identified.filter((device) => !uniqueDevices.includes(device))) {
    console.log(`Uebersprungen (selbes Geraet wie ${skipped.hardwareId}): ${skipped.serial}`);
  }

  const installed: { serial: string; name: string }[] = [];
  for (const { serial, name } of uniqueDevices) {
    try {
      await installApk(adbExecutable, serial, apkPath);
      console.log(`Installiert auf ${name} (${serial}).`);
      installed.push({ serial, name });
    } catch (error) {
      console.error(
        `Installation auf ${name} (${serial}) fehlgeschlagen: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }
  console.log(formatInstallSummary(installed));
}

export async function runUnitTests(cwd: string = process.cwd()): Promise<void> {
  await runTaskWithFixLoop(cwd, 'test', 'Der Testlauf');
  console.log('Unit-Tests erfolgreich und ohne Warnings.');
}
