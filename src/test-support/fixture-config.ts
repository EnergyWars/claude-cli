import { dirname, join } from 'node:path';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';

export interface FixtureAgent {
  name: string;
  description?: string;
  contexts?: string[];
  model?: string;
}

export interface FixtureHostedEntry {
  name: string;
  path: string;
  type: 'path' | 'file';
}

export interface FixturePathCommandEntry {
  key: string;
  command: string;
  displayName: string;
  description: string;
}

export interface FixturePathEntry {
  name: string;
  path: string;
  hosted?: FixtureHostedEntry[];
  commands?: FixturePathCommandEntry[];
}

export interface FixtureTask {
  name: string;
  contexts?: string[];
  tasks?: string[];
  model?: string;
}

export interface FixtureConfigOptions {
  main?: { description?: string; contexts?: string[]; model?: string };
  agents?: FixtureAgent[];
  databaseDirectory?: string;
  contexts?: Record<string, string>;
  paths?: FixturePathEntry[];
  tasks?: FixtureTask[];
  taskFiles?: Record<string, string>;
}

export interface Fixture {
  rootDir: string;
  cleanup: () => void;
}

export function createFixtureRoot(options: FixtureConfigOptions = {}): Fixture {
  const rootDir = mkdtempSync(join(tmpdir(), 'cl-fixture-'));

  const config = {
    main: {
      description: 'Test-Main-Agent',
      contexts: ['main'],
      model: 'sonnet',
      ...options.main,
    },
    agents: (options.agents ?? []).map((agent) => ({
      description: 'Test-Agent',
      contexts: ['main'],
      model: 'sonnet',
      ...agent,
    })),
    databaseDirectory: options.databaseDirectory ?? join(rootDir, 'db'),
    paths: options.paths ?? [{ name: 'default', path: rootDir }],
    tasks: (options.tasks ?? []).map((task) => ({
      contexts: ['main'],
      tasks: [],
      model: 'sonnet',
      ...task,
    })),
  };
  writeFileSync(join(rootDir, 'config.json'), JSON.stringify(config, null, 2));

  const contexts = options.contexts ?? { main: '# Test-Main-Context\n' };
  for (const [name, content] of Object.entries(contexts)) {
    const filePath = join(rootDir, 'contexts', `${name}.md`);
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, content);
  }

  for (const [name, content] of Object.entries(options.taskFiles ?? {})) {
    const filePath = join(rootDir, 'tasks', `${name}.md`);
    mkdirSync(dirname(filePath), { recursive: true });
    writeFileSync(filePath, content);
  }

  return {
    rootDir,
    cleanup: () => {
      rmSync(rootDir, { recursive: true, force: true });
    },
  };
}

export function createEmptyFixtureRoot(): Fixture {
  const rootDir = mkdtempSync(join(tmpdir(), 'cl-fixture-empty-'));
  return {
    rootDir,
    cleanup: () => {
      rmSync(rootDir, { recursive: true, force: true });
    },
  };
}
