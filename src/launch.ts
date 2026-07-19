import { spawn } from 'node:child_process';

import { resolveAgent, resolveContext, type AgentDefinition } from './config.js';

function buildSystemPrompt(agent: AgentDefinition): string {
  return agent.contexts.map((name) => resolveContext(name)).join('\n\n');
}

export async function launchAgent(name: string | undefined): Promise<void> {
  const agent = resolveAgent(name);
  const systemPrompt = buildSystemPrompt(agent);

  const exitCode = await new Promise<number>((resolve, reject) => {
    const child = spawn(
      'claude',
      [
        '--model',
        agent.model,
        '--append-system-prompt',
        systemPrompt,
        '--permission-mode',
        'acceptEdits',
      ],
      { stdio: 'inherit' },
    );
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve(code ?? 0);
    });
  });

  process.exit(exitCode);
}
