import { spawn } from 'node:child_process';

import type { TicketAgentConfig } from './config.js';
import { extractJsonObjects } from './json-utils.js';
import { buildClaudeArgs } from './launch.js';

const TICKET_AGENT_OUTPUT_INSTRUCTIONS = [
  'Antworte AUSSCHLIESSLICH mit einem einzigen JSON-Objekt in genau dieser Form, ohne Markdown-Codeblock und ohne zusaetzlichen Text davor oder danach:',
  '{"summary": "...", "claudeInstruction": "...", "category": "..."}',
  '"summary": kurze Beschreibung des Ziels und des aktuellen Ist-Zustands (2-4 Saetze).',
  '"claudeInstruction": eine konkrete Anweisung, wie man sie Claude spaeter geben wuerde, um das Feature/den Fix umzusetzen.',
  '"category": kurzes Schlagwort zur Gruppierung zusammenhaengender Tickets (z. B. "UI", "Backend", "Bugfix").',
].join('\n');

export function buildTicketAgentSystemPrompt(task: string): string {
  return `${task}\n\n${TICKET_AGENT_OUTPUT_INSTRUCTIONS}`;
}

export interface TicketAgentOutput {
  summary: string;
  claudeInstruction: string;
  category: string;
}

function isTicketAgentOutput(value: unknown): value is TicketAgentOutput {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.summary === 'string' &&
    record.summary.trim() !== '' &&
    typeof record.claudeInstruction === 'string' &&
    record.claudeInstruction.trim() !== '' &&
    typeof record.category === 'string' &&
    record.category.trim() !== ''
  );
}

export function parseTicketAgentOutput(output: string): TicketAgentOutput {
  const candidates = extractJsonObjects(output);
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
    if (isTicketAgentOutput(parsed)) {
      return {
        summary: parsed.summary.trim(),
        claudeInstruction: parsed.claudeInstruction.trim(),
        category: parsed.category.trim(),
      };
    }
  }
  throw new Error(
    'Die Antwort des Ticket-Agenten enthaelt kein gueltiges Ticket-JSON ({"summary", "claudeInstruction", "category"}).',
  );
}

export async function runTicketAgent(
  cwd: string,
  ticketAgent: TicketAgentConfig,
  text: string,
): Promise<TicketAgentOutput> {
  const systemPrompt = buildTicketAgentSystemPrompt(ticketAgent.task);
  const args = buildClaudeArgs(ticketAgent.model, systemPrompt, text);

  const { exitCode, output } = await new Promise<{ exitCode: number | null; output: string }>(
    (resolve, reject) => {
      const child = spawn('claude', args, { stdio: ['ignore', 'pipe', 'pipe'], cwd });
      let collected = '';

      const handleChunk = (chunk: Buffer): void => {
        collected += chunk.toString('utf8');
      };

      child.stdout.on('data', handleChunk);
      child.stderr.on('data', handleChunk);
      child.on('error', reject);
      child.on('exit', (code) => {
        resolve({ exitCode: code, output: collected });
      });
    },
  );

  if (exitCode !== 0) {
    throw new Error(
      `Ticket-Agent (Model "${ticketAgent.model}") ist fehlgeschlagen (Exit-Code ${String(exitCode)}).\n\nOutput:\n${output}`,
    );
  }

  return parseTicketAgentOutput(output);
}
