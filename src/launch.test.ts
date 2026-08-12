import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

import type { AgentDefinition } from './config.js';
import {
  buildClaudeArgs,
  buildSystemPrompt,
  buildTaskContent,
  runHeadlessCommand,
  runShellCommand,
} from './launch.js';
import { createFixtureRoot } from './test-support/fixture-config.js';
import {
  createEmptyBinDir,
  createMockClaude,
  extractMockArgs,
  pathWithMock,
} from './test-support/mock-claude.js';

test('buildClaudeArgs: ohne headlessPrompt (interaktiver Fall)', () => {
  const args = buildClaudeArgs('sonnet', 'SYSTEM-PROMPT');
  assert.deepEqual(args, [
    '--model',
    'sonnet',
    '--append-system-prompt',
    'SYSTEM-PROMPT',
    '--permission-mode',
    'acceptEdits',
  ]);
});

test('buildClaudeArgs: mit headlessPrompt haengt --print + Prompt an', () => {
  const args = buildClaudeArgs('opus', 'SYSTEM-PROMPT', 'mache irgendwas cooles');
  assert.deepEqual(args, [
    '--model',
    'opus',
    '--append-system-prompt',
    'SYSTEM-PROMPT',
    '--permission-mode',
    'acceptEdits',
    '--print',
    'mache irgendwas cooles',
  ]);
});

test('buildSystemPrompt: verkettet alle Contexts eines Agents mit doppeltem Newline', () => {
  const fixture = createFixtureRoot({
    contexts: { a: 'Content A', b: 'Content B' },
  });
  const previous = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  try {
    const agent: AgentDefinition = {
      description: 'x',
      model: 'sonnet',
      contexts: ['a', 'b'],
    };
    assert.equal(buildSystemPrompt(agent), 'Content A\n\nContent B');
  } finally {
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    fixture.cleanup();
  }
});

test('buildTaskContent: verkettet alle Tasks-Dateien eines Tasks mit doppeltem Newline', () => {
  const fixture = createFixtureRoot({
    taskFiles: { a: 'Task A', b: 'Task B' },
  });
  const previous = process.env.CL_ROOT_DIR;
  process.env.CL_ROOT_DIR = fixture.rootDir;
  try {
    assert.equal(buildTaskContent({ tasks: ['a', 'b'] }), 'Task A\n\nTask B');
  } finally {
    if (previous === undefined) {
      delete process.env.CL_ROOT_DIR;
    } else {
      process.env.CL_ROOT_DIR = previous;
    }
    fixture.cleanup();
  }
});

const testAgent: AgentDefinition = { description: 'x', model: 'sonnet', contexts: [] };

test('runHeadlessCommand: sammelt Output und liefert Exit-Code 0', async () => {
  const mock = createMockClaude({ outputChunks: ['hello '], exitCode: 0 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const chunks: string[] = [];
    const result = await runHeadlessCommand(
      testAgent,
      'sonnet',
      'irgendein prompt',
      process.cwd(),
      (output) => {
        chunks.push(output);
      },
    );
    assert.equal(result.exitCode, 0);
    assert.match(result.output, /hello /);
    assert.ok(chunks.length > 0);
    assert.equal(chunks.at(-1), result.output);

    const invokedArgs = extractMockArgs(result.output);
    assert.deepEqual(invokedArgs, [
      '--model',
      'sonnet',
      '--append-system-prompt',
      '',
      '--permission-mode',
      'acceptEdits',
      '--print',
      'irgendein prompt',
    ]);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('runHeadlessCommand: onChunk erhaelt kumulierten Output bei mehreren Chunks', async () => {
  const mock = createMockClaude({ outputChunks: ['A', 'B', 'C'], chunkDelayMs: 10, exitCode: 0 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const chunks: string[] = [];
    const result = await runHeadlessCommand(testAgent, 'sonnet', 'p', process.cwd(), (output) => {
      chunks.push(output);
    });
    assert.ok(chunks.length >= 1);
    for (let i = 1; i < chunks.length; i += 1) {
      const current = chunks[i];
      const prior = chunks[i - 1];
      assert.ok(current !== undefined && prior !== undefined);
      assert.ok(current.length >= prior.length, 'Output waechst monoton');
    }
    assert.equal(chunks.at(-1), result.output);
    assert.match(result.output, /ABC/);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('runHeadlessCommand: liefert nicht-null Exit-Code bei Fehlschlag', async () => {
  const mock = createMockClaude({ outputChunks: ['fail'], exitCode: 3 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const result = await runHeadlessCommand(
      testAgent,
      'sonnet',
      'p',
      process.cwd(),
      () => undefined,
    );
    assert.equal(result.exitCode, 3);
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('runHeadlessCommand: rejected wenn "claude" nicht im PATH gefunden wird', async () => {
  const empty = createEmptyBinDir();
  const previousPath = process.env.PATH;
  process.env.PATH = empty.binDir;
  try {
    await assert.rejects(() =>
      runHeadlessCommand(testAgent, 'sonnet', 'p', process.cwd(), () => undefined),
    );
  } finally {
    process.env.PATH = previousPath;
    empty.cleanup();
  }
});

test('runShellCommand: fuehrt den Command im angegebenen Verzeichnis aus, liefert Output + Exit-Code 0', async () => {
  const cwd = mkdtempSync(join(tmpdir(), 'cl-shell-cmd-'));
  try {
    const chunks: string[] = [];
    const result = await runShellCommand('pwd', cwd, (output) => {
      chunks.push(output);
    });
    assert.equal(result.exitCode, 0);
    assert.match(result.output.trim(), new RegExp(cwd.replace(/[/\\^$*+?.()|[\]{}]/g, '\\$&')));
    assert.ok(chunks.length > 0);
    assert.equal(chunks.at(-1), result.output);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('runShellCommand: liefert nicht-null Exit-Code bei fehlschlagendem Command', async () => {
  const result = await runShellCommand('exit 7', process.cwd(), () => undefined);
  assert.equal(result.exitCode, 7);
});
