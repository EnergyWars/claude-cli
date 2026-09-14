import { spawn, type ChildProcess } from 'node:child_process';

import {
  resolveAgent,
  resolveContext,
  resolveSchedulerContext,
  type SchedulerConfig,
  type TaskConfig,
} from './config.js';
import type { CommandUsage } from './db.js';
import { extractJsonObjects } from './json-utils.js';

export function buildSystemPrompt(entity: { contexts: string[] }): string {
  return entity.contexts.map((name) => resolveContext(name)).join('\n\n');
}

/** Eigener scheduler/<name>.md-Context immer zuerst, danach die zusaetzlich referenzierten contexts (wie bei Agents/Tasks). */
export function buildSchedulerSystemPrompt(scheduler: SchedulerConfig): string {
  const ownContext = resolveSchedulerContext(scheduler.name);
  const extraContexts = (scheduler.contexts ?? []).map((name) => resolveContext(name));
  return [ownContext, ...extraContexts].join('\n\n');
}

export const SCHEDULER_TRIGGER_PROMPT =
  'Fuehre den geplanten Lauf jetzt aus, gemaess dem im System-Prompt beschriebenen Auftrag.';

/**
 * Text, der als `command` eines Scheduler-Laufs im Verlauf gespeichert wird - im Gegensatz zu
 * {@link SCHEDULER_TRIGGER_PROMPT} (immer identisch, nur die technische `--print`-Eingabe fuer `claude`)
 * soll hier der tatsaechliche, pro Lauf aufgeloeste Auftrag sichtbar sein.
 */
export function describeSchedulerRun(scheduler: SchedulerConfig, systemPrompt: string): string {
  return `${scheduler.description}\n\n${systemPrompt}`;
}

export function buildClaudeArgs(
  model: string,
  systemPrompt: string,
  headlessPrompt?: string,
  interactivePrompt?: string,
  permissions?: string[],
  headlessOutputFormat?: 'json',
): string[] {
  const args = [
    '--model',
    model,
    '--append-system-prompt',
    systemPrompt,
    '--permission-mode',
    'auto',
  ];

  if (headlessPrompt !== undefined) {
    args.push('--print', headlessPrompt);
    if (headlessOutputFormat !== undefined) {
      args.push('--output-format', headlessOutputFormat);
    }
  } else if (interactivePrompt !== undefined) {
    args.push(interactivePrompt);
  }

  if (permissions !== undefined && permissions.length > 0) {
    args.push('--allowedTools', ...permissions);
  }

  return args;
}

interface HeadlessResultJson {
  type?: unknown;
  result?: unknown;
  total_cost_usd?: unknown;
  usage?: {
    input_tokens?: unknown;
    output_tokens?: unknown;
    cache_creation_input_tokens?: unknown;
    cache_read_input_tokens?: unknown;
  };
}

function toFiniteNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

/**
 * Parst das abschliessende `claude --print ... --output-format json`-Ergebnisobjekt. Nutzt wie
 * `extractUsageResultText` (`src/usage.ts`) `extractJsonObjects()` statt eines simplen `JSON.parse(raw)`
 * auf dem Gesamtstring - dadurch liefert dieselbe Funktion auch waehrend `claude` noch laeuft (`raw` ist
 * dann noch unvollstaendiges JSON, z.B. `{"type":"result","result":` ohne schliessende Klammer)
 * konsistent `undefined`, bis das Ergebnisobjekt vollstaendig auf stdout angekommen ist. Aufrufer fallen in
 * diesem Fall auf den rohen bisherigen Output zurueck (siehe {@link runHeadlessCommand}) - unveraendertes
 * Verhalten fuer jedes Mock-"claude"-Binary in den Tests, das kein JSON, sondern rohen Text ausgibt.
 */
export function parseHeadlessResultJson(
  raw: string,
): { text: string; usage: CommandUsage | undefined } | undefined {
  const candidates = extractJsonObjects(raw);
  for (let i = candidates.length - 1; i >= 0; i -= 1) {
    const candidate = candidates[i];
    if (candidate === undefined) {
      continue;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(candidate);
    } catch {
      continue;
    }
    if (typeof parsed !== 'object' || parsed === null) {
      continue;
    }
    const result = parsed as HeadlessResultJson;
    if (result.type !== 'result' || typeof result.result !== 'string') {
      continue;
    }
    const usageRaw = result.usage;
    const usage: CommandUsage | undefined =
      typeof usageRaw === 'object'
        ? {
            costUsd: toFiniteNumber(result.total_cost_usd),
            inputTokens: toFiniteNumber(usageRaw.input_tokens),
            outputTokens: toFiniteNumber(usageRaw.output_tokens),
            cacheCreationInputTokens: toFiniteNumber(usageRaw.cache_creation_input_tokens),
            cacheReadInputTokens: toFiniteNumber(usageRaw.cache_read_input_tokens),
          }
        : undefined;
    return { text: result.result, usage };
  }
  return undefined;
}

export async function launchAgent(
  name: string | undefined,
  modelOverride?: string,
  headlessPrompt?: string,
): Promise<void> {
  const agent = resolveAgent(name);
  const model = modelOverride ?? agent.model;
  const args = buildClaudeArgs(
    model,
    buildSystemPrompt(agent),
    headlessPrompt,
    undefined,
    agent.permissions,
  );

  const exitCode = await new Promise<number>((resolve, reject) => {
    const child = spawn('claude', args, { stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve(code ?? 0);
    });
  });

  process.exit(exitCode);
}

export interface HeadlessCommandResult {
  exitCode: number | null;
  output: string;
  usage: CommandUsage | undefined;
}

/**
 * Fordert von `claude` `--output-format json` an, um Kosten/Token-Verbrauch (`usage`) einzusammeln
 * (siehe {@link parseHeadlessResultJson}). `stdoutOnly` wird ausschliesslich fuer den JSON-Parse-Versuch
 * verwendet (stderr wuerde ein sonst gueltiges JSON-Objekt zerstueckeln); `combinedOutput` (stdout+stderr,
 * wie vor Einfuehrung dieses Formats) ist der Fallback, solange noch kein vollstaendiges Ergebnisobjekt
 * geparst werden kann - dadurch bleibt das Verhalten fuer jeden Mock/Fehlerfall, der kein `--output-format
 * json` versteht (z.B. alle bestehenden Test-Mocks, ein abstuerzendes `claude`-Binary), unveraendert.
 */
export async function runHeadlessCommand(
  systemPrompt: string,
  model: string,
  command: string,
  cwd: string,
  onChunk: (output: string) => void,
  permissions?: string[],
  onSpawn?: (child: ChildProcess) => void,
): Promise<HeadlessCommandResult> {
  const args = buildClaudeArgs(model, systemPrompt, command, undefined, permissions, 'json');

  return new Promise((resolve, reject) => {
    const child = spawn('claude', args, { stdio: ['ignore', 'pipe', 'pipe'], cwd });
    onSpawn?.(child);
    let combinedOutput = '';
    let stdoutOnly = '';

    const currentDisplay = (): { text: string; usage: CommandUsage | undefined } => {
      const parsed = parseHeadlessResultJson(stdoutOnly);
      return parsed ?? { text: combinedOutput, usage: undefined };
    };

    child.stdout.on('data', (chunk: Buffer) => {
      const text = chunk.toString('utf8');
      combinedOutput += text;
      stdoutOnly += text;
      onChunk(currentDisplay().text);
    });
    child.stderr.on('data', (chunk: Buffer) => {
      combinedOutput += chunk.toString('utf8');
      onChunk(currentDisplay().text);
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      const final = currentDisplay();
      resolve({ exitCode: code, output: final.text, usage: final.usage });
    });
  });
}

export async function runShellCommand(
  command: string,
  cwd: string,
  onChunk: (output: string) => void,
  onSpawn?: (child: ChildProcess) => void,
): Promise<HeadlessCommandResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, { shell: true, cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    onSpawn?.(child);
    let output = '';

    const handleChunk = (chunk: Buffer): void => {
      output += chunk.toString('utf8');
      onChunk(output);
    };

    child.stdout.on('data', handleChunk);
    child.stderr.on('data', handleChunk);
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve({ exitCode: code, output, usage: undefined });
    });
  });
}

export async function runTask(task: TaskConfig): Promise<void> {
  const args = buildClaudeArgs(
    task.model,
    buildSystemPrompt(task),
    undefined,
    task.startCommand,
    task.permissions,
  );

  const exitCode = await new Promise<number>((resolve, reject) => {
    const child = spawn('claude', args, { stdio: 'inherit' });
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve(code ?? 0);
    });
  });

  process.exit(exitCode);
}
