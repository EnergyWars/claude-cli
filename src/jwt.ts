import { createHmac, timingSafeEqual } from 'node:crypto';

const HEADER_JSON = JSON.stringify({ alg: 'HS256', typ: 'JWT' });

function base64UrlEncode(input: string): string {
  return Buffer.from(input, 'utf8').toString('base64url');
}

function base64UrlDecode(input: string): string {
  return Buffer.from(input, 'base64url').toString('utf8');
}

function sign(signingInput: string, secret: string): string {
  return createHmac('sha256', secret).update(signingInput).digest('base64url');
}

export interface JwtPayload {
  [key: string]: unknown;
  iat: number;
  exp: number;
}

export function signJwt(
  payload: Record<string, unknown>,
  secret: string,
  options: { expiresInSeconds: number; nowMs?: number },
): string {
  const nowSeconds = Math.floor((options.nowMs ?? Date.now()) / 1000);
  const fullPayload: JwtPayload = {
    ...payload,
    iat: nowSeconds,
    exp: nowSeconds + options.expiresInSeconds,
  };
  const encodedHeader = base64UrlEncode(HEADER_JSON);
  const encodedPayload = base64UrlEncode(JSON.stringify(fullPayload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const signature = sign(signingInput, secret);
  return `${signingInput}.${signature}`;
}

export type JwtVerifyResult =
  | { valid: true; payload: JwtPayload }
  | { valid: false; reason: 'malformed' | 'alg' | 'bad-signature' | 'expired' };

export function verifyJwt(
  token: string,
  secret: string,
  options: { nowMs?: number } = {},
): JwtVerifyResult {
  const parts = token.split('.');
  if (parts.length !== 3) {
    return { valid: false, reason: 'malformed' };
  }
  const [encodedHeader, encodedPayload, signature] = parts as [string, string, string];

  let header: unknown;
  try {
    header = JSON.parse(base64UrlDecode(encodedHeader));
  } catch {
    return { valid: false, reason: 'malformed' };
  }
  if (
    typeof header !== 'object' ||
    header === null ||
    (header as Record<string, unknown>).alg !== 'HS256'
  ) {
    return { valid: false, reason: 'alg' };
  }

  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const expectedSignature = sign(signingInput, secret);
  const actualBuffer = Buffer.from(signature);
  const expectedBuffer = Buffer.from(expectedSignature);
  if (
    actualBuffer.length !== expectedBuffer.length ||
    !timingSafeEqual(actualBuffer, expectedBuffer)
  ) {
    return { valid: false, reason: 'bad-signature' };
  }

  let payload: unknown;
  try {
    payload = JSON.parse(base64UrlDecode(encodedPayload));
  } catch {
    return { valid: false, reason: 'malformed' };
  }
  if (
    typeof payload !== 'object' ||
    payload === null ||
    typeof (payload as Record<string, unknown>).exp !== 'number' ||
    typeof (payload as Record<string, unknown>).iat !== 'number'
  ) {
    return { valid: false, reason: 'malformed' };
  }

  const nowSeconds = Math.floor((options.nowMs ?? Date.now()) / 1000);
  const typedPayload = payload as JwtPayload;
  if (nowSeconds > typedPayload.exp) {
    return { valid: false, reason: 'expired' };
  }

  return { valid: true, payload: typedPayload };
}
