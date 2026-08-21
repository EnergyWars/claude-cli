import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { EMBEDDED_CONFIG, EMBEDDED_CONTEXTS } from './generated/embedded-context.js';

export interface AgentDefinition {
  description: string;
  contexts: string[];
  model: string;
}

export interface AgentConfig extends AgentDefinition {
  name: string;
}

export interface HostedEntry {
  name: string;
  path: string;
  type: 'path' | 'file';
}

export interface PathCommandEntry {
  key: string;
  command: string;
  displayName: string;
  description: string;
}

export interface PathEntry {
  name: string;
  path: string;
  hosted?: HostedEntry[];
  commands?: PathCommandEntry[];
}

export interface TaskDefinition {
  description: string;
  contexts: string[];
  model: string;
  startCommand: string;
}

export interface TaskConfig extends TaskDefinition {
  name: string;
}

export interface TicketAgentConfig {
  model: string;
  task: string;
}

export interface Config {
  main: AgentDefinition;
  agents: AgentConfig[];
  databaseDirectory: string;
  paths: PathEntry[];
  tasks: TaskConfig[];
  ticketAgent: TicketAgentConfig;
}

export const MODEL_COMMANDS = [
  { name: 'haiku', alias: 'h' },
  { name: 'sonnet', alias: 's' },
  { name: 'opus', alias: 'o' },
  { name: 'fable', alias: 'f' },
] as const;

const RESERVED_COMMAND_NAMES = new Set<string>([
  ...MODEL_COMMANDS.flatMap((model) => [model.name, model.alias]),
  'server',
  'task',
  'totp',
  'inst',
  'instr',
  'ticket',
]);

const defaultRootDir = join(dirname(fileURLToPath(import.meta.url)), '..');

/** Overridable via CL_ROOT_DIR (tests only) to point local-file resolution at a fixture directory. */
function getRootDir(): string {
  return process.env.CL_ROOT_DIR ?? defaultRootDir;
}

function readLocalFile(relativePath: string): string | undefined {
  try {
    return readFileSync(join(getRootDir(), relativePath), 'utf8');
  } catch {
    return undefined;
  }
}

function isAgentDefinition(value: unknown): value is AgentDefinition {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.description === 'string' &&
    typeof record.model === 'string' &&
    Array.isArray(record.contexts) &&
    record.contexts.every((entry) => typeof entry === 'string')
  );
}

function isAgentConfig(value: unknown): value is AgentConfig {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return typeof record.name === 'string' && isAgentDefinition(value);
}

function isHostedEntry(value: unknown): value is HostedEntry {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.name === 'string' &&
    typeof record.path === 'string' &&
    (record.type === 'path' || record.type === 'file')
  );
}

function isPathCommandEntry(value: unknown): value is PathCommandEntry {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.key === 'string' &&
    record.key.trim() !== '' &&
    typeof record.command === 'string' &&
    record.command.trim() !== '' &&
    typeof record.displayName === 'string' &&
    typeof record.description === 'string'
  );
}

function isPathEntry(value: unknown): value is PathEntry {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.name === 'string' &&
    typeof record.path === 'string' &&
    (record.hosted === undefined ||
      (Array.isArray(record.hosted) && record.hosted.every(isHostedEntry))) &&
    (record.commands === undefined ||
      (Array.isArray(record.commands) && record.commands.every(isPathCommandEntry)))
  );
}

function isTaskDefinition(value: unknown): value is TaskDefinition {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.description === 'string' &&
    typeof record.model === 'string' &&
    Array.isArray(record.contexts) &&
    record.contexts.every((entry) => typeof entry === 'string') &&
    typeof record.startCommand === 'string' &&
    record.startCommand.trim() !== ''
  );
}

function isTaskConfig(value: unknown): value is TaskConfig {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return typeof record.name === 'string' && isTaskDefinition(value);
}

function isTicketAgentConfig(value: unknown): value is TicketAgentConfig {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.model === 'string' &&
    record.model.trim() !== '' &&
    typeof record.task === 'string' &&
    record.task.trim() !== ''
  );
}

function isConfig(value: unknown): value is Config {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    isAgentDefinition(record.main) &&
    Array.isArray(record.agents) &&
    record.agents.every(isAgentConfig) &&
    typeof record.databaseDirectory === 'string' &&
    Array.isArray(record.paths) &&
    record.paths.every(isPathEntry) &&
    Array.isArray(record.tasks) &&
    record.tasks.every(isTaskConfig) &&
    isTicketAgentConfig(record.ticketAgent)
  );
}

function assertNoReservedAgentNames(config: Config): void {
  const conflicts = config.agents.filter((agent) => RESERVED_COMMAND_NAMES.has(agent.name));
  if (conflicts.length > 0) {
    const names = conflicts.map((agent) => `"${agent.name}"`).join(', ');
    const reserved = [...RESERVED_COMMAND_NAMES].join(', ');
    throw new Error(
      `Ungueltige config.json: Agent-Name(n) ${names} kollidieren mit reservierten Commands (${reserved}).`,
    );
  }
}

export function parseConfig(raw: unknown): Config {
  if (!isConfig(raw)) {
    throw new Error(
      'Ungueltige config.json: Feld "main" (Objekt), "agents" (Array), "databaseDirectory" (String), "paths" (Array von { name, path, hosted?, commands? }, wobei hosted ein Array von { name, path, type: "path"|"file" } und commands ein Array von { key, command, displayName, description } ist), "tasks" (Array von { name, description, contexts, model, startCommand }) oder "ticketAgent" (Objekt { model, task }) fehlt oder ist fehlerhaft.',
    );
  }
  assertNoReservedAgentNames(raw);
  return raw;
}

export function loadConfig(): Config {
  const local = readLocalFile('config.json');
  return parseConfig(local === undefined ? EMBEDDED_CONFIG : (JSON.parse(local) as unknown));
}

export function resolveAgent(name: string | undefined): AgentDefinition {
  const config = loadConfig();
  if (name === undefined) {
    return config.main;
  }
  const agent = config.agents.find((entry) => entry.name === name);
  if (!agent) {
    throw new Error(`Agent "${name}" wurde in config.json nicht gefunden.`);
  }
  return agent;
}

export interface AgentSummary {
  command: string;
  description: string;
}

export function listAgents(config: Config): AgentSummary[] {
  return [
    { command: 'cl', description: config.main.description },
    ...config.agents.map((agent) => ({
      command: `cl ${agent.name}`,
      description: agent.description,
    })),
  ];
}

export function listTasks(config: Config): AgentSummary[] {
  return config.tasks.map((task) => ({
    command: `cl task ${task.name}`,
    description: task.description,
  }));
}

export function resolvePathEntry(config: Config, name: string): PathEntry {
  const entry = config.paths.find((candidate) => candidate.name === name);
  if (!entry) {
    throw new Error(`Pfad "${name}" wurde in config.json nicht gefunden.`);
  }
  return entry;
}

export function resolvePath(config: Config, name: string): string {
  return resolvePathEntry(config, name).path;
}

export function listPathNames(config: Config): string[] {
  return config.paths.map((entry) => entry.name);
}

export function listHostedNames(config: Config, pathName: string): string[] {
  const entry = resolvePathEntry(config, pathName);
  return (entry.hosted ?? []).map((hosted) => hosted.name);
}

export interface HostedSummary {
  name: string;
  type: 'path' | 'file';
}

export function listHostedSummaries(config: Config, pathName: string): HostedSummary[] {
  const entry = resolvePathEntry(config, pathName);
  return (entry.hosted ?? []).map((hosted) => ({ name: hosted.name, type: hosted.type }));
}

export function resolveHostedEntry(
  config: Config,
  pathName: string,
  hostedName: string,
): HostedEntry {
  const entry = resolvePathEntry(config, pathName);
  const hosted = (entry.hosted ?? []).find((candidate) => candidate.name === hostedName);
  if (!hosted) {
    throw new Error(`Hosted-Eintrag "${hostedName}" wurde in Pfad "${pathName}" nicht gefunden.`);
  }
  return { ...hosted, path: join(entry.path, hosted.path) };
}

export function listPathCommands(config: Config, pathName: string): PathCommandEntry[] {
  return resolvePathEntry(config, pathName).commands ?? [];
}

export function resolvePathCommand(
  config: Config,
  pathName: string,
  key: string,
): PathCommandEntry {
  const entry = resolvePathEntry(config, pathName);
  const command = (entry.commands ?? []).find((candidate) => candidate.key === key);
  if (!command) {
    throw new Error(`Command "${key}" wurde in Pfad "${pathName}" nicht gefunden.`);
  }
  return command;
}

function isPathsOverrideFile(value: unknown): value is { paths: PathEntry[] } {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return Array.isArray(record.paths) && record.paths.every(isPathEntry);
}

export function parsePathsOverride(raw: unknown): PathEntry[] {
  if (!isPathsOverrideFile(raw)) {
    throw new Error(
      'Ungueltige Paths-Datei: Feld "paths" (Array von { name, path }) fehlt oder ist fehlerhaft.',
    );
  }
  return raw.paths;
}

export function loadPathsOverride(filePath: string): PathEntry[] {
  const raw = readFileSync(filePath, 'utf8');
  return parsePathsOverride(JSON.parse(raw) as unknown);
}

export function applyPathsOverride(config: Config, paths: PathEntry[]): Config {
  return { ...config, paths };
}

export function resolveContext(name: string): string {
  const local = readLocalFile(join('contexts', `${name}.md`));
  if (local !== undefined) {
    return local;
  }
  const embedded = EMBEDDED_CONTEXTS[name];
  if (embedded === undefined) {
    throw new Error(`Context "${name}" wurde nicht gefunden.`);
  }
  return embedded;
}

export function resolveTask(config: Config, name: string): TaskConfig {
  const task = config.tasks.find((entry) => entry.name === name);
  if (!task) {
    throw new Error(`Task "${name}" wurde in config.json nicht gefunden.`);
  }
  return task;
}
