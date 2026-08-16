import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';

const ARGS_END_MARKER = '===ARGS-END===';

export interface MockClaudeOptions {
  outputChunks?: string[];
  chunkDelayMs?: number;
  exitCode?: number;
  /** When set, each invocation appends its args as a JSON line to this file (opt-in, for tests that spawn "claude" with stdio: 'inherit' and can't capture its stdout directly). */
  logFile?: string;
}

export interface MockClaude {
  binDir: string;
  cleanup: () => void;
}

export function createMockClaude(options: MockClaudeOptions = {}): MockClaude {
  const { outputChunks = ['MOCK_OUTPUT'], chunkDelayMs = 20, exitCode = 0, logFile } = options;
  const binDir = mkdtempSync(join(tmpdir(), 'cl-mock-claude-'));
  const scriptPath = join(binDir, 'claude');

  const writeStatements = outputChunks
    .map((chunk) => `  process.stdout.write(${JSON.stringify(chunk)});`)
    .join(`\n  await new Promise((r) => setTimeout(r, ${chunkDelayMs.toString()}));\n`);

  const logStatement =
    logFile === undefined
      ? ''
      : `require('node:fs').appendFileSync(${JSON.stringify(logFile)}, JSON.stringify(process.argv.slice(2)) + '\\n');\n`;

  const script = `#!/usr/bin/env node
${logStatement}process.stdout.write(JSON.stringify(process.argv.slice(2)) + '\\n${ARGS_END_MARKER}\\n');
(async () => {
${writeStatements}
  process.exit(${exitCode.toString()});
})();
`;

  writeFileSync(scriptPath, script);
  chmodSync(scriptPath, 0o755);

  return {
    binDir,
    cleanup: () => {
      rmSync(binDir, { recursive: true, force: true });
    },
  };
}

export function pathWithMock(binDir: string): string {
  return `${binDir}${delimiter}${process.env.PATH ?? ''}`;
}

export function extractMockArgs(output: string): string[] {
  const marker = `\n${ARGS_END_MARKER}\n`;
  const markerIndex = output.indexOf(marker);
  if (markerIndex === -1) {
    throw new Error('Mock-Claude-Output enthaelt keine Args-Markierung.');
  }
  // Content may be preceded by unrelated output (e.g. a readline "Prompt: " with no
  // trailing newline), so locate the JSON array's start rather than assuming index 0.
  const jsonStart = output.indexOf('[');
  if (jsonStart === -1 || jsonStart > markerIndex) {
    throw new Error('Mock-Claude-Output enthaelt kein JSON-Array vor der Markierung.');
  }
  return JSON.parse(output.slice(jsonStart, markerIndex)) as string[];
}

export function createEmptyBinDir(): { binDir: string; cleanup: () => void } {
  const binDir = mkdtempSync(join(tmpdir(), 'cl-empty-bin-'));
  return {
    binDir,
    cleanup: () => {
      rmSync(binDir, { recursive: true, force: true });
    },
  };
}
