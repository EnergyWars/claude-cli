import assert from 'node:assert/strict';
import { test } from 'node:test';

import { isLocalNetworkAddress } from './network.js';

test('isLocalNetworkAddress: Loopback', () => {
  assert.equal(isLocalNetworkAddress('127.0.0.1'), true);
  assert.equal(isLocalNetworkAddress('::1'), true);
  assert.equal(isLocalNetworkAddress('127.5.5.5'), true);
});

test('isLocalNetworkAddress: private RFC1918-Bereiche', () => {
  assert.equal(isLocalNetworkAddress('10.0.0.5'), true);
  assert.equal(isLocalNetworkAddress('192.168.1.20'), true);
  assert.equal(isLocalNetworkAddress('172.16.0.1'), true);
  assert.equal(isLocalNetworkAddress('172.31.255.255'), true);
  assert.equal(isLocalNetworkAddress('172.32.0.1'), false);
  assert.equal(isLocalNetworkAddress('172.15.255.255'), false);
});

test('isLocalNetworkAddress: IPv6 ULA/link-local', () => {
  assert.equal(isLocalNetworkAddress('fe80::1'), true);
  assert.equal(isLocalNetworkAddress('fd12:3456::1'), true);
  assert.equal(isLocalNetworkAddress('fc00::1'), true);
});

test('isLocalNetworkAddress: oeffentliche Adressen werden abgelehnt', () => {
  assert.equal(isLocalNetworkAddress('8.8.8.8'), false);
  assert.equal(isLocalNetworkAddress('2001:4860:4860::8888'), false);
  assert.equal(isLocalNetworkAddress(undefined), false);
});

test('isLocalNetworkAddress: IPv4-mapped IPv6 (::ffff:) wird entpackt', () => {
  assert.equal(isLocalNetworkAddress('::ffff:127.0.0.1'), true);
  assert.equal(isLocalNetworkAddress('::ffff:8.8.8.8'), false);
});
