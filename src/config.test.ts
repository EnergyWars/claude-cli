import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import {
  applyPathsOverride,
  ensureConfigBootstrapped,
  listAgents,
  listHostedNames,
  listHostedSummaries,
  listPathCommands,
  listPathNames,
  listTasks,
  loadConfig,
  loadPathsOverride,
  parseConfig,
  parsePathsOverride,
  resolveAgent,
  resolveAgentFrom,
  resolveContext,
  resolveEffectiveConfig,
  resolveHostedEntry,
  resolvePath,
  resolvePathCommand,
  resolveTask,
  type Config,
} from './config.js';
import { getConfigPointer, openDatabase, setConfigPointer } from './db.js';
import { EMBEDDED_CONFIG, EMBEDDED_CONTEXTS } from './generated/embedded-context.js';
import { createEmptyFixtureRoot, createFixtureRoot } from './test-support/fixture-config.js';

function validRawConfig(): unknown {
  return {
    main: { description: 'Main', contexts: ['main'], model: 'sonnet' },
    agents: [{ name: 'dev', description: 'Dev', contexts: ['main'], model: 'sonnet' }],
    databaseDirectory: '/tmp/does-not-matter',
    paths: [{ name: 'myapp', path: '/my/path' }],
    tasks: [
      {
        name: 'cleanup',
        description: 'Cleanup',
        contexts: ['main'],
        model: 'sonnet',
        startCommand: 'raeum auf',
      },
    ],
    ticketAgent: { model: 'haiku', task: 'Erstelle ein Ticket aus dem Text.' },
    contentPath: '/tmp/content',
    collection: [{ sourcePath: '/tmp/source.apk', targetName: 'test', path: 'myapp' }],
  };
}

test('parseConfig: akzeptiert ein vollstaendiges gueltiges Objekt', () => {
  const parsed = parseConfig(validRawConfig());
  assert.equal(parsed.main.description, 'Main');
  assert.equal(parsed.agents.length, 1);
  assert.equal(parsed.agents[0]?.name, 'dev');
  assert.equal(parsed.databaseDirectory, '/tmp/does-not-matter');
});

test('parseConfig: wirft ohne Feld "main"', () => {
  const raw = validRawConfig() as Record<string, unknown>;
  delete raw.main;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft ohne Feld "agents"', () => {
  const raw = validRawConfig() as Record<string, unknown>;
  delete raw.agents;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft ohne Feld "databaseDirectory"', () => {
  const raw = validRawConfig() as Record<string, unknown>;
  delete raw.databaseDirectory;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft ohne Feld "paths"', () => {
  const raw = validRawConfig() as Record<string, unknown>;
  delete raw.paths;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Path-Eintrag "path" fehlt', () => {
  const raw = validRawConfig() as { paths: Record<string, unknown>[] };
  delete raw.paths[0]?.path;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: akzeptiert einen Path-Eintrag mit "hosted"', () => {
  const raw = validRawConfig() as { paths: Record<string, unknown>[] };
  raw.paths[0] = {
    ...raw.paths[0],
    hosted: [
      { name: 'notes', path: '/my/path/notes.txt', type: 'file' },
      { name: 'docs', path: '/my/path/docs', type: 'path' },
    ],
  };
  const parsed = parseConfig(raw);
  assert.equal(parsed.paths[0]?.hosted?.length, 2);
});

test('parseConfig: wirft wenn ein Hosted-Eintrag "type" fehlt', () => {
  const raw = validRawConfig() as { paths: Record<string, unknown>[] };
  raw.paths[0] = { ...raw.paths[0], hosted: [{ name: 'notes', path: '/my/path/notes.txt' }] };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Hosted-Eintrag "type" ungueltig ist', () => {
  const raw = validRawConfig() as { paths: Record<string, unknown>[] };
  raw.paths[0] = {
    ...raw.paths[0],
    hosted: [{ name: 'notes', path: '/my/path/notes.txt', type: 'invalid' }],
  };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: akzeptiert einen Path-Eintrag mit "commands"', () => {
  const raw = validRawConfig() as { paths: Record<string, unknown>[] };
  raw.paths[0] = {
    ...raw.paths[0],
    commands: [
      {
        key: 'build',
        command: 'npm run build',
        displayName: 'Build',
        description: 'Baut das Projekt',
      },
    ],
  };
  const parsed = parseConfig(raw);
  assert.equal(parsed.paths[0]?.commands?.length, 1);
});

test('parseConfig: wirft wenn ein Command-Eintrag "key" fehlt', () => {
  const raw = validRawConfig() as { paths: Record<string, unknown>[] };
  raw.paths[0] = {
    ...raw.paths[0],
    commands: [{ command: 'npm run build', displayName: 'Build', description: 'x' }],
  };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Command-Eintrag "command" leer ist', () => {
  const raw = validRawConfig() as { paths: Record<string, unknown>[] };
  raw.paths[0] = {
    ...raw.paths[0],
    commands: [{ key: 'build', command: '  ', displayName: 'Build', description: 'x' }],
  };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Command-Eintrag "description" fehlt', () => {
  const raw = validRawConfig() as { paths: Record<string, unknown>[] };
  raw.paths[0] = {
    ...raw.paths[0],
    commands: [{ key: 'build', command: 'npm run build', displayName: 'Build' }],
  };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft bei Agent-Namen "totp"', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'totp';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

test('parseConfig: wirft ohne Feld "tasks"', () => {
  const raw = validRawConfig() as Record<string, unknown>;
  delete raw.tasks;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Task-Eintrag "startCommand" fehlt', () => {
  const raw = validRawConfig() as { tasks: Record<string, unknown>[] };
  delete raw.tasks[0]?.startCommand;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Task-Eintrag "startCommand" leer ist', () => {
  const raw = validRawConfig() as { tasks: Record<string, unknown>[] };
  raw.tasks[0] = { ...raw.tasks[0], startCommand: '   ' };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Task-Eintrag "model" fehlt', () => {
  const raw = validRawConfig() as { tasks: Record<string, unknown>[] };
  delete raw.tasks[0]?.model;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: akzeptiert einen Task-Eintrag mit "permissions"', () => {
  const raw = validRawConfig() as { tasks: Record<string, unknown>[] };
  raw.tasks[0] = { ...raw.tasks[0], permissions: ['Bash(gradle *)', 'Bash(./gradlew *)'] };
  const parsed = parseConfig(raw);
  assert.deepEqual(parsed.tasks[0]?.permissions, ['Bash(gradle *)', 'Bash(./gradlew *)']);
});

test('parseConfig: wirft wenn ein Task-Eintrag "permissions" kein String-Array ist', () => {
  const raw = validRawConfig() as { tasks: Record<string, unknown>[] };
  raw.tasks[0] = { ...raw.tasks[0], permissions: ['Bash(gradle *)', 42] };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft ohne Feld "ticketAgent"', () => {
  const raw = validRawConfig() as Record<string, unknown>;
  delete raw.ticketAgent;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn "ticketAgent.model" fehlt', () => {
  const raw = validRawConfig() as { ticketAgent: Record<string, unknown> };
  delete raw.ticketAgent.model;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn "ticketAgent.task" fehlt', () => {
  const raw = validRawConfig() as { ticketAgent: Record<string, unknown> };
  delete raw.ticketAgent.task;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn "ticketAgent.task" leer ist', () => {
  const raw = validRawConfig() as { ticketAgent: Record<string, unknown> };
  raw.ticketAgent.task = '   ';
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn "ticketAgent.model" leer ist', () => {
  const raw = validRawConfig() as { ticketAgent: Record<string, unknown> };
  raw.ticketAgent.model = '';
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft ohne Feld "contentPath"', () => {
  const raw = validRawConfig() as Record<string, unknown>;
  delete raw.contentPath;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft ohne Feld "collection"', () => {
  const raw = validRawConfig() as Record<string, unknown>;
  delete raw.collection;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: akzeptiert ein leeres "collection"-Array', () => {
  const raw = validRawConfig() as { collection: unknown };
  raw.collection = [];
  const parsed = parseConfig(raw);
  assert.deepEqual(parsed.collection, []);
});

test('parseConfig: wirft wenn ein Collection-Eintrag "sourcePath" fehlt', () => {
  const raw = validRawConfig() as { collection: Record<string, unknown>[] };
  raw.collection[0] = { targetName: 'test' };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Collection-Eintrag "targetName" leer ist', () => {
  const raw = validRawConfig() as { collection: Record<string, unknown>[] };
  raw.collection[0] = { sourcePath: '/tmp/source.apk', targetName: '  ', path: 'myapp' };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Collection-Eintrag "path" fehlt', () => {
  const raw = validRawConfig() as { collection: Record<string, unknown>[] };
  raw.collection[0] = { sourcePath: '/tmp/source.apk', targetName: 'test' };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Collection-Eintrag "path" leer ist', () => {
  const raw = validRawConfig() as { collection: Record<string, unknown>[] };
  raw.collection[0] = { sourcePath: '/tmp/source.apk', targetName: 'test', path: '  ' };
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft bei Agent-Namen "collect"', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'collect';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

function firstAgentOf(raw: unknown): Record<string, unknown> {
  const record = raw as { agents: Record<string, unknown>[] };
  const [firstAgent] = record.agents;
  assert.ok(firstAgent);
  return firstAgent;
}

test('parseConfig: wirft wenn ein Agent "model" fehlt', () => {
  const raw = validRawConfig();
  delete firstAgentOf(raw).model;
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft wenn ein Agent "contexts" kein String-Array ist', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).contexts = ['main', 42];
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: akzeptiert einen Agent mit "permissions"', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).permissions = ['Bash(gradle *)'];
  const parsed = parseConfig(raw);
  assert.deepEqual(parsed.agents[0]?.permissions, ['Bash(gradle *)']);
});

test('parseConfig: wirft wenn ein Agent "permissions" kein String-Array ist', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).permissions = 'Bash(gradle *)';
  assert.throws(() => parseConfig(raw), /Ungueltige config\.json/);
});

test('parseConfig: wirft bei reserviertem Agent-Namen (Model-Command)', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'opus';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

test('parseConfig: wirft bei reservierter Model-Command-Kurzform', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'h';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

test('parseConfig: wirft bei Agent-Namen "server"', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'server';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

test('parseConfig: wirft bei Agent-Namen "task"', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'task';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

test('parseConfig: wirft bei Agent-Namen "inst"', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'inst';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

test('parseConfig: wirft bei Agent-Namen "instr"', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'instr';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

test('parseConfig: wirft bei Agent-Namen "ticket"', () => {
  const raw = validRawConfig();
  firstAgentOf(raw).name = 'ticket';
  assert.throws(() => parseConfig(raw), /reservierten Commands/);
});

test('listAgents: main + jeder Agent als "cl <name>" mit description', () => {
  const config: Config = {
    main: { description: 'Main-Desc', contexts: ['main'], model: 'sonnet' },
    agents: [
      { name: 'dev', description: 'Dev-Desc', contexts: ['main'], model: 'sonnet' },
      { name: 'iwan', description: 'Iwan-Desc', contexts: ['main'], model: 'opus' },
    ],
    databaseDirectory: '/tmp/x',
    paths: [],
    tasks: [],
    ticketAgent: { model: 'haiku', task: 'Test-Task' },
    contentPath: '/tmp/content',
    collection: [],
  };
  assert.deepEqual(listAgents(config), [
    { command: 'cl', description: 'Main-Desc' },
    { command: 'cl dev', description: 'Dev-Desc' },
    { command: 'cl iwan', description: 'Iwan-Desc' },
  ]);
});

test('loadConfig/resolveAgent/resolveContext: lokal-first ueber CL_ROOT_DIR-Fixture', () => {
  const fixture = createFixtureRoot({
    main: { description: 'Fixture-Main' },
    agents: [{ name: 'dev', description: 'Fixture-Dev' }],
    contexts: { main: '# Fixture Main Content\n' },
    paths: [{ name: 'myapp', path: '/my/path' }],
  });
  const previous = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  try {
    const config = loadConfig();
    assert.equal(config.main.description, 'Fixture-Main');
    assert.equal(config.agents[0]?.name, 'dev');

    assert.equal(resolveContext('main'), '# Fixture Main Content\n');

    const mainAgent = resolveAgent(undefined);
    assert.equal(mainAgent.description, 'Fixture-Main');

    const devAgent = resolveAgent('dev');
    assert.equal(devAgent.description, 'Fixture-Dev');

    assert.throws(() => resolveAgent('doesnotexist'), /wurde in config\.json nicht gefunden/);
    assert.throws(() => resolveContext('doesnotexist'), /wurde nicht gefunden/);

    assert.equal(resolvePath(config, 'myapp'), '/my/path');
    assert.throws(
      () => resolvePath(config, 'doesnotexist'),
      /wurde in config\.json nicht gefunden/,
    );
    assert.deepEqual(listPathNames(config), ['myapp']);
  } finally {
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    fixture.cleanup();
  }
});

test('listHostedNames/resolveHostedEntry: liefert Hosted-Eintraege eines Pfads', () => {
  const config: Config = {
    main: { description: 'Main', contexts: ['main'], model: 'sonnet' },
    agents: [],
    databaseDirectory: '/tmp/x',
    paths: [
      {
        name: 'myapp',
        path: '/my/path',
        hosted: [
          { name: 'notes', path: 'notes.txt', type: 'file' },
          { name: 'docs', path: 'docs', type: 'path' },
        ],
      },
      { name: 'empty', path: '/empty/path' },
    ],
    tasks: [],
    ticketAgent: { model: 'haiku', task: 'Test-Task' },
    contentPath: '/tmp/content',
    collection: [],
  };

  assert.deepEqual(listHostedNames(config, 'myapp'), ['notes', 'docs']);
  assert.deepEqual(listHostedNames(config, 'empty'), []);
  assert.throws(
    () => listHostedNames(config, 'doesnotexist'),
    /wurde in config\.json nicht gefunden/,
  );

  assert.deepEqual(resolveHostedEntry(config, 'myapp', 'notes'), {
    name: 'notes',
    path: '/my/path/notes.txt',
    type: 'file',
  });
  assert.throws(
    () => resolveHostedEntry(config, 'myapp', 'doesnotexist'),
    /Hosted-Eintrag "doesnotexist" wurde in Pfad "myapp" nicht gefunden/,
  );
  assert.throws(
    () => resolveHostedEntry(config, 'doesnotexist', 'notes'),
    /wurde in config\.json nicht gefunden/,
  );
});

test('listHostedSummaries: liefert Name + Typ der Hosted-Eintraege eines Pfads', () => {
  const config: Config = {
    main: { description: 'Main', contexts: ['main'], model: 'sonnet' },
    agents: [],
    databaseDirectory: '/tmp/x',
    paths: [
      {
        name: 'myapp',
        path: '/my/path',
        hosted: [
          { name: 'notes', path: 'notes.txt', type: 'file' },
          { name: 'docs', path: 'docs', type: 'path' },
        ],
      },
      { name: 'empty', path: '/empty/path' },
    ],
    tasks: [],
    ticketAgent: { model: 'haiku', task: 'Test-Task' },
    contentPath: '/tmp/content',
    collection: [],
  };

  assert.deepEqual(listHostedSummaries(config, 'myapp'), [
    { name: 'notes', type: 'file' },
    { name: 'docs', type: 'path' },
  ]);
  assert.deepEqual(listHostedSummaries(config, 'empty'), []);
  assert.throws(
    () => listHostedSummaries(config, 'doesnotexist'),
    /wurde in config\.json nicht gefunden/,
  );
});

test('listPathCommands/resolvePathCommand: liefert Commands eines Pfads', () => {
  const config: Config = {
    main: { description: 'Main', contexts: ['main'], model: 'sonnet' },
    agents: [],
    databaseDirectory: '/tmp/x',
    paths: [
      {
        name: 'myapp',
        path: '/my/path',
        commands: [
          { key: 'build', command: 'npm run build', displayName: 'Build', description: 'Baut' },
          { key: 'test', command: 'npm test', displayName: 'Test', description: 'Testet' },
        ],
      },
      { name: 'empty', path: '/empty/path' },
    ],
    tasks: [],
    ticketAgent: { model: 'haiku', task: 'Test-Task' },
    contentPath: '/tmp/content',
    collection: [],
  };

  assert.deepEqual(listPathCommands(config, 'myapp'), [
    { key: 'build', command: 'npm run build', displayName: 'Build', description: 'Baut' },
    { key: 'test', command: 'npm test', displayName: 'Test', description: 'Testet' },
  ]);
  assert.deepEqual(listPathCommands(config, 'empty'), []);
  assert.throws(
    () => listPathCommands(config, 'doesnotexist'),
    /wurde in config\.json nicht gefunden/,
  );

  assert.deepEqual(resolvePathCommand(config, 'myapp', 'build'), {
    key: 'build',
    command: 'npm run build',
    displayName: 'Build',
    description: 'Baut',
  });
  assert.throws(
    () => resolvePathCommand(config, 'myapp', 'doesnotexist'),
    /Command "doesnotexist" wurde in Pfad "myapp" nicht gefunden/,
  );
  assert.throws(
    () => resolvePathCommand(config, 'doesnotexist', 'build'),
    /wurde in config\.json nicht gefunden/,
  );
});

test('resolveTask: lokal-first ueber CL_ROOT_DIR-Fixture', () => {
  const fixture = createFixtureRoot({
    contexts: { main: '# Fixture Main Content\n' },
    tasks: [
      {
        name: 'cleanup',
        description: 'Cleanup',
        contexts: ['main'],
        model: 'opus',
        startCommand: 'raeum auf',
      },
    ],
  });
  const previous = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  try {
    const config = loadConfig();
    const task = resolveTask(config, 'cleanup');
    assert.equal(task.model, 'opus');
    assert.deepEqual(task.contexts, ['main']);
    assert.equal(task.startCommand, 'raeum auf');

    assert.throws(
      () => resolveTask(config, 'doesnotexist'),
      /wurde in config\.json nicht gefunden/,
    );
  } finally {
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    fixture.cleanup();
  }
});

test('loadConfig/resolveContext: faellt ohne lokale Dateien auf embedded zurueck', () => {
  const fixture = createEmptyFixtureRoot();
  const previous = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  try {
    assert.deepEqual(loadConfig(), EMBEDDED_CONFIG);
    assert.equal(resolveContext('main'), EMBEDDED_CONTEXTS.main);
  } finally {
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    fixture.cleanup();
  }
});

test('parsePathsOverride: akzeptiert { paths: [...] }', () => {
  const paths = parsePathsOverride({ paths: [{ name: 'override', path: '/override/path' }] });
  assert.deepEqual(paths, [{ name: 'override', path: '/override/path' }]);
});

test('parsePathsOverride: wirft ohne Feld "paths"', () => {
  assert.throws(() => parsePathsOverride({}), /Ungueltige Paths-Datei/);
});

test('parsePathsOverride: wirft wenn ein Eintrag "path" fehlt', () => {
  assert.throws(() => parsePathsOverride({ paths: [{ name: 'x' }] }), /Ungueltige Paths-Datei/);
});

test('loadPathsOverride/applyPathsOverride: liest Datei und ersetzt config.paths vollstaendig', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-paths-override-'));
  const filePath = join(dir, 'paths.json');
  writeFileSync(filePath, JSON.stringify({ paths: [{ name: 'override', path: '/override' }] }));
  try {
    const paths = loadPathsOverride(filePath);
    assert.deepEqual(paths, [{ name: 'override', path: '/override' }]);

    const config: Config = {
      main: { description: 'Main', contexts: ['main'], model: 'sonnet' },
      agents: [],
      databaseDirectory: '/tmp/x',
      paths: [{ name: 'original', path: '/original' }],
      tasks: [],
      ticketAgent: { model: 'haiku', task: 'Test-Task' },
      contentPath: '/tmp/content',
      collection: [],
    };
    const overridden = applyPathsOverride(config, paths);
    assert.deepEqual(overridden.paths, [{ name: 'override', path: '/override' }]);
    assert.equal(overridden.main.description, 'Main');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('loadConfig: bricht bei reserviertem Agent-Namen in lokaler config.json ab', () => {
  const fixture = createFixtureRoot({ agents: [{ name: 'sonnet' }] });
  const previous = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  try {
    assert.throws(() => loadConfig(), /reservierten Commands/);
  } finally {
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    fixture.cleanup();
  }
});

test('listTasks: jeder Task als "cl task <name>" mit description', () => {
  const config: Config = {
    main: { description: 'm', contexts: [], model: 'sonnet' },
    agents: [],
    databaseDirectory: '/tmp/db',
    paths: [],
    tasks: [
      { name: 'a', description: 'A-Desc', contexts: [], model: 'sonnet', startCommand: 'x' },
      { name: 'b', description: 'B-Desc', contexts: [], model: 'opus', startCommand: 'y' },
    ],
    ticketAgent: { model: 'haiku', task: 'Test-Task' },
    contentPath: '/tmp/content',
    collection: [],
  };
  assert.deepEqual(listTasks(config), [
    { command: 'cl task a', description: 'A-Desc' },
    { command: 'cl task b', description: 'B-Desc' },
  ]);
});

test('resolveAgentFrom: liefert main ohne Namen, Agent per Namen, wirft bei unbekanntem Namen', () => {
  const config: Config = {
    main: { description: 'Main-Desc', contexts: [], model: 'sonnet' },
    agents: [{ name: 'dev', description: 'Dev-Desc', contexts: [], model: 'sonnet' }],
    databaseDirectory: '/tmp/db',
    paths: [],
    tasks: [],
    ticketAgent: { model: 'haiku', task: 'x' },
    contentPath: '/tmp/content',
    collection: [],
  };
  assert.equal(resolveAgentFrom(config, undefined).description, 'Main-Desc');
  assert.equal(resolveAgentFrom(config, 'dev').description, 'Dev-Desc');
  assert.throws(
    () => resolveAgentFrom(config, 'doesnotexist'),
    /wurde in config\.json nicht gefunden/,
  );
});

test('ensureConfigBootstrapped + resolveEffectiveConfig: importiert lokale config.json als Version 1 und setzt den Zeiger darauf', () => {
  const fixture = createFixtureRoot({
    main: { description: 'Fixture-Main' },
    agents: [{ name: 'dev', description: 'Fixture-Dev' }],
  });
  const dbDir = mkdtempSync(join(tmpdir(), 'cl-config-db-'));
  const previous = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  const db = openDatabase(dbDir);
  try {
    assert.equal(getConfigPointer(db), undefined);

    ensureConfigBootstrapped(db);

    const pointer = getConfigPointer(db);
    assert.equal(pointer?.versionId, 1);

    const effective = resolveEffectiveConfig(db);
    assert.equal(effective.main.description, 'Fixture-Main');
    assert.equal(effective.agents[0]?.name, 'dev');

    ensureConfigBootstrapped(db);
    assert.equal(getConfigPointer(db)?.versionId, 1);
  } finally {
    db.close();
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    fixture.cleanup();
    rmSync(dbDir, { recursive: true, force: true });
  }
});

test('ensureConfigBootstrapped: ohne lokale config.json wird die embedded Config als Version 1 uebernommen', () => {
  const fixture = createEmptyFixtureRoot();
  const dbDir = mkdtempSync(join(tmpdir(), 'cl-config-db-'));
  const previous = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  const db = openDatabase(dbDir);
  try {
    ensureConfigBootstrapped(db);
    const effective = resolveEffectiveConfig(db);
    assert.deepEqual(effective, parseConfig(EMBEDDED_CONFIG));
  } finally {
    db.close();
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    fixture.cleanup();
    rmSync(dbDir, { recursive: true, force: true });
  }
});

test('resolveEffectiveConfig: Zeiger auf null liefert die fest reinkompilierte Version', () => {
  const dbDir = mkdtempSync(join(tmpdir(), 'cl-config-db-'));
  const db = openDatabase(dbDir);
  try {
    setConfigPointer(db, null);
    assert.deepEqual(resolveEffectiveConfig(db), parseConfig(EMBEDDED_CONFIG));
  } finally {
    db.close();
    rmSync(dbDir, { recursive: true, force: true });
  }
});

test('resolveEffectiveConfig: wirft, wenn der Zeiger auf eine nicht existierende Version zeigt', () => {
  const dbDir = mkdtempSync(join(tmpdir(), 'cl-config-db-'));
  const db = openDatabase(dbDir);
  try {
    setConfigPointer(db, 999);
    assert.throws(() => resolveEffectiveConfig(db), /Config-Version 999 wurde in der Datenbank nicht gefunden/);
  } finally {
    db.close();
    rmSync(dbDir, { recursive: true, force: true });
  }
});
