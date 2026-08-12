import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const SECRET_BYTES = 20;
const CODE_DIGITS = 6;
const STEP_SECONDS = 30;
const DEFAULT_WINDOW = 1;

export function base32Encode(buffer: Buffer): string {
  let bits = '';
  for (const byte of buffer) {
    bits += byte.toString(2).padStart(8, '0');
  }

  let output = '';
  for (let i = 0; i + 5 <= bits.length; i += 5) {
    output += BASE32_ALPHABET.charAt(parseInt(bits.slice(i, i + 5), 2));
  }
  const remainder = bits.length % 5;
  if (remainder !== 0) {
    const lastChunk = bits.slice(bits.length - remainder).padEnd(5, '0');
    output += BASE32_ALPHABET.charAt(parseInt(lastChunk, 2));
  }
  return output;
}

export function base32Decode(value: string): Buffer {
  const cleaned = value.toUpperCase().replace(/\s+/g, '');
  let bits = '';
  for (const char of cleaned) {
    const index = BASE32_ALPHABET.indexOf(char);
    if (index === -1) {
      throw new Error(`Ungueltiges Base32-Zeichen: "${char}"`);
    }
    bits += index.toString(2).padStart(5, '0');
  }

  const bytes: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    bytes.push(parseInt(bits.slice(i, i + 8), 2));
  }
  return Buffer.from(bytes);
}

export function generateSecret(): string {
  return base32Encode(randomBytes(SECRET_BYTES));
}

function hotp(secret: Buffer, counter: bigint): string {
  const counterBuffer = Buffer.alloc(8);
  counterBuffer.writeBigUInt64BE(counter);
  const hmac = createHmac('sha1', secret).update(counterBuffer).digest();

  const offset = hmac.readUInt8(hmac.length - 1) & 0x0f;
  const binaryCode =
    ((hmac.readUInt8(offset) & 0x7f) << 24) |
    ((hmac.readUInt8(offset + 1) & 0xff) << 16) |
    ((hmac.readUInt8(offset + 2) & 0xff) << 8) |
    (hmac.readUInt8(offset + 3) & 0xff);

  return (binaryCode % 10 ** CODE_DIGITS).toString().padStart(CODE_DIGITS, '0');
}

export function generateTotp(secretBase32: string, timestampMs = Date.now()): string {
  const counter = BigInt(Math.floor(timestampMs / 1000 / STEP_SECONDS));
  return hotp(base32Decode(secretBase32), counter);
}

export function verifyTotp(
  secretBase32: string,
  code: string,
  options: { timestampMs?: number; window?: number } = {},
): boolean {
  if (!/^\d{6}$/.test(code)) {
    return false;
  }

  const timestampMs = options.timestampMs ?? Date.now();
  const window = options.window ?? DEFAULT_WINDOW;
  const secret = base32Decode(secretBase32);
  const currentCounter = BigInt(Math.floor(timestampMs / 1000 / STEP_SECONDS));
  const codeBuffer = Buffer.from(code);

  for (let offset = -window; offset <= window; offset += 1) {
    const candidate = Buffer.from(hotp(secret, currentCounter + BigInt(offset)));
    if (candidate.length === codeBuffer.length && timingSafeEqual(candidate, codeBuffer)) {
      return true;
    }
  }
  return false;
}

export function buildOtpAuthUrl(secretBase32: string, accountName: string, issuer: string): string {
  const label = encodeURIComponent(`${issuer}:${accountName}`);
  const params = new URLSearchParams({
    secret: secretBase32,
    issuer,
    algorithm: 'SHA1',
    digits: String(CODE_DIGITS),
    period: String(STEP_SECONDS),
  });
  return `otpauth://totp/${label}?${params.toString()}`;
}
