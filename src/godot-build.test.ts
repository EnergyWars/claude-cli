import assert from 'node:assert/strict';
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import {
  GODOT_BIN_ENV_VAR,
  hasGodotErrors,
  isGodotProject,
  parseAndroidExportPreset,
  readAndroidExportPreset,
  resolveGodotExecutable,
} from './godot-build.js';

const PRESETS = [
  '[preset.0]',
  '',
  'name="Web"',
  'platform="Web"',
  'export_path="build/web/index.html"',
  '',
  '[preset.0.options]',
  '',
  'name="ignored"',
  '',
  '[preset.1]',
  '',
  'name="Android"',
  'platform="Android"',
  'export_path="build/game.apk"',
  '',
  '[preset.1.options]',
  '',
  'package/unique_name="com.example.game"',
].join('\n');

test('parseAndroidExportPreset: waehlt das erste Android-Preset und ignoriert Options-Sektionen', () => {
  assert.deepEqual(parseAndroidExportPreset(PRESETS), { name: 'Android', exportPath: 'build/game.apk' });
});

test('parseAndroidExportPreset: null ohne Android-Preset', () => {
  assert.equal(parseAndroidExportPreset('[preset.0]\nname="Web"\nplatform="Web"\nexport_path="x"\n'), null);
});

test('parseAndroidExportPreset: null bei leerem export_path', () => {
  assert.equal(
    parseAndroidExportPreset('[preset.0]\nname="A"\nplatform="Android"\nexport_path=""\n'),
    null,
  );
});

test('parseAndroidExportPreset: null bei leerem Inhalt', () => {
  assert.equal(parseAndroidExportPreset(''), null);
});

test('readAndroidExportPreset: liest die Datei im Projektverzeichnis', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-godot-preset-'));
  try {
    writeFileSync(join(cwd, 'export_presets.cfg'), PRESETS);
    assert.deepEqual(readAndroidExportPreset(cwd), { name: 'Android', exportPath: 'build/game.apk' });
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('readAndroidExportPreset: wirft ohne Datei', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-godot-preset-'));
  try {
    assert.throws(() => readAndroidExportPreset(cwd), /export_presets\.cfg fehlt/);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('readAndroidExportPreset: wirft ohne Android-Preset', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-godot-preset-'));
  try {
    writeFileSync(join(cwd, 'export_presets.cfg'), '[preset.0]\nname="Web"\nplatform="Web"\n');
    assert.throws(() => readAndroidExportPreset(cwd), /Kein Android-Export-Preset/);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('isGodotProject: erkennt project.godot', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-godot-detect-'));
  try {
    assert.equal(isGodotProject(cwd), false);
    writeFileSync(join(cwd, 'project.godot'), '');
    assert.equal(isGodotProject(cwd), true);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('hasGodotErrors: erkennt ERROR und SCRIPT ERROR, ignoriert WARNING', () => {
  assert.equal(hasGodotErrors('ok\nERROR: boom\n'), true);
  assert.equal(hasGodotErrors('SCRIPT ERROR: Parse Error\n'), true);
  assert.equal(hasGodotErrors('WARNING: leaked\nExport done\n'), false);
  assert.equal(hasGodotErrors(''), false);
});

function withEnv<T>(env: Record<string, string | undefined>, run: () => T): T {
  const previous = Object.fromEntries(Object.keys(env).map((key) => [key, process.env[key]]));
  for (const [key, value] of Object.entries(env)) {
    if (value === undefined) {
      Reflect.deleteProperty(process.env, key);
    } else {
      process.env[key] = value;
    }
  }
  try {
    return run();
  } finally {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) {
        Reflect.deleteProperty(process.env, key);
      } else {
        process.env[key] = value;
      }
    }
  }
}

test('resolveGodotExecutable: GODOT_BIN hat Vorrang', () => {
  withEnv({ [GODOT_BIN_ENV_VAR]: '/opt/custom/godot' }, () => {
    assert.equal(resolveGodotExecutable(), '/opt/custom/godot');
  });
});

test('resolveGodotExecutable: nimmt godot aus dem PATH', () => {
  const binDir = mkdtempSync(join(tmpdir(), 'cl-godot-path-'));
  try {
    writeFileSync(join(binDir, 'godot'), '#!/bin/sh\n');
    chmodSync(join(binDir, 'godot'), 0o755);
    withEnv({ [GODOT_BIN_ENV_VAR]: undefined, PATH: binDir }, () => {
      assert.equal(resolveGodotExecutable(), 'godot');
    });
  } finally {
    rmSync(binDir, { recursive: true, force: true });
  }
});

test('resolveGodotExecutable: nimmt godot4 aus dem PATH', () => {
  const binDir = mkdtempSync(join(tmpdir(), 'cl-godot-path-'));
  try {
    writeFileSync(join(binDir, 'godot4'), '#!/bin/sh\n');
    withEnv({ [GODOT_BIN_ENV_VAR]: undefined, PATH: binDir }, () => {
      assert.equal(resolveGodotExecutable(), 'godot4');
    });
  } finally {
    rmSync(binDir, { recursive: true, force: true });
  }
});

test('resolveGodotExecutable: findet die hoechste Version unter ~/.local/opt/godot-*', () => {
  const home = mkdtempSync(join(tmpdir(), 'cl-godot-home-'));
  try {
    for (const version of ['godot-4.3', 'godot-4.10', 'godot-4.7.2']) {
      const dir = join(home, '.local', 'opt', version);
      mkdirSync(dir, { recursive: true });
      writeFileSync(join(dir, `Godot_v${version.slice(6)}-stable_linux.x86_64`), '');
      writeFileSync(join(dir, `Godot_v${version.slice(6)}-stable_linux.x86_64_console`), '');
    }
    withEnv({ [GODOT_BIN_ENV_VAR]: undefined, PATH: '', HOME: home }, () => {
      assert.equal(
        resolveGodotExecutable(),
        join(home, '.local', 'opt', 'godot-4.10', 'Godot_v4.10-stable_linux.x86_64'),
      );
    });
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('resolveGodotExecutable: ueberspringt Versionsordner ohne Binary', () => {
  const home = mkdtempSync(join(tmpdir(), 'cl-godot-home-'));
  try {
    mkdirSync(join(home, '.local', 'opt', 'godot-9.0'), { recursive: true });
    const dir = join(home, '.local', 'opt', 'godot-4.7.2');
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'Godot_v4.7.2-stable_linux.x86_64'), '');
    withEnv({ [GODOT_BIN_ENV_VAR]: undefined, PATH: '', HOME: home }, () => {
      assert.equal(resolveGodotExecutable(), join(dir, 'Godot_v4.7.2-stable_linux.x86_64'));
    });
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});

test('resolveGodotExecutable: Fallback "godot" ohne Treffer', () => {
  const home = mkdtempSync(join(tmpdir(), 'cl-godot-home-'));
  try {
    withEnv({ [GODOT_BIN_ENV_VAR]: undefined, PATH: '', HOME: home }, () => {
      assert.equal(resolveGodotExecutable(), 'godot');
    });
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});
