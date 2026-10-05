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

export interface RemoteSessionOptions {
  name?: string;
  prompt?: string;
  model?: string | undefined;
}

export async function startRemoteSession(
  cwd: string,
  { name, prompt, model }: RemoteSessionOptions = {},
): Promise<RemoteSessionStart> {
  const remoteControlFlag =
    name !== undefined && name.trim() !== '' ? `--remote-control=${name}` : '--remote-control';
  const args = ['--bg', remoteControlFlag];
  if (model !== undefined && model.trim() !== '') {
    args.push('--model', model);
  }
  if (prompt !== undefined && prompt.trim() !== '') {
    args.push('--', prompt);
  }

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
  pid?: number;
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
    (record.pid === undefined || typeof record.pid === 'number') &&
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

const ACTIVE_SESSION_STATES: ReadonlySet<string> = new Set(['working', 'starting', 'queued', 'running']);
const IDLE_SESSION_STATUSES: ReadonlySet<string> = new Set(['idle', 'done', 'failed', 'stopped']);

export function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code !== 'ESRCH';
  }
}

export function isSessionActive(
  session: RemoteAgentSession,
  isAlive: (pid: number) => boolean = isProcessAlive,
): boolean {
  if (session.pid !== undefined && !isAlive(session.pid)) {
    return false;
  }
  if (session.state !== undefined) {
    return ACTIVE_SESSION_STATES.has(session.state);
  }
  return session.status === undefined || !IDLE_SESSION_STATUSES.has(session.status);
}

export type SessionActivity = 'working' | 'waiting' | 'idle';

const WAITING_SESSION_MARKERS: ReadonlySet<string> = new Set(['waiting', 'blocked']);
const TERMINAL_SESSION_STATUSES: ReadonlySet<string> = new Set(['done', 'failed', 'stopped']);

export function sessionActivity(session: RemoteAgentSession): SessionActivity {
  if (
    session.waitingFor !== undefined ||
    (session.status !== undefined && WAITING_SESSION_MARKERS.has(session.status)) ||
    (session.state !== undefined && WAITING_SESSION_MARKERS.has(session.state))
  ) {
    return 'waiting';
  }
  if (session.state !== undefined && ACTIVE_SESSION_STATES.has(session.state)) {
    return 'working';
  }
  return 'idle';
}

export function isSessionRunning(
  session: RemoteAgentSession,
  isAlive: (pid: number) => boolean = isProcessAlive,
): boolean {
  if (session.pid !== undefined) {
    return isAlive(session.pid);
  }
  return session.status === undefined || !TERMINAL_SESSION_STATUSES.has(session.status);
}

async function runClaude(args: string[]): Promise<{ exitCode: number | null; output: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn('claude', args, { stdio: ['ignore', 'pipe', 'pipe'] });
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
  });
}

export async function killRemoteSession(
  session: RemoteAgentSession,
  killProcess: (pid: number, signal: NodeJS.Signals) => void = process.kill.bind(process),
): Promise<void> {
  if (session.id !== undefined) {
    const { exitCode, output } = await runClaude(['stop', session.id]);
    if (exitCode !== 0) {
      throw new Error(`"claude stop ${session.id}" ist fehlgeschlagen (Exit-Code ${String(exitCode)}).\n\n${output}`);
    }
    return;
  }
  if (session.pid === undefined || !Number.isInteger(session.pid) || session.pid <= 1) {
    throw new Error('Die Session hat weder eine Kurz-ID noch eine gueltige PID und kann nicht beendet werden.');
  }
  try {
    killProcess(session.pid, 'SIGTERM');
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ESRCH') {
      throw error;
    }
  }
}
