export const DEFAULT_SERVER_PORT = 8787;

const MAX_PORT = 65535;

export function resolveServerPort(
  portOption: string | undefined,
  env: NodeJS.ProcessEnv = process.env,
): number {
  const raw = portOption ?? env.PORT;
  if (raw === undefined || raw.trim() === '') {
    return DEFAULT_SERVER_PORT;
  }
  const port = Number(raw.trim());
  if (!Number.isInteger(port) || port < 0 || port > MAX_PORT) {
    throw new Error(`Ungueltiger Port: "${raw}"`);
  }
  return port;
}
