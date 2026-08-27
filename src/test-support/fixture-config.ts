import { dirname, join } from 'node:path';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';

export interface FixtureAgent {
  name: string;
  description?: string;
  contexts?: string[];
  model?: string;
  permissions?: string[];
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
  description?: string;
  contexts?: string[];
  model?: string;
  startCommand?: string;
  permissions?: string[];
}

export interface FixtureTicketAgent {
  model?: string;
  task?: string;
}

export interface FixtureCollectionEntry {
  sourcePath: string;
  targetName: string;
  path: string;
}

export interface FixtureConfigOptions {
  main?: { description?: string; contexts?: string[]; model?: string; permissions?: string[] };
  agents?: FixtureAgent[];
  databaseDirectory?: string;
  contexts?: Record<string, string>;
  paths?: FixturePathEntry[];
  tasks?: FixtureTask[];
  ticketAgent?: FixtureTicketAgent;
  contentPath?: string;
  collection?: FixtureCollectionEntry[];
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
      description: 'Test-Task',
      contexts: ['main'],
      model: 'sonnet',
      startCommand: 'mach was',
      ...task,
    })),
    ticketAgent: {
      model: 'haiku',
      task: 'Test-Ticket-Agent-Aufgabe',
      ...options.ticketAgent,
    },
    contentPath: options.contentPath ?? join(rootDir, 'content'),
    collection: options.collection ?? [],
  };
  writeFileSync(join(rootDir, 'config.json'), JSON.stringify(config, null, 2));

  const contexts = options.contexts ?? { main: '# Test-Main-Context\n' };
  for (const [name, content] of Object.entries(contexts)) {
    const filePath = join(rootDir, 'contexts', `${name}.md`);
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
