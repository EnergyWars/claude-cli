import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { test } from 'node:test';
import { DatabaseSync } from 'node:sqlite';

import { openDatabase, listSystemMetrics } from './db.js';
import {
  computeCpuLoadPercent,
  computeMemoryUsedPercent,
  sampleSystemMetrics,
  startSystemMetricsLogger,
} from './metrics.js';

test('computeCpuLoadPercent: Load-Average geteilt durch Kernanzahl, als Prozent', () => {
  assert.equal(computeCpuLoadPercent(2, 4), 50);
  assert.equal(computeCpuLoadPercent(4, 4), 100);
  assert.equal(computeCpuLoadPercent(0, 4), 0);
});

test('computeCpuLoadPercent: kann ueber 100 liegen (Ueberlast), wird nicht gekappt', () => {
  assert.equal(computeCpuLoadPercent(8, 4), 200);
});

test('computeCpuLoadPercent: 0 Kerne liefert 0 statt Division durch 0', () => {
  assert.equal(computeCpuLoadPercent(4, 0), 0);
});

test('computeCpuLoadPercent: negative Load-Average liefert nie negatives Ergebnis', () => {
  assert.equal(computeCpuLoadPercent(-1, 4), 0);
});

test('computeMemoryUsedPercent: berechnet Prozentsatz aus total/free', () => {
  assert.equal(computeMemoryUsedPercent(1000, 400), 60);
  assert.equal(computeMemoryUsedPercent(1000, 1000), 0);
  assert.equal(computeMemoryUsedPercent(1000, 0), 100);
});

test('computeMemoryUsedPercent: 0 Gesamtspeicher liefert 0 statt Division durch 0', () => {
  assert.equal(computeMemoryUsedPercent(0, 0), 0);
});

test('sampleSystemMetrics: liefert plausible Werte aus dem echten Betriebssystem', () => {
  const snapshot = sampleSystemMetrics();
  assert.ok(snapshot.cpuPercent >= 0);
  assert.ok(snapshot.memUsedPercent >= 0 && snapshot.memUsedPercent <= 100);
  assert.ok(snapshot.memTotalBytes > 0);
  assert.ok(snapshot.memFreeBytes >= 0);
});

test('startSystemMetricsLogger: loggt sofort einen Messpunkt und danach im Intervall weitere', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-metrics-logger-'));
  try {
    const db = openDatabase(dir);
    try {
      const logger = startSystemMetricsLogger(db, 50);
      try {
        assert.equal(listSystemMetrics(db).length, 1);
        await delay(160);
        const rows = listSystemMetrics(db);
        assert.ok(rows.length >= 3, `erwartete mindestens 3 Messpunkte, bekam ${String(rows.length)}`);
      } finally {
        logger.stop();
      }
      const countAtStop = listSystemMetrics(db).length;
      await delay(120);
      assert.equal(listSystemMetrics(db).length, countAtStop);
    } finally {
      db.close();
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('startSystemMetricsLogger: schreibt gueltige CommandRow-unabhaengige Systemwerte in t_system_metrics', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cl-metrics-logger-shape-'));
  try {
    const db: DatabaseSync = openDatabase(dir);
    try {
      const logger = startSystemMetricsLogger(db, 60_000);
      logger.stop();
      const [row] = listSystemMetrics(db);
      assert.ok(row);
      assert.ok(row.cpuPercent >= 0);
      assert.ok(row.memUsedPercent >= 0 && row.memUsedPercent <= 100);
      assert.ok(row.memTotalBytes > 0);
      assert.equal(typeof row.createdAt, 'string');
    } finally {
      db.close();
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
