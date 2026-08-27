import assert from 'node:assert/strict';
import { test } from 'node:test';

import { createEmptyBinDir, createMockClaude, pathWithMock } from './test-support/mock-claude.js';
import { extractUsageResultText, getUsageLimits, parseUsageResult } from './usage.js';

const SAMPLE_RESULT_TEXT = [
  'Current session: 94% used · resets Aug 27, 5:40pm (Europe/Berlin)',
  'Current week (all models): 67% used · resets Aug 29, 9pm (Europe/Berlin)',
  'Current week (Fable): 25% used · resets Aug 29, 9pm (Europe/Berlin)',
].join('\n');

test('parseUsageResult: parsed alle Limit-Zeilen', () => {
  assert.deepEqual(parseUsageResult(SAMPLE_RESULT_TEXT), [
    { label: 'Current session', percentUsed: 94, resetsAt: 'Aug 27, 5:40pm (Europe/Berlin)' },
    {
      label: 'Current week (all models)',
      percentUsed: 67,
      resetsAt: 'Aug 29, 9pm (Europe/Berlin)',
    },
    { label: 'Current week (Fable)', percentUsed: 25, resetsAt: 'Aug 29, 9pm (Europe/Berlin)' },
  ]);
});

test('parseUsageResult: ignoriert Zeilen ohne "% used" Muster', () => {
  const text = `${SAMPLE_RESULT_TEXT}\n\nLast 24h · 3916 requests · 78 sessions\n  74% of your usage came from subagent-heavy sessions`;
  assert.equal(parseUsageResult(text).length, 3);
});

test('parseUsageResult: leerer Text liefert leeres Array', () => {
  assert.deepEqual(parseUsageResult(''), []);
});

function usageJsonOutput(result: string): string {
  return JSON.stringify({ type: 'result', result });
}

test('extractUsageResultText: liest das "result"-Feld aus dem JSON-Output', () => {
  assert.equal(extractUsageResultText(usageJsonOutput(SAMPLE_RESULT_TEXT)), SAMPLE_RESULT_TEXT);
});

test('extractUsageResultText: ignoriert Prosa/Warnungen vor dem JSON-Objekt', () => {
  const output = `(node:1234) ExperimentalWarning: irgendwas\n${usageJsonOutput(SAMPLE_RESULT_TEXT)}`;
  assert.equal(extractUsageResultText(output), SAMPLE_RESULT_TEXT);
});

test('extractUsageResultText: wirft ohne gueltiges Result-JSON', () => {
  assert.throws(() => extractUsageResultText('kein json hier'), /kein gueltiges Result-JSON/);
});

test('extractUsageResultText: wirft, wenn "type" nicht "result" ist', () => {
  assert.throws(
    () => extractUsageResultText(JSON.stringify({ type: 'system', result: 'x' })),
    /kein gueltiges Result-JSON/,
  );
});

test('getUsageLimits: spawnt "claude --print /usage --output-format json" und parsed die Antwort', async () => {
  const mock = createMockClaude({
    outputChunks: [usageJsonOutput(SAMPLE_RESULT_TEXT)],
    exitCode: 0,
  });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const limits = await getUsageLimits();
    assert.deepEqual(limits, [
      { label: 'Current session', percentUsed: 94, resetsAt: 'Aug 27, 5:40pm (Europe/Berlin)' },
      {
        label: 'Current week (all models)',
        percentUsed: 67,
        resetsAt: 'Aug 29, 9pm (Europe/Berlin)',
      },
      { label: 'Current week (Fable)', percentUsed: 25, resetsAt: 'Aug 29, 9pm (Europe/Berlin)' },
    ]);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('getUsageLimits: wirft bei nicht-null Exit-Code', async () => {
  const mock = createMockClaude({ outputChunks: ['irgendwas'], exitCode: 1 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await assert.rejects(() => getUsageLimits(), /ist fehlgeschlagen \(Exit-Code 1\)/);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('getUsageLimits: rejected wenn "claude" nicht im PATH gefunden wird', async () => {
  const empty = createEmptyBinDir();
  const previousPath = process.env.PATH;
  process.env.PATH = empty.binDir;
  try {
    await assert.rejects(() => getUsageLimits());
  } finally {
    process.env.PATH = previousPath;
    empty.cleanup();
  }
});
