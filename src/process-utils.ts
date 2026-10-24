import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { delimiter, join } from 'node:path';

export interface CapturedProcessResult {
  exitCode: number;
  output: string;
}

export function isOnPath(executable: string): boolean {
  const names = process.platform === 'win32' ? [`${executable}.exe`, `${executable}.bat`] : [executable];
  return (process.env.PATH ?? '')
    .split(delimiter)
    .filter((dir) => dir.length > 0)
    .some((dir) => names.some((name) => existsSync(join(dir, name))));
}

export function runCapturedProcess(
  command: string,
  args: readonly string[],
  cwd: string,
): Promise<CapturedProcessResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, [...args], { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';

    const handleChunk = (chunk: Buffer): void => {
      output += chunk.toString('utf8');
      process.stdout.write(chunk);
    };

    child.stdout.on('data', handleChunk);
    child.stderr.on('data', handleChunk);
    child.on('error', reject);
    child.on('exit', (code) => {
      resolve({ exitCode: code ?? 1, output });
    });
  });
}
