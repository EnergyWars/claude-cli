import { spawn } from 'node:child_process';

import { resolveAgent, resolveContext, type TaskConfig } from './config.js';

export function buildSystemPrompt(entity: { contexts: string[] }): string {
  return entity.contexts.map((name) => resolveContext(name)).join('\n\n');
}

export function buildClaudeArgs(
  model: string,
  systemPrompt: string,
  headlessPrompt?: string,
  interactivePrompt?: string,
  permissions?: string[],
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
  } else if (interactivePrompt !== undefined) {
    args.push(interactivePrompt);
  }

  if (permissions !== undefined && permissions.length > 0) {
    args.push('--allowedTools', ...permissions);
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
}

export async function runHeadlessCommand(
  entity: { contexts: string[] },
  model: string,
  command: string,
  cwd: string,
  onChunk: (output: string) => void,
  permissions?: string[],
): Promise<HeadlessCommandResult> {
  const args = buildClaudeArgs(model, buildSystemPrompt(entity), command, undefined, permissions);

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
