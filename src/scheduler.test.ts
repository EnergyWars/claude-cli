import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { test } from 'node:test';

import type { SchedulerConfig } from './config.js';
import { startSchedulers } from './scheduler.js';

function makeScheduler(overrides: Partial<SchedulerConfig> = {}): SchedulerConfig {
  return {
    name: 'test-scheduler',
    description: 'x',
    cron: '* * * * * *',
    paths: ['default'],
    model: 'sonnet',
    ...overrides,
  };
}

test('startSchedulers: ruft onTrigger bei jedem Cron-Treffer auf', async () => {
  const triggered: string[] = [];
  const registry = startSchedulers([makeScheduler()], (scheduler) => {
    triggered.push(scheduler.name);
  });
  try {
    await delay(2200);
    assert.ok(
      triggered.length >= 2,
      `erwartete mindestens 2 Trigger, bekam ${String(triggered.length)}`,
    );
    assert.ok(triggered.every((name) => name === 'test-scheduler'));
  } finally {
    registry.stop();
  }
});

test('startSchedulers: stop() beendet weitere Trigger', async () => {
  const triggered: string[] = [];
  const registry = startSchedulers([makeScheduler()], (scheduler) => {
    triggered.push(scheduler.name);
  });
  await delay(1100);
  registry.stop();
  const countAtStop = triggered.length;
  await delay(1200);
  assert.equal(triggered.length, countAtStop);
});

test('startSchedulers: ungueltiger Cron-Ausdruck wird uebersprungen, andere Scheduler laufen weiter', async () => {
  const triggered: string[] = [];
  const previousError = console.error;
  console.error = () => undefined;
  const registry = startSchedulers(
    [
      makeScheduler({ name: 'broken', cron: 'not-a-cron-expression' }),
      makeScheduler({ name: 'valid' }),
    ],
    (scheduler) => {
      triggered.push(scheduler.name);
    },
  );
  try {
    await delay(1300);
    assert.ok(triggered.includes('valid'));
    assert.ok(!triggered.includes('broken'));
  } finally {
    registry.stop();
    console.error = previousError;
  }
});

test('startSchedulers: keine Scheduler - stop() ist ein No-op', () => {
  const registry = startSchedulers([], () => {
    throw new Error('sollte nie aufgerufen werden');
  });
  assert.doesNotThrow(() => {
    registry.stop();
  });
});
