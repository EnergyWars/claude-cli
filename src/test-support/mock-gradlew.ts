import { chmodSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import type { GradleBuildType } from '../gradle-install.js';

export interface GradlewStep {
  exitCode?: number;
  stdout?: string;
  createApk?: boolean;
}

export interface FakeGradlewOptions {
  buildType?: GradleBuildType;
  /** Scripted sequence of results, one per invocation; the last entry repeats once exhausted. */
  steps?: GradlewStep[];
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

function renderStep(step: GradlewStep, buildType: GradleBuildType): string {
  const exitCode = step.exitCode ?? 0;
  const createApk = step.createApk ?? exitCode === 0;
  const lines: string[] = [];
  if (step.stdout !== undefined && step.stdout !== '') {
    lines.push(`cat <<'MOCK_GRADLEW_STDOUT_EOF'\n${step.stdout}\nMOCK_GRADLEW_STDOUT_EOF`);
  }
  if (createApk) {
    lines.push(`mkdir -p "build/outputs/apk/${buildType}"`);
    lines.push(`echo FAKE-APK > "build/outputs/apk/${buildType}/app-${buildType}.apk"`);
  }
  lines.push(`exit ${String(exitCode)}`);
  return lines.join('\n');
}

export function writeFakeGradlew(cwd: string, options: FakeGradlewOptions = {}): void {
  const { buildType = 'debug', steps = [{ exitCode: 0, createApk: true }] } = options;
  const scriptPath = join(cwd, 'gradlew');
  const stateFile = join(cwd, '.gradlew-call-count');

  // No indentation of step bodies: a heredoc terminator must start at column 0,
  // so indenting would break `cat <<'EOF' ... EOF` blocks inside renderStep().
  const caseBlocks = steps
    .map((step, index) => `${String(index)})\n${renderStep(step, buildType)}\n;;`)
    .join('\n');

  const lastStep = steps.at(-1) ?? { exitCode: 0, createApk: true };
  const defaultBlock = `*)\n${renderStep(lastStep, buildType)}\n;;`;

  const script = `#!/bin/sh
STATE_FILE=${shellQuote(stateFile)}
COUNT=0
if [ -f "$STATE_FILE" ]; then
  COUNT=$(cat "$STATE_FILE")
fi
echo $((COUNT + 1)) > "$STATE_FILE"

case "$COUNT" in
${caseBlocks}
${defaultBlock}
esac
`;

  writeFileSync(scriptPath, script);
  chmodSync(scriptPath, 0o755);
}

export function readGradlewCallCount(cwd: string): number {
  const stateFile = join(cwd, '.gradlew-call-count');
  try {
    return Number(readFileSync(stateFile, 'utf8').trim());
  } catch {
    return 0;
  }
}
