import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { getRootDir } from './config.js';
import { EMBEDDED_ENV_FILE } from './generated/embedded-context.js';

export const DATABASE_DIRECTORY_ENV_VAR = 'CL_DATABASE_DIR';

export function parseEnvFile(content: string): Record<string, string> {
  const result: Record<string, string> = {};
  for (const rawLine of content.split('\n')) {
    const line = rawLine.trim();
    if (line === '' || line.startsWith('#')) {
      continue;
    }
    const separatorIndex = line.indexOf('=');
    if (separatorIndex === -1) {
      continue;
    }
    const key = line.slice(0, separatorIndex).trim();
    if (key === '') {
      continue;
    }
    let value = line.slice(separatorIndex + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    result[key] = value;
  }
  return result;
}

function readLocalEnvFile(rootDir: string): string | undefined {
  try {
    return readFileSync(join(rootDir, '.env'), 'utf8');
  } catch {
    return undefined;
  }
}

/**
 * Lokal-first wie loadConfig()/resolveContext(): eine .env im Projekt-Root ersetzt die beim Build
 * eingebettete .env vollstaendig (kein Feld-Merge), echte Umgebungsvariablen ueberschreiben in
 * jedem Fall beide (z. B. "PORT=7765 cl server").
 */
export function loadEnv(
  env: NodeJS.ProcessEnv = process.env,
  rootDir: string = getRootDir(),
): Record<string, string> {
  const fileContent = readLocalEnvFile(rootDir) ?? EMBEDDED_ENV_FILE;
  const merged = parseEnvFile(fileContent);
  for (const [key, value] of Object.entries(env)) {
    if (value !== undefined) {
      merged[key] = value;
    }
  }
  return merged;
}

export function resolveDatabaseDirectory(env: NodeJS.ProcessEnv = process.env): string {
  const value = loadEnv(env)[DATABASE_DIRECTORY_ENV_VAR];
  if (value === undefined || value.trim() === '') {
    throw new Error(
      `Umgebungsvariable ${DATABASE_DIRECTORY_ENV_VAR} ist nicht gesetzt (per .env-Datei im Projekt-Root oder als echte Umgebungsvariable konfigurierbar).`,
    );
  }
  return value;
}
