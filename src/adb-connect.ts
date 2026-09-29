import { spawn } from 'node:child_process';
import { connect } from 'node:net';

import { loadEnv } from './env.js';

export const ADB_HOST_ENV_VAR = 'CL_ADB_HOST';

export const DEFAULT_PORT_SPEC = '5555,30000-65535';
export const DEFAULT_SCAN_TIMEOUT_MS = 300;
export const DEFAULT_SCAN_PARALLELISM = 500;
export const DEFAULT_CONNECT_TIMEOUT_MS = 5000;
export const DEFAULT_MDNS_TIMEOUT_MS = 2000;

const PRIORITY_PORT = 5555;
const MAX_PORT = 65535;

export type ConnectResult = 'connected' | 'unauthorized';

export interface AdbConnectOptions {
  portSpec?: string;
  scanTimeoutMs?: number;
  parallelism?: number;
  connectTimeoutMs?: number;
  mdnsTimeoutMs?: number;
  useMdns?: boolean;
  log?: (message: string) => void;
}

export function isValidIpv4(value: string): boolean {
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(value);
  if (match === null) {
    return false;
  }
  return match.slice(1).every((octet) => Number(octet) <= 255 && (octet === '0' || !octet.startsWith('0')));
}

export function expandPortSpec(spec: string): number[] {
  const ports: number[] = [];
  for (const rawPart of spec.split(',')) {
    const part = rawPart.trim();
    const range = /^(\d+)-(\d+)$/.exec(part);
    if (range !== null) {
      const start = Number(range[1]);
      const end = Number(range[2]);
      if (start < 1 || end > MAX_PORT || start > end) {
        throw new Error(`Ungueltiger Portbereich: ${part}`);
      }
      for (let port = start; port <= end; port += 1) {
        ports.push(port);
      }
    } else if (/^\d+$/.test(part)) {
      const port = Number(part);
      if (port < 1 || port > MAX_PORT) {
        throw new Error(`Ungueltiger Port: ${part}`);
      }
      ports.push(port);
    } else {
      throw new Error(`Ungueltige Port-Angabe: ${part}`);
    }
  }
  return ports;
}

export function orderPortsPriorityFirst(ports: readonly number[]): number[] {
  return [
    ...ports.filter((port) => port === PRIORITY_PORT),
    ...ports.filter((port) => port !== PRIORITY_PORT),
  ];
}

export function parseMdnsPorts(output: string, host: string): number[] {
  const ports: number[] = [];
  for (const line of output.split('\n')) {
    if (!line.includes('_adb-tls-connect._tcp')) {
      continue;
    }
    const address = line.trim().split(/\s+/).pop() ?? '';
    const separator = address.lastIndexOf(':');
    if (separator === -1 || address.slice(0, separator) !== host) {
      continue;
    }
    const port = Number(address.slice(separator + 1));
    if (Number.isInteger(port) && port > 0) {
      ports.push(port);
    }
  }
  return ports;
}

export type DeviceState = 'device' | 'unauthorized' | 'absent';

export function findDeviceState(devicesOutput: string, host: string, port: number): DeviceState {
  const target = `${host}:${String(port)}`;
  for (const line of devicesOutput.split('\n')) {
    const [serial, state] = line.trim().split(/\s+/);
    if (serial === target) {
      if (state === 'device') {
        return 'device';
      }
      if (state === 'unauthorized') {
        return 'unauthorized';
      }
    }
  }
  return 'absent';
}

export function isPortOpen(host: string, port: number, timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = connect({ host, port });
    const finish = (open: boolean): void => {
      socket.destroy();
      resolve(open);
    };
    socket.setTimeout(timeoutMs);
    socket.once('connect', () => {
      finish(true);
    });
    socket.once('timeout', () => {
      finish(false);
    });
    socket.once('error', () => {
      finish(false);
    });
  });
}

export async function scanOpenPorts(
  host: string,
  ports: readonly number[],
  timeoutMs: number,
  parallelism: number,
): Promise<number[]> {
  const open = new Set<number>();
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < ports.length) {
      const port = ports[next];
      next += 1;
      if (port !== undefined && (await isPortOpen(host, port, timeoutMs))) {
        open.add(port);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Math.min(parallelism, ports.length)) }, worker));
  return ports.filter((port) => open.has(port));
}

function runAdb(adbExecutable: string, args: string[], timeoutMs?: number): Promise<string> {
  return new Promise((resolve) => {
    const child = spawn(adbExecutable, args, timeoutMs === undefined ? {} : { timeout: timeoutMs });
    let output = '';
    const collect = (chunk: Buffer): void => {
      output += chunk.toString('utf8');
    };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    child.on('error', () => {
      resolve(output);
    });
    child.on('close', () => {
      resolve(output);
    });
  });
}

async function tryConnectPorts(
  adbExecutable: string,
  host: string,
  ports: readonly number[],
  connectTimeoutMs: number,
  log: (message: string) => void,
): Promise<ConnectResult | undefined> {
  for (const port of ports) {
    log(`Verbinde zu ${host}:${String(port)} ...`);
    const output = await runAdb(adbExecutable, ['connect', `${host}:${String(port)}`], connectTimeoutMs);
    log(output.trim());
    const state = findDeviceState(await runAdb(adbExecutable, ['devices']), host, port);
    if (state === 'device') {
      log(`Erfolgreich verbunden mit ${host}:${String(port)}.`);
      return 'connected';
    }
    if (state === 'unauthorized') {
      log(
        `Verbindung zu ${host}:${String(port)} hergestellt, aber nicht autorisiert. Bitte Autorisierung auf dem Geraet bestaetigen.`,
      );
      return 'unauthorized';
    }
  }
  return undefined;
}

export async function connectAdb(
  adbExecutable: string,
  host: string,
  options: AdbConnectOptions = {},
): Promise<ConnectResult> {
  const {
    portSpec = DEFAULT_PORT_SPEC,
    scanTimeoutMs = DEFAULT_SCAN_TIMEOUT_MS,
    parallelism = DEFAULT_SCAN_PARALLELISM,
    connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
    mdnsTimeoutMs = DEFAULT_MDNS_TIMEOUT_MS,
    useMdns = true,
    log = (message: string) => {
      console.log(message);
    },
  } = options;

  if (!isValidIpv4(host)) {
    throw new Error(`'${host}' ist keine gueltige IPv4-Adresse.`);
  }
  const ports = expandPortSpec(portSpec);

  if (useMdns) {
    log(`Suche ${host} per mDNS (adb mdns services)...`);
    const mdnsPorts = parseMdnsPorts(await runAdb(adbExecutable, ['mdns', 'services'], mdnsTimeoutMs), host);
    if (mdnsPorts.length > 0) {
      log(`mDNS: Port(s) gefunden: ${mdnsPorts.join(' ')}`);
      const result = await tryConnectPorts(adbExecutable, host, mdnsPorts, connectTimeoutMs, log);
      if (result !== undefined) {
        return result;
      }
      log('mDNS-Verbindung fehlgeschlagen, falle auf Portscan zurueck.');
    } else {
      log(`Kein Geraet unter ${host} per mDNS gefunden, falle auf Portscan zurueck.`);
    }
  }

  log(`Scanne ${host} auf offene ADB-Ports (${String(ports.length)} Ports, Parallelitaet: ${String(parallelism)})...`);
  const openPorts = await scanOpenPorts(host, ports, scanTimeoutMs, parallelism);
  if (openPorts.length === 0) {
    throw new Error(`Kein offener Port unter ${host} gefunden.`);
  }
  log(`Offene Ports gefunden: ${openPorts.join(' ')}`);

  const result = await tryConnectPorts(
    adbExecutable,
    host,
    orderPortsPriorityFirst(openPorts),
    connectTimeoutMs,
    log,
  );
  if (result === undefined) {
    throw new Error(`Es konnte keine ADB-Verbindung zu ${host} aufgebaut werden.`);
  }
  return result;
}

export function resolveAdbHost(
  explicitHost?: string,
  env: Record<string, string> = loadEnv(),
): string | undefined {
  const value = explicitHost ?? env[ADB_HOST_ENV_VAR];
  return value === undefined || value.trim() === '' ? undefined : value.trim();
}

export async function connectConfiguredAdb(
  adbExecutable: string,
  env: Record<string, string> = loadEnv(),
): Promise<void> {
  const host = resolveAdbHost(undefined, env);
  if (host === undefined) {
    return;
  }
  try {
    await connectAdb(adbExecutable, host);
  } catch (error) {
    console.error(
      `adb connect zu ${host} fehlgeschlagen: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}
