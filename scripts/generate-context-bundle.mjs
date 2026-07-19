import { existsSync, readdirSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const rootDir = dirname(dirname(fileURLToPath(import.meta.url)));
const contextsDir = join(rootDir, 'contexts');
const tasksDir = join(rootDir, 'tasks');
const configPath = join(rootDir, 'config.json');
const outDir = join(rootDir, 'src', 'generated');
const outFile = join(outDir, 'embedded-context.ts');

function collectMarkdownFiles(dir) {
  const files = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const fullPath = join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...collectMarkdownFiles(fullPath));
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      files.push(fullPath);
    }
  }
  return files;
}

function collectNamedFiles(dir) {
  if (!existsSync(dir)) {
    return {};
  }
  const result = {};
  for (const filePath of collectMarkdownFiles(dir)) {
    const name = relative(dir, filePath).replace(/\.md$/, '').split('\\').join('/');
    result[name] = readFileSync(filePath, 'utf8');
  }
  return result;
}

const config = JSON.parse(readFileSync(configPath, 'utf8'));
const contexts = collectNamedFiles(contextsDir);
const tasks = collectNamedFiles(tasksDir);

const body = `export const EMBEDDED_CONFIG: unknown = ${JSON.stringify(config)};

export const EMBEDDED_CONTEXTS: Record<string, string> = ${JSON.stringify(contexts)};

export const EMBEDDED_TASKS: Record<string, string> = ${JSON.stringify(tasks)};
`;

mkdirSync(outDir, { recursive: true });
writeFileSync(outFile, body);
