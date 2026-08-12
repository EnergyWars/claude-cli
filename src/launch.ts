import { spawn } from 'node:child_process';

import { resolveAgent, resolveContext, resolveTaskFile, type TaskConfig } from './config.js';

export function buildSystemPrompt(entity: { contexts: string[] }): string {
  return entity.contexts.map((name) => resolveContext(name)).join('\n\n');
}

export function buildTaskContent(task: { tasks: string[] }): string {
  return task.tasks.map((name) => resolveTaskFile(name)).join('\n\n');
}

export function buildClaudeArgs(
  model: string,
  systemPrompt: string,
  headlessPrompt?: string,
): string[] {
  const args = [
    '--model',
    model,
    '--append-system-prompt',
    systemPrompt,
    '--permission-mode',
    'acceptEdits',
  ];

  if (headlessPrompt !== undefined) {
    args.push('--print', headlessPrompt);
  }

  return args;
}

export async function launchAgent(
  name: string | undefined,
  modelOverride?: string,
  headlessPrompt?: string,
): Promise<void> {
  const agent = resolveAgent(name);
  const model = modelOverride ?? agent.model;
  const args = buildClaudeArgs(model, buildSystemPrompt(agent), headlessPrompt);

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
}

export async function runHeadlessCommand(
  entity: { contexts: string[] },
  model: string,
  command: string,
  cwd: string,
  onChunk: (output: string) => void,
): Promise<HeadlessCommandResult> {
  const args = buildClaudeArgs(model, buildSystemPrompt(entity), command);

  return new Promise((resolve, reject) => {
    const child = spawn('claude', args, { stdio: ['ignore', 'pipe', 'pipe'], cwd });
    let output = '';

    const handleChunk = (chunk: Buffer): void => {
      output += chunk.toString('utf8');
      onChunk(output);
    };

    child.stdout.on('data', handleChunk);
    child.stderr.on('data', handleChunk);
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve({ exitCode: code, output });
    });
  });
}

export async function runShellCommand(
  command: string,
  cwd: string,
  onChunk: (output: string) => void,
): Promise<HeadlessCommandResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, { shell: true, cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';

    const handleChunk = (chunk: Buffer): void => {
      output += chunk.toString('utf8');
      onChunk(output);
    };

    child.stdout.on('data', handleChunk);
    child.stderr.on('data', handleChunk);
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve({ exitCode: code, output });
    });
  });
}

export async function runTask(task: TaskConfig, detached: boolean): Promise<void> {
  const args = buildClaudeArgs(task.model, buildSystemPrompt(task), buildTaskContent(task));

  if (detached) {
    const child = spawn('claude', args, { stdio: 'ignore', detached: true });
    child.unref();
    return;
  }

  const exitCode = await new Promise<number>((resolve, reject) => {
    const child = spawn('claude', args, {
      stdio: ['ignore', 'inherit', 'inherit'],
      detached: true,
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve(code ?? 0);
    });
  });

  process.exit(exitCode);
}
