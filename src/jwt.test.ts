import assert from 'node:assert/strict';
import { test } from 'node:test';

import { signJwt, verifyJwt } from './jwt.js';

const SECRET = 'test-secret-value';
const NOW_MS = 1_700_000_000_000;

test('signJwt/verifyJwt: gueltiges Token wird akzeptiert und Payload kommt zurueck', () => {
  const token = signJwt({ foo: 'bar' }, SECRET, { expiresInSeconds: 3600, nowMs: NOW_MS });
  assert.match(token, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  const result = verifyJwt(token, SECRET, { nowMs: NOW_MS });
  assert.equal(result.valid, true);
  assert.equal(result.payload.foo, 'bar');
  assert.equal(result.payload.iat, Math.floor(NOW_MS / 1000));
  assert.equal(result.payload.exp, Math.floor(NOW_MS / 1000) + 3600);
});

test('verifyJwt: manipulierte Signatur wird abgelehnt', () => {
  const token = signJwt({}, SECRET, { expiresInSeconds: 3600, nowMs: NOW_MS });
  const [header, payload, signature] = token.split('.') as [string, string, string];
  const tampered = `${header}.${payload}.${signature.slice(0, -1)}A`;
  const result = verifyJwt(tampered, SECRET, { nowMs: NOW_MS });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'bad-signature');
});

test('verifyJwt: manipuliertes Payload wird abgelehnt (Signatur passt nicht mehr)', () => {
  const token = signJwt({ role: 'user' }, SECRET, { expiresInSeconds: 3600, nowMs: NOW_MS });
  const [header, , signature] = token.split('.') as [string, string, string];
  const forgedPayload = Buffer.from(JSON.stringify({ role: 'admin', iat: 0, exp: 9_999_999_999 }))
    .toString('base64url');
  const tampered = `${header}.${forgedPayload}.${signature}`;
  const result = verifyJwt(tampered, SECRET, { nowMs: NOW_MS });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'bad-signature');
});

test('verifyJwt: abgelaufenes Token wird abgelehnt', () => {
  const token = signJwt({}, SECRET, { expiresInSeconds: 60, nowMs: NOW_MS });
  const result = verifyJwt(token, SECRET, { nowMs: NOW_MS + 61_000 });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'expired');
});

test('verifyJwt: Token exakt im gueltigen Zeitfenster wird noch akzeptiert', () => {
  const token = signJwt({}, SECRET, { expiresInSeconds: 60, nowMs: NOW_MS });
  const result = verifyJwt(token, SECRET, { nowMs: NOW_MS + 60_000 });
  assert.equal(result.valid, true);
});

test('verifyJwt: falsches Secret wird abgelehnt', () => {
  const token = signJwt({}, SECRET, { expiresInSeconds: 3600, nowMs: NOW_MS });
  const result = verifyJwt(token, 'anderes-secret', { nowMs: NOW_MS });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'bad-signature');
});

test('verifyJwt: alg:none wird abgelehnt (keine Alg-Confusion)', () => {
  const forgedHeader = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString(
    'base64url',
  );
  const forgedPayload = Buffer.from(
    JSON.stringify({ iat: Math.floor(NOW_MS / 1000), exp: Math.floor(NOW_MS / 1000) + 3600 }),
  ).toString('base64url');
  const forgedToken = `${forgedHeader}.${forgedPayload}.`;
  const result = verifyJwt(forgedToken, SECRET, { nowMs: NOW_MS });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'alg');
});

test('verifyJwt: alg:HS512 wird abgelehnt', () => {
  const forgedHeader = Buffer.from(JSON.stringify({ alg: 'HS512', typ: 'JWT' })).toString(
    'base64url',
  );
  const forgedPayload = Buffer.from(
    JSON.stringify({ iat: Math.floor(NOW_MS / 1000), exp: Math.floor(NOW_MS / 1000) + 3600 }),
  ).toString('base64url');
  const forgedToken = `${forgedHeader}.${forgedPayload}.forged`;
  const result = verifyJwt(forgedToken, SECRET, { nowMs: NOW_MS });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'alg');
});

test('verifyJwt: fehlerhaft geformtes Token (nicht 3 Teile) wird abgelehnt', () => {
  const result = verifyJwt('nur.zweiteile', SECRET, { nowMs: NOW_MS });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'malformed');
});
