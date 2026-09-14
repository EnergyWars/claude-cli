import os from 'node:os';
import type { DatabaseSync } from 'node:sqlite';

import { insertSystemMetric } from './db.js';

/** Linux-Load-Average bezogen auf die Kernanzahl, als Prozentwert - kann > 100 sein (Ueberlast), wird bewusst nicht gekappt. */
export function computeCpuLoadPercent(loadAverage1Min: number, cpuCount: number): number {
  if (cpuCount <= 0) {
    return 0;
  }
  return Math.max(0, (loadAverage1Min / cpuCount) * 100);
}

export function computeMemoryUsedPercent(totalBytes: number, freeBytes: number): number {
  if (totalBytes <= 0) {
    return 0;
  }
  return ((totalBytes - freeBytes) / totalBytes) * 100;
}

export interface SystemMetricsSnapshot {
  cpuPercent: number;
  memUsedPercent: number;
  memTotalBytes: number;
  memFreeBytes: number;
}

export function sampleSystemMetrics(): SystemMetricsSnapshot {
  const [loadAverage1Min] = os.loadavg();
  const cpuCount = os.cpus().length;
  const totalBytes = os.totalmem();
  const freeBytes = os.freemem();
  return {
    cpuPercent: computeCpuLoadPercent(loadAverage1Min ?? 0, cpuCount),
    memUsedPercent: computeMemoryUsedPercent(totalBytes, freeBytes),
    memTotalBytes: totalBytes,
    memFreeBytes: freeBytes,
  };
}

export const SYSTEM_METRICS_INTERVAL_MS = 5 * 60 * 1000;

export interface SystemMetricsLogger {
  stop: () => void;
}

/** Loggt sofort einen Messpunkt und danach alle `intervalMs` einen weiteren, in `t_system_metrics` (Grundlage fuer `GET /system-metrics`, siehe `commander/context.md`s CPU-/RAM-Diagramme). */
export function startSystemMetricsLogger(
  db: DatabaseSync,
  intervalMs: number = SYSTEM_METRICS_INTERVAL_MS,
): SystemMetricsLogger {
  const record = (): void => {
    insertSystemMetric(db, sampleSystemMetrics());
  };
  record();
  const timer = setInterval(record, intervalMs);
  return {
    stop: () => {
      clearInterval(timer);
    },
  };
}
