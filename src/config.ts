import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { EMBEDDED_CONFIG, EMBEDDED_CONTEXTS } from './generated/embedded-context.js';

export interface AgentDefinition {
  contexts: string[];
  model: string;
}

export interface AgentConfig extends AgentDefinition {
  name: string;
}

export interface Config {
  main: AgentDefinition;
  agents: AgentConfig[];
}

const rootDir = join(dirname(fileURLToPath(import.meta.url)), '..');

function readLocalFile(relativePath: string): string | undefined {
  try {
    return readFileSync(join(rootDir, relativePath), 'utf8');
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

function isConfig(value: unknown): value is Config {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    isAgentDefinition(record.main) &&
    Array.isArray(record.agents) &&
    record.agents.every(isAgentConfig)
  );
}

function parseConfig(raw: unknown): Config {
  if (!isConfig(raw)) {
    throw new Error(
      'Ungueltige config.json: Feld "main" (Objekt) oder "agents" (Array) fehlt oder ist fehlerhaft.',
    );
  }
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
