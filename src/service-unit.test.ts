import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  DEFAULT_SERVICE_PORT,
  renderServiceUnit,
  SERVICE_NAME,
  type ServiceUnitParams,
} from './service-unit.js';

const params: ServiceUnitParams = {
  user: 'sklein',
  group: 'sklein',
  homeDir: '/home/sklein',
  rootDir: '/home/sklein/IdeaProjects/claude-cli',
  executable: '/home/sklein/.local/bin/cl',
  nodeDir: '/home/sklein/.nvm/versions/node/v24.21.0/bin',
  port: 7765,
};

test('renderServiceUnit: uebernimmt Nutzer, Pfade und Port', () => {
  const unit = renderServiceUnit(params);
  assert.match(unit, /^User=sklein$/m);
  assert.match(unit, /^Group=sklein$/m);
  assert.match(unit, /^WorkingDirectory=\/home\/sklein\/IdeaProjects\/claude-cli$/m);
  assert.match(unit, /^Environment=CL_ROOT_DIR=\/home\/sklein\/IdeaProjects\/claude-cli$/m);
  assert.match(unit, /^Environment=HOME=\/home\/sklein$/m);
  assert.match(unit, /^Environment=PORT=7765$/m);
  assert.match(unit, /^ExecStart=\/home\/sklein\/\.local\/bin\/cl server$/m);
  assert.match(
    unit,
    /^Environment=PATH=\/home\/sklein\/\.nvm\/versions\/node\/v24\.21\.0\/bin:\/home\/sklein\/\.local\/bin:/m,
  );
});

test('renderServiceUnit: startet unbegrenzt und frueh neu', () => {
  const unit = renderServiceUnit(params);
  assert.match(unit, /^Restart=always$/m);
  assert.match(unit, /^RestartSec=1$/m);
  assert.match(unit, /^StartLimitIntervalSec=0$/m);
  assert.match(unit, /^WantedBy=multi-user\.target$/m);
  assert.match(unit, /^OOMPolicy=continue$/m);
});

test('renderServiceUnit: wartet nicht auf network-online.target', () => {
  const unit = renderServiceUnit(params);
  assert.ok(!unit.includes('network-online.target'));
  assert.match(unit, /^After=network\.target$/m);
});

test('renderServiceUnit: lehnt leere Werte ab', () => {
  assert.throws(() => renderServiceUnit({ ...params, user: '' }), /Leerer Wert fuer "user"/);
  assert.throws(() => renderServiceUnit({ ...params, rootDir: ' ' }), /Leerer Wert fuer "rootDir"/);
  assert.throws(() => renderServiceUnit({ ...params, nodeDir: '' }), /Leerer Wert fuer "nodeDir"/);
});

test('renderServiceUnit: lehnt ungueltige Ports ab', () => {
  for (const port of [0, -1, 65536, 1.5, Number.NaN]) {
    assert.throws(() => renderServiceUnit({ ...params, port }), /Ungueltiger Port/);
  }
});

test('Konstanten fuer Unit-Datei und Service-Port', () => {
  assert.equal(SERVICE_NAME, 'cl-server.service');
  assert.equal(DEFAULT_SERVICE_PORT, 7765);
});
