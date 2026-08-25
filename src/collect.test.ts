import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import type { Config } from './config.js';
import {
  collectAll,
  collectOne,
  listCollectedFiles,
  resolveCollectedFilePath,
} from './collect.js';

function baseConfig(overrides: Partial<Config> = {}): Config {
  return {
    main: { description: 'm', contexts: [], model: 'sonnet' },
    agents: [],
    databaseDirectory: '/tmp/db',
    paths: [],
    tasks: [],
    ticketAgent: { model: 'haiku', task: 't' },
    contentPath: '/tmp/content',
    collection: [],
    ...overrides,
  };
}

function withTempDir<T>(prefix: string, fn: (dir: string) => T): T {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  try {
    return fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('collectAll: kopiert alle Eintraege, haengt Extension der Quelle an', () => {
  withTempDir('cl-collect-', (dir) => {
    const contentPath = join(dir, 'content');
    writeFileSync(join(dir, 'app.apk'), 'FAKE-APK');
    const config = baseConfig({
      contentPath,
      collection: [{ sourcePath: join(dir, 'app.apk'), targetName: 'test' }],
    });

    const summary = collectAll(config);

    assert.deepEqual(summary.errors, []);
    assert.deepEqual(summary.results, [{ targetName: 'test', fileName: 'test.apk', status: 'ok' }]);
    assert.equal(readFileSync(join(contentPath, 'test.apk'), 'utf8'), 'FAKE-APK');
  });
});

test('collectAll: haengt keine doppelte Extension an, falls targetName sie schon traegt', () => {
  withTempDir('cl-collect-', (dir) => {
    const contentPath = join(dir, 'content');
    writeFileSync(join(dir, 'app.apk'), 'FAKE-APK');
    const config = baseConfig({
      contentPath,
      collection: [{ sourcePath: join(dir, 'app.apk'), targetName: 'test.apk' }],
    });

    const summary = collectAll(config);

    assert.deepEqual(summary.results, [
      { targetName: 'test.apk', fileName: 'test.apk', status: 'ok' },
    ]);
  });
});

test('collectAll: fehlende Quelldatei landet in errors, andere Eintraege laufen trotzdem durch', () => {
  withTempDir('cl-collect-', (dir) => {
    const contentPath = join(dir, 'content');
    writeFileSync(join(dir, 'app.apk'), 'FAKE-APK');
    const config = baseConfig({
      contentPath,
      collection: [
        { sourcePath: join(dir, 'missing.apk'), targetName: 'missing' },
        { sourcePath: join(dir, 'app.apk'), targetName: 'ok' },
      ],
    });

    const summary = collectAll(config);

    assert.equal(summary.results.length, 1);
    assert.equal(summary.results[0]?.targetName, 'ok');
    assert.equal(summary.errors.length, 1);
    const error = summary.errors[0];
    assert.ok(error);
    assert.equal(error.targetName, 'missing');
    assert.match(error.error, /wurde nicht gefunden/);
  });
});

test('collectOne: kopiert nur den passenden Eintrag', () => {
  withTempDir('cl-collect-', (dir) => {
    const contentPath = join(dir, 'content');
    writeFileSync(join(dir, 'a.apk'), 'A');
    writeFileSync(join(dir, 'b.apk'), 'B');
    const config = baseConfig({
      contentPath,
      collection: [
        { sourcePath: join(dir, 'a.apk'), targetName: 'a' },
        { sourcePath: join(dir, 'b.apk'), targetName: 'b' },
      ],
    });

    const summary = collectOne(config, 'b');

    assert.deepEqual(summary.results, [{ targetName: 'b', fileName: 'b.apk', status: 'ok' }]);
    assert.equal(existsSync(join(contentPath, 'a.apk')), false);
    assert.equal(readFileSync(join(contentPath, 'b.apk'), 'utf8'), 'B');
  });
});

test('collectOne: wirft bei unbekanntem targetName', () => {
  const config = baseConfig();
  assert.throws(() => collectOne(config, 'doesnotexist'), /wurde in config\.json nicht gefunden/);
});

test('listCollectedFiles: neueste zuerst, nur Dateien', () => {
  withTempDir('cl-collect-', (dir) => {
    const contentPath = join(dir, 'content');
    const config = baseConfig({
      contentPath,
      collection: [
        { sourcePath: join(dir, 'missing1.apk'), targetName: 'a' },
        { sourcePath: join(dir, 'missing2.apk'), targetName: 'b' },
      ],
    });
    // ensures the directory exists even without any successful collect
    collectAll(config);
    writeFileSync(join(contentPath, 'a.apk'), 'A');
    writeFileSync(join(contentPath, 'b.apk'), 'B');

    const files = listCollectedFiles(contentPath);
    assert.deepEqual(
      files.map((f) => f.name).sort(),
      ['a.apk', 'b.apk'],
    );
    for (const file of files) {
      assert.match(file.timestamp, /^\d{4}-\d{2}-\d{2}T/);
    }
  });
});

test('resolveCollectedFilePath: loest gueltigen Namen auf', () => {
  withTempDir('cl-collect-', (dir) => {
    const filePath = resolveCollectedFilePath(dir, 'a.apk');
    assert.equal(filePath, join(dir, 'a.apk'));
  });
});

test('resolveCollectedFilePath: wirft bei Pfad-Traversal', () => {
  withTempDir('cl-collect-', (dir) => {
    assert.throws(() => resolveCollectedFilePath(dir, '../escaped'), /Ungueltiger Dateiname/);
  });
});
