import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createServer, type AddressInfo, type Server } from 'node:net';
import { test } from 'node:test';

import {
  connectAdb,
  connectConfiguredAdb,
  expandPortSpec,
  findDeviceState,
  isPortOpen,
  isValidIpv4,
  orderPortsPriorityFirst,
  parseMdnsPorts,
  resolveAdbHost,
  scanOpenPorts,
} from './adb-connect.js';
import { createMockAdb } from './test-support/mock-adb.js';

const HOST = '127.0.0.1';
const silent = (): void => undefined;

function listen(): Promise<{ server: Server; port: number }> {
  return new Promise((resolve) => {
    const server = createServer();
    server.listen(0, HOST, () => {
      resolve({ server, port: (server.address() as AddressInfo).port });
    });
  });
}

function closeServer(server: Server): Promise<void> {
  return new Promise((resolve) => {
    server.close(() => {
      resolve();
    });
  });
}

async function unusedPort(): Promise<number> {
  const { server, port } = await listen();
  await closeServer(server);
  return port;
}

test('isValidIpv4: akzeptiert gueltige Adressen', () => {
  assert.equal(isValidIpv4('192.168.2.129'), true);
  assert.equal(isValidIpv4('0.0.0.0'), true);
  assert.equal(isValidIpv4('255.255.255.255'), true);
});

test('isValidIpv4: lehnt ungueltige Adressen ab', () => {
  assert.equal(isValidIpv4('256.1.1.1'), false);
  assert.equal(isValidIpv4('01.1.1.1'), false);
  assert.equal(isValidIpv4('1.1.1'), false);
  assert.equal(isValidIpv4('a.b.c.d'), false);
  assert.equal(isValidIpv4(''), false);
});

test('expandPortSpec: expandiert Einzelports und Bereiche', () => {
  assert.deepEqual(expandPortSpec('5555,30000-30002'), [5555, 30000, 30001, 30002]);
  assert.deepEqual(expandPortSpec(' 80 , 90 '), [80, 90]);
});

test('expandPortSpec: wirft bei ungueltigen Angaben', () => {
  assert.throws(() => expandPortSpec('0-10'), /Ungueltiger Portbereich/);
  assert.throws(() => expandPortSpec('10-5'), /Ungueltiger Portbereich/);
  assert.throws(() => expandPortSpec('1-70000'), /Ungueltiger Portbereich/);
  assert.throws(() => expandPortSpec('70000'), /Ungueltiger Port:/);
  assert.throws(() => expandPortSpec('0'), /Ungueltiger Port:/);
  assert.throws(() => expandPortSpec('abc'), /Ungueltige Port-Angabe/);
});

test('orderPortsPriorityFirst: setzt 5555 an den Anfang', () => {
  assert.deepEqual(orderPortsPriorityFirst([31000, 5555, 32000]), [5555, 31000, 32000]);
  assert.deepEqual(orderPortsPriorityFirst([31000, 32000]), [31000, 32000]);
  assert.deepEqual(orderPortsPriorityFirst([]), []);
});

test('parseMdnsPorts: liefert nur Ports der Ziel-IP', () => {
  const output = [
    'List of discovered mdns services',
    'adb-AAA\t_adb-tls-connect._tcp.\t192.168.2.129:37123',
    'adb-BBB\t_adb-tls-connect._tcp.\t192.168.2.50:40000',
    'adb-AAA\t_adb-tls-pairing._tcp.\t192.168.2.129:41000',
    'adb-CCC\t_adb-tls-connect._tcp.\tnoport',
    'adb-DDD\t_adb-tls-connect._tcp.\t192.168.2.129:abc',
    '',
  ].join('\n');
  assert.deepEqual(parseMdnsPorts(output, '192.168.2.129'), [37123]);
  assert.deepEqual(parseMdnsPorts('', '192.168.2.129'), []);
});

test('findDeviceState: unterscheidet device, unauthorized und absent', () => {
  const output =
    'List of devices attached\n1.2.3.4:5555\tdevice\n1.2.3.4:6666\tunauthorized\n1.2.3.4:7777\toffline\n';
  assert.equal(findDeviceState(output, '1.2.3.4', 5555), 'device');
  assert.equal(findDeviceState(output, '1.2.3.4', 6666), 'unauthorized');
  assert.equal(findDeviceState(output, '1.2.3.4', 7777), 'absent');
  assert.equal(findDeviceState(output, '1.2.3.4', 8888), 'absent');
});

test('isPortOpen: erkennt offene und geschlossene Ports', async () => {
  const { server, port } = await listen();
  try {
    assert.equal(await isPortOpen(HOST, port, 500), true);
  } finally {
    await closeServer(server);
  }
  assert.equal(await isPortOpen(HOST, port, 500), false);
});

test('scanOpenPorts: liefert offene Ports in Eingabereihenfolge', async () => {
  const first = await listen();
  const second = await listen();
  const closed = await unusedPort();
  try {
    const result = await scanOpenPorts(HOST, [second.port, closed, first.port], 500, 2);
    assert.deepEqual(result, [second.port, first.port]);
  } finally {
    await closeServer(first.server);
    await closeServer(second.server);
  }
});

test('scanOpenPorts: leere Portliste liefert leeres Ergebnis', async () => {
  assert.deepEqual(await scanOpenPorts(HOST, [], 100, 10), []);
});

test('connectAdb: verbindet ueber mDNS-Port', async () => {
  const adb = createMockAdb({
    mdnsOutput: `adb-X\t_adb-tls-connect._tcp.\t${HOST}:40123`,
    devicesOutput: `List of devices attached\n${HOST}:40123\tdevice\n`,
  });
  try {
    const result = await connectAdb(`${adb.binDir}/adb`, HOST, { log: silent });
    assert.equal(result, 'connected');
    const log = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    assert.deepEqual(log, ['mdns services', `connect ${HOST}:40123`, 'devices']);
  } finally {
    adb.cleanup();
  }
});

test('connectAdb: meldet unauthorisierte Geraete', async () => {
  const adb = createMockAdb({
    mdnsOutput: `adb-X\t_adb-tls-connect._tcp.\t${HOST}:40123`,
    devicesOutput: `List of devices attached\n${HOST}:40123\tunauthorized\n`,
  });
  const messages: string[] = [];
  try {
    const result = await connectAdb(`${adb.binDir}/adb`, HOST, {
      log: (message) => messages.push(message),
    });
    assert.equal(result, 'unauthorized');
    assert.ok(messages.some((message) => message.includes('nicht autorisiert')));
  } finally {
    adb.cleanup();
  }
});

test('connectAdb: faellt ohne mDNS-Treffer auf den Portscan zurueck', async () => {
  const { server, port } = await listen();
  const adb = createMockAdb({
    devicesOutput: `List of devices attached\n${HOST}:${String(port)}\tdevice\n`,
  });
  try {
    const result = await connectAdb(`${adb.binDir}/adb`, HOST, {
      log: silent,
      portSpec: String(port),
    });
    assert.equal(result, 'connected');
    const log = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    assert.deepEqual(log, ['mdns services', `connect ${HOST}:${String(port)}`, 'devices']);
  } finally {
    await closeServer(server);
    adb.cleanup();
  }
});

test('connectAdb: useMdns=false ueberspringt die mDNS-Suche', async () => {
  const { server, port } = await listen();
  const adb = createMockAdb({
    devicesOutput: `List of devices attached\n${HOST}:${String(port)}\tdevice\n`,
  });
  try {
    await connectAdb(`${adb.binDir}/adb`, HOST, { log: silent, portSpec: String(port), useMdns: false });
    const log = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    assert.equal(log[0], `connect ${HOST}:${String(port)}`);
  } finally {
    await closeServer(server);
    adb.cleanup();
  }
});

test('connectAdb: faellt nach fehlgeschlagenem mDNS-Connect auf den Portscan zurueck', async () => {
  const { server, port } = await listen();
  const adb = createMockAdb({
    mdnsOutput: `adb-X\t_adb-tls-connect._tcp.\t${HOST}:1`,
    devicesOutput: `List of devices attached\n${HOST}:${String(port)}\tdevice\n`,
  });
  try {
    const result = await connectAdb(`${adb.binDir}/adb`, HOST, { log: silent, portSpec: String(port) });
    assert.equal(result, 'connected');
    const log = readFileSync(adb.logFile, 'utf8').trim().split('\n');
    assert.ok(log.includes(`connect ${HOST}:1`));
    assert.ok(log.includes(`connect ${HOST}:${String(port)}`));
  } finally {
    await closeServer(server);
    adb.cleanup();
  }
});

test('connectAdb: wirft ohne offenen Port', async () => {
  const adb = createMockAdb();
  try {
    await assert.rejects(
      connectAdb(`${adb.binDir}/adb`, HOST, {
        log: silent,
        portSpec: String(await unusedPort()),
        useMdns: false,
      }),
      /Kein offener Port/,
    );
  } finally {
    adb.cleanup();
  }
});

test('connectAdb: wirft, wenn adb keine Verbindung herstellt', async () => {
  const { server, port } = await listen();
  const adb = createMockAdb();
  try {
    await assert.rejects(
      connectAdb(`${adb.binDir}/adb`, HOST, { log: silent, portSpec: String(port), useMdns: false }),
      /keine ADB-Verbindung/,
    );
  } finally {
    await closeServer(server);
    adb.cleanup();
  }
});

test('connectAdb: wirft bei ungueltiger IP und ungueltiger Portangabe', async () => {
  await assert.rejects(connectAdb('adb', 'nope', { log: silent }), /keine gueltige IPv4-Adresse/);
  await assert.rejects(connectAdb('adb', HOST, { log: silent, portSpec: 'x' }), /Ungueltige Port-Angabe/);
});

test('connectAdb: nicht startbares adb wird wie eine leere Ausgabe behandelt', async () => {
  await assert.rejects(
    connectAdb('/nonexistent/adb', HOST, {
      log: silent,
      portSpec: String(await unusedPort()),
    }),
    /Kein offener Port/,
  );
});

test('resolveAdbHost: explizite IP hat Vorrang vor der Umgebung', () => {
  assert.equal(resolveAdbHost('1.2.3.4', { CL_ADB_HOST: '5.6.7.8' }), '1.2.3.4');
  assert.equal(resolveAdbHost(undefined, { CL_ADB_HOST: ' 5.6.7.8 ' }), '5.6.7.8');
  assert.equal(resolveAdbHost(undefined, { CL_ADB_HOST: '' }), undefined);
  assert.equal(resolveAdbHost(undefined, {}), undefined);
});

test('connectConfiguredAdb: tut ohne konfigurierten Host nichts', async () => {
  const adb = createMockAdb();
  try {
    await connectConfiguredAdb(`${adb.binDir}/adb`, {});
    assert.throws(() => readFileSync(adb.logFile, 'utf8'));
  } finally {
    adb.cleanup();
  }
});

test('connectConfiguredAdb: Fehler werden geloggt, aber nicht geworfen', async () => {
  const errors: unknown[] = [];
  const original = console.error;
  console.error = (...args: unknown[]) => {
    errors.push(args[0]);
  };
  try {
    await connectConfiguredAdb('adb', { CL_ADB_HOST: 'nope' });
  } finally {
    console.error = original;
  }
  assert.equal(errors.length, 1);
  assert.match(String(errors[0]), /adb connect zu nope fehlgeschlagen/);
});
