import { copyFileSync, existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { extname, join, resolve, sep } from 'node:path';

import type { CollectionEntry, Config } from './config.js';

export interface CollectResult {
  targetName: string;
  fileName: string;
  status: 'ok';
}

export interface CollectError {
  targetName: string;
  error: string;
}

export interface CollectSummary {
  results: CollectResult[];
  errors: CollectError[];
}

export interface CollectedFile {
  name: string;
  timestamp: string;
}

export function ensureContentDir(contentPath: string): void {
  mkdirSync(contentPath, { recursive: true });
}

function targetFileName(entry: CollectionEntry): string {
  const ext = extname(entry.sourcePath);
  return ext !== '' && !entry.targetName.endsWith(ext) ? `${entry.targetName}${ext}` : entry.targetName;
}

function collectEntry(entry: CollectionEntry, contentPath: string): CollectResult {
  if (!existsSync(entry.sourcePath) || !statSync(entry.sourcePath).isFile()) {
    throw new Error(`Quelldatei "${entry.sourcePath}" wurde nicht gefunden.`);
  }
  const fileName = targetFileName(entry);
  copyFileSync(entry.sourcePath, join(contentPath, fileName));
  return { targetName: entry.targetName, fileName, status: 'ok' };
}

export function collectAll(config: Config): CollectSummary {
  ensureContentDir(config.contentPath);
  const results: CollectResult[] = [];
  const errors: CollectError[] = [];
  for (const entry of config.collection) {
    try {
      results.push(collectEntry(entry, config.contentPath));
    } catch (error) {
      errors.push({
        targetName: entry.targetName,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  }
  return { results, errors };
}

export function collectOne(config: Config, targetName: string): CollectSummary {
  const entry = config.collection.find((candidate) => candidate.targetName === targetName);
  if (!entry) {
    throw new Error(`Collection-Eintrag "${targetName}" wurde in config.json nicht gefunden.`);
  }
  ensureContentDir(config.contentPath);
  try {
    return { results: [collectEntry(entry, config.contentPath)], errors: [] };
  } catch (error) {
    return {
      results: [],
      errors: [{ targetName, error: error instanceof Error ? error.message : String(error) }],
    };
  }
}

/** Neueste zuerst (mtime). */
export function listCollectedFiles(contentPath: string): CollectedFile[] {
  ensureContentDir(contentPath);
  return readdirSync(contentPath, { withFileTypes: true })
    .filter((dirent) => dirent.isFile())
    .map((dirent) => {
      const stat = statSync(join(contentPath, dirent.name));
      return { name: dirent.name, timestamp: stat.mtime.toISOString() };
    })
    .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
}

/** Wirft bei Pfad-Traversal-Versuchen (z. B. "..") - der aufgeloeste Pfad muss innerhalb von contentPath liegen. */
export function resolveCollectedFilePath(contentPath: string, name: string): string {
  const directory = resolve(contentPath);
  const filePath = resolve(join(directory, name));
  if (filePath !== directory && !filePath.startsWith(directory + sep)) {
    throw new Error('Ungueltiger Dateiname.');
  }
  return filePath;
}
