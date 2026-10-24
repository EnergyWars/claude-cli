import { existsSync, mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve as resolvePath } from 'node:path';

import { type CapturedProcessResult, isOnPath, runCapturedProcess } from './process-utils.js';

export type GodotBuildType = 'debug' | 'release';

export interface AndroidExportPreset {
  name: string;
  exportPath: string;
}

export const GODOT_BIN_ENV_VAR = 'GODOT_BIN';

const GODOT_PROJECT_FILE = 'project.godot';
const EXPORT_PRESETS_FILE = 'export_presets.cfg';
const GODOT_ERROR_PATTERN = /^\s*(SCRIPT ERROR|ERROR):/;
const PRESET_SECTION_PATTERN = /^\[preset\.(\d+)]$/;
const KEY_VALUE_PATTERN = /^([\w/]+)="(.*)"$/;

export function isGodotProject(cwd: string): boolean {
  return existsSync(join(cwd, GODOT_PROJECT_FILE));
}

export function hasGodotErrors(output: string): boolean {
  return output.split('\n').some((line) => GODOT_ERROR_PATTERN.test(line));
}

export function parseAndroidExportPreset(cfg: string): AndroidExportPreset | null {
  const presets: Record<string, string>[] = [];
  let current: Record<string, string> | null = null;
  for (const rawLine of cfg.split('\n')) {
    const line = rawLine.trim();
    if (line.startsWith('[')) {
      current = PRESET_SECTION_PATTERN.test(line) ? {} : null;
      if (current !== null) {
        presets.push(current);
      }
      continue;
    }
    const match = KEY_VALUE_PATTERN.exec(line);
    if (current !== null && match?.[1] !== undefined && match[2] !== undefined) {
      current[match[1]] = match[2];
    }
  }
  for (const preset of presets) {
    const { name, platform, export_path: exportPath } = preset;
    if (platform === 'Android' && name !== undefined && exportPath !== undefined && exportPath.length > 0) {
      return { name, exportPath };
    }
  }
  return null;
}

export function readAndroidExportPreset(cwd: string): AndroidExportPreset {
  const presetsFile = join(cwd, EXPORT_PRESETS_FILE);
  if (!existsSync(presetsFile)) {
    throw new Error(`${EXPORT_PRESETS_FILE} fehlt in ${cwd}.`);
  }
  const preset = parseAndroidExportPreset(readFileSync(presetsFile, 'utf8'));
  if (preset === null) {
    throw new Error(`Kein Android-Export-Preset mit export_path in ${presetsFile} gefunden.`);
  }
  return preset;
}

function compareVersionDirs(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true });
}

function findInstalledGodot(): string | null {
  const optDir = join(homedir(), '.local', 'opt');
  let entries: string[];
  try {
    entries = readdirSync(optDir);
  } catch {
    return null;
  }
  const versionDirs = entries.filter((entry) => entry.startsWith('godot-')).sort(compareVersionDirs).reverse();
  for (const versionDir of versionDirs) {
    const dir = join(optDir, versionDir);
    let files: string[];
    try {
      files = readdirSync(dir);
    } catch {
      continue;
    }
    const binary = files.find((file) => file.startsWith('Godot_') && !file.includes('_console'));
    if (binary !== undefined) {
      return join(dir, binary);
    }
  }
  return null;
}

export function resolveGodotExecutable(): string {
  const fromEnv = process.env[GODOT_BIN_ENV_VAR];
  if (fromEnv !== undefined && fromEnv.length > 0) {
    return fromEnv;
  }
  for (const name of ['godot', 'godot4']) {
    if (isOnPath(name)) {
      return name;
    }
  }
  return findInstalledGodot() ?? 'godot';
}

export function runGodotExport(
  cwd: string,
  buildType: GodotBuildType,
  godotExecutable: string,
  preset: AndroidExportPreset,
): Promise<CapturedProcessResult> {
  mkdirSync(dirname(resolvePath(cwd, preset.exportPath)), { recursive: true });
  const exportFlag = buildType === 'debug' ? '--export-debug' : '--export-release';
  return runCapturedProcess(
    godotExecutable,
    ['--headless', '--path', cwd, exportFlag, preset.name, preset.exportPath],
    cwd,
  );
}
