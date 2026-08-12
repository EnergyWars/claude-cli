import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  base32Decode,
  base32Encode,
  buildOtpAuthUrl,
  generateSecret,
  generateTotp,
  verifyTotp,
} from './totp.js';

test('base32Encode/base32Decode: Rundreise fuer beliebige Bytes', () => {
  const original = Buffer.from([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 255, 254]);
  const encoded = base32Encode(original);
  assert.match(encoded, /^[A-Z2-7]+$/);
  assert.deepEqual(base32Decode(encoded), original);
});

test('base32Decode: wirft bei ungueltigem Zeichen', () => {
  assert.throws(() => base32Decode('1'), /Ungueltiges Base32-Zeichen/);
});

test('generateSecret: liefert ein 32-stelliges Base32-Secret ohne Padding', () => {
  const secret = generateSecret();
  assert.equal(secret.length, 32);
  assert.match(secret, /^[A-Z2-7]+$/);
  assert.notEqual(generateSecret(), secret);
});

test('generateTotp/verifyTotp: gueltiger Code zum selben Zeitpunkt wird akzeptiert', () => {
  const secret = generateSecret();
  const timestampMs = 1_700_000_000_000;
  const code = generateTotp(secret, timestampMs);
  assert.match(code, /^\d{6}$/);
  assert.equal(verifyTotp(secret, code, { timestampMs }), true);
});

test('verifyTotp: Code aus dem vorherigen Zeitfenster wird noch akzeptiert (Clock-Drift-Toleranz)', () => {
  const secret = generateSecret();
  const timestampMs = 1_700_000_000_000;
  const previousCode = generateTotp(secret, timestampMs - 30_000);
  assert.equal(verifyTotp(secret, previousCode, { timestampMs }), true);
});

test('verifyTotp: Code weit ausserhalb des Zeitfensters wird abgelehnt', () => {
  const secret = generateSecret();
  const timestampMs = 1_700_000_000_000;
  const oldCode = generateTotp(secret, timestampMs - 10 * 60_000);
  assert.equal(verifyTotp(secret, oldCode, { timestampMs }), false);
});

test('verifyTotp: falsches Secret wird abgelehnt', () => {
  const secretA = generateSecret();
  const secretB = generateSecret();
  const timestampMs = 1_700_000_000_000;
  const code = generateTotp(secretA, timestampMs);
  assert.equal(verifyTotp(secretB, code, { timestampMs }), false);
});

test('verifyTotp: ungueltiges Format (nicht 6 Ziffern) wird abgelehnt', () => {
  const secret = generateSecret();
  assert.equal(verifyTotp(secret, '12345'), false);
  assert.equal(verifyTotp(secret, 'abcdef'), false);
});

test('buildOtpAuthUrl: enthaelt Secret, Issuer, Digits, Period', () => {
  const url = buildOtpAuthUrl('ABCDEFGHIJKLMNOPQRST234567234567', 'cl-server', 'cl');
  assert.match(url, /^otpauth:\/\/totp\/cl%3Acl-server\?/);
  assert.match(url, /secret=ABCDEFGHIJKLMNOPQRST234567234567/);
  assert.match(url, /issuer=cl/);
  assert.match(url, /digits=6/);
  assert.match(url, /period=30/);
});
