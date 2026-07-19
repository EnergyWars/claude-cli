import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const tsxBin = join(projectRoot, 'node_modules', '.bin', 'tsx');
const entryPoint = join(projectRoot, 'src', 'index.ts');

export interface CliResult {
  stdout: string;
  stderr: string;
  exitCode: number | null;
}

export interface RunCliOptions {
  env?: NodeJS.ProcessEnv;
  input?: string;
}

export async function runCli(args: string[], options: RunCliOptions = {}): Promise<CliResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(tsxBin, [entryPoint, ...args], {
      env: { ...process.env, ...options.env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk: Buffer) => {
      stdout += chunk.toString('utf8');
    });
    child.stderr.on('data', (chunk: Buffer) => {
      stderr += chunk.toString('utf8');
    });

    if (options.input !== undefined) {
      child.stdin.write(options.input);
    }
    child.stdin.end();

    child.on('error', reject);
    child.on('exit', (code) => {
      resolve({ stdout, stderr, exitCode: code });
    });
  });
}
