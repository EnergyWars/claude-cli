import assert from 'node:assert/strict';
import { test } from 'node:test';

import { DEFAULT_SERVER_PORT, resolveServerPort } from './server-port.js';

test('resolveServerPort: ohne Option und ohne PORT den Default', () => {
  assert.equal(resolveServerPort(undefined, {}), DEFAULT_SERVER_PORT);
});

test('resolveServerPort: PORT aus der Umgebung', () => {
  assert.equal(resolveServerPort(undefined, { PORT: '7765' }), 7765);
});

test('resolveServerPort: --port schlaegt PORT', () => {
  assert.equal(resolveServerPort('9000', { PORT: '7765' }), 9000);
});

test('resolveServerPort: leeres PORT faellt auf den Default zurueck', () => {
  assert.equal(resolveServerPort(undefined, { PORT: '   ' }), DEFAULT_SERVER_PORT);
});

test('resolveServerPort: Port 0 waehlt einen freien Port', () => {
  assert.equal(resolveServerPort(undefined, { PORT: '0' }), 0);
  assert.equal(resolveServerPort('0', {}), 0);
});

test('resolveServerPort: Umgebungswert wird getrimmt', () => {
  assert.equal(resolveServerPort(undefined, { PORT: ' 7765 ' }), 7765);
});

test('resolveServerPort: leere --port-Option faellt auf den Default zurueck', () => {
  assert.equal(resolveServerPort('', { PORT: '7765' }), DEFAULT_SERVER_PORT);
});

test('resolveServerPort: ungueltige Werte werfen', () => {
  for (const value of ['abc', '-1', '65536', '80.5']) {
    assert.throws(() => resolveServerPort(value, {}), /Ungueltiger Port/);
    assert.throws(() => resolveServerPort(undefined, { PORT: value }), /Ungueltiger Port/);
  }
});
