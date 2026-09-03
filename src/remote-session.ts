import { spawn } from 'node:child_process';

export interface RemoteSessionStart {
  id: string;
  output: string;
}

const BACKGROUND_SESSION_ID_PATTERN = /backgrounded · (\S+)/;

export function parseBackgroundSessionId(output: string): string {
  const match = BACKGROUND_SESSION_ID_PATTERN.exec(output);
  if (match?.[1] === undefined) {
    throw new Error(
      `Konnte die Session-ID nicht aus der Ausgabe von "claude --bg --remote-control" lesen:\n${output}`,
    );
  }
  return match[1];
}

export async function startRemoteSession(cwd: string, name?: string): Promise<RemoteSessionStart> {
  const remoteControlFlag =
    name !== undefined && name.trim() !== '' ? `--remote-control=${name}` : '--remote-control';
  const args = ['--bg', remoteControlFlag];

  const { exitCode, output } = await new Promise<{ exitCode: number | null; output: string }>(
    (resolve, reject) => {
      const child = spawn('claude', args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
      let collected = '';
      child.stdout.on('data', (chunk: Buffer) => {
        collected += chunk.toString('utf8');
      });
      child.stderr.on('data', (chunk: Buffer) => {
        collected += chunk.toString('utf8');
      });
      child.on('error', reject);
      child.on('exit', (code) => {
        resolve({ exitCode: code, output: collected });
      });
    },
  );

  if (exitCode !== 0) {
    throw new Error(
      `"claude --bg --remote-control" ist fehlgeschlagen (Exit-Code ${String(exitCode)}).\n\n${output}`,
    );
  }

  return { id: parseBackgroundSessionId(output), output };
}

export interface RemoteAgentSession {
  pid: number;
  id?: string;
  cwd: string;
  kind: string;
  startedAt: number;
  sessionId: string;
  name: string;
  status?: string;
  waitingFor?: string;
  state?: string;
}

function isRemoteAgentSession(value: unknown): value is RemoteAgentSession {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.pid === 'number' &&
    typeof record.cwd === 'string' &&
    typeof record.kind === 'string' &&
    typeof record.startedAt === 'number' &&
    typeof record.sessionId === 'string' &&
    typeof record.name === 'string'
  );
}

export async function listRemoteSessions(cwd?: string): Promise<RemoteAgentSession[]> {
  const args = cwd !== undefined ? ['agents', '--json', '--cwd', cwd] : ['agents', '--json'];

  const { exitCode, stdout, stderr } = await new Promise<{
    exitCode: number | null;
    stdout: string;
    stderr: string;
  }>((resolve, reject) => {
    const child = spawn('claude', args, { stdio: ['ignore', 'pipe', 'pipe'] });
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
      `"claude agents --json" ist fehlgeschlagen (Exit-Code ${String(exitCode)}).\n\n${stdout}${stderr}`,
    );
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(stdout);
  } catch {
    throw new Error(`"claude agents --json" lieferte kein gueltiges JSON:\n${stdout}`);
  }

  if (!Array.isArray(parsed)) {
    throw new Error(`"claude agents --json" lieferte kein Array:\n${stdout}`);
  }

  return parsed.filter(isRemoteAgentSession);
}
