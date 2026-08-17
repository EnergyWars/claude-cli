import { spawn } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { join } from 'node:path';

import { buildClaudeArgs } from './launch.js';

export type GradleBuildType = 'debug' | 'release';

const GRADLE_TASKS: Record<GradleBuildType, string> = {
  debug: 'assembleDebug',
  release: 'assembleRelease',
};

const FIX_AGENT_MODEL = 'sonnet';

const FIX_AGENT_SYSTEM_PROMPT =
  'Du bist ein Android-Build-Fix-Agent. Du bekommst die Ausgabe eines fehlgeschlagenen oder ' +
  'Warnings enthaltenden Gradle-Builds. Behebe die Ursache im Code, sodass ein erneuter ' +
  'Gradle-Build fehlerfrei und ohne Warnings durchlaeuft.';

function hasWarnings(output: string): boolean {
  if (/warning/i.test(output)) {
    return true;
  }
  // Kotlinc emits "w: <file>: <message>" per line instead of the word "warning".
  return output.split('\n').some((line) => /^\s*w:\s/.test(line));
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

export function findApk(cwd: string, buildType: GradleBuildType): string {
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

  const [first] = matches;
  if (first === undefined) {
    throw new Error(`Keine APK gefunden unter **/${targetSuffix}/*.apk in ${cwd}.`);
  }
  return first;
}

interface GradleBuildResult {
  exitCode: number;
  output: string;
}

function runGradleBuild(cwd: string, buildType: GradleBuildType): Promise<GradleBuildResult> {
  const task = GRADLE_TASKS[buildType];
  return new Promise((resolve, reject) => {
    const child = spawn('./gradlew', [task], { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';

    const handleChunk = (chunk: Buffer): void => {
      output += chunk.toString('utf8');
      process.stdout.write(chunk);
    };

    child.stdout.on('data', handleChunk);
    child.stderr.on('data', handleChunk);
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve({ exitCode: code ?? 1, output });
    });
  });
}

function runFixAgent(cwd: string, message: string): Promise<void> {
  const args = buildClaudeArgs(FIX_AGENT_MODEL, FIX_AGENT_SYSTEM_PROMPT, message);
  return new Promise((resolve, reject) => {
    const child = spawn('claude', args, { cwd, stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', () => {
      resolve();
    });
  });
}

function listAdbDevices(): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const child = spawn('adb', ['devices']);
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

function readDeviceName(serial: string): Promise<string> {
  return new Promise((resolve) => {
    const child = spawn('adb', ['-s', serial, 'shell', 'getprop', 'ro.product.model']);
    let output = '';
    child.stdout.on('data', (chunk: Buffer) => {
      output += chunk.toString('utf8');
    });
    child.on('error', () => {
      resolve(serial);
    });
    child.on('exit', (code) => {
      const name = output.trim();
      resolve(code === 0 && name.length > 0 ? name : serial);
    });
  });
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

function installApk(serial: string, apkPath: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn('adb', ['-s', serial, 'install', '-r', apkPath], { stdio: 'inherit' });
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
  const task = GRADLE_TASKS[buildType];

  for (;;) {
    const { exitCode, output } = await runGradleBuild(cwd, buildType);
    if (exitCode === 0 && !hasWarnings(output)) {
      break;
    }

    const reason =
      exitCode !== 0
        ? `Der Gradle-Build (./gradlew ${task}) ist fehlgeschlagen (Exit-Code ${String(exitCode)}).`
        : `Der Gradle-Build (./gradlew ${task}) war erfolgreich, enthaelt aber Warnings.`;
    console.log(`${reason} Starte Claude (${FIX_AGENT_MODEL}, Auto-Mode) zur Behebung...`);
    await runFixAgent(cwd, `${reason}\n\nBuild-Output:\n${output}`);
    console.log('Fix-Agent beendet, starte den Build erneut...');
  }

  const apkPath = findApk(cwd, buildType);
  console.log(`APK: ${apkPath}`);

  const devices = await listAdbDevices();
  if (devices.length === 0) {
    console.log('Keine adb-Geraete gefunden.');
    return;
  }

  const installed: { serial: string; name: string }[] = [];
  for (const serial of devices) {
    const name = await readDeviceName(serial);
    try {
      await installApk(serial, apkPath);
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
