import { spawn } from 'node:child_process';

import { extractJsonObjects } from './json-utils.js';

export interface UsageLimit {
  label: string;
  percentUsed: number;
  resetsAt: string;
}

const USAGE_LINE_PATTERN = /^(.+?):\s*(\d+)%\s*used\s*·\s*resets\s*(.+)$/gm;

export function parseUsageResult(resultText: string): UsageLimit[] {
  const limits: UsageLimit[] = [];
  for (const match of resultText.matchAll(USAGE_LINE_PATTERN)) {
    const [, label, percent, resetsAt] = match;
    if (label === undefined || percent === undefined || resetsAt === undefined) {
      continue;
    }
    limits.push({ label: label.trim(), percentUsed: Number(percent), resetsAt: resetsAt.trim() });
  }
  return limits;
}

function isUsageJsonResult(value: unknown): value is { type: 'result'; result: string } {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return record.type === 'result' && typeof record.result === 'string';
}

export function extractUsageResultText(output: string): string {
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
    if (isUsageJsonResult(parsed)) {
      return parsed.result;
    }
  }
  throw new Error(
    'Die Antwort von "claude --print /usage --output-format json" enthaelt kein gueltiges Result-JSON.',
  );
}

export async function getUsageLimits(): Promise<UsageLimit[]> {
  const { exitCode, stdout, stderr } = await new Promise<{
    exitCode: number | null;
    stdout: string;
    stderr: string;
  }>((resolve, reject) => {
    const child = spawn('claude', ['--print', '/usage', '--output-format', 'json'], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (chunk: Buffer) => {
      stdout += chunk.toString('utf8');
    });
    child.stderr.on('data', (chunk: Buffer) => {
      stderr += chunk.toString('utf8');
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve({ exitCode: code, stdout, stderr });
    });
  });

  if (exitCode !== 0) {
    throw new Error(
      `"claude --print /usage" ist fehlgeschlagen (Exit-Code ${String(exitCode)}).\n\n${stdout}${stderr}`,
    );
  }

  return parseUsageResult(extractUsageResultText(stdout));
}
