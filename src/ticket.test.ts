import assert from 'node:assert/strict';
import { test } from 'node:test';

import type { TicketAgentConfig } from './config.js';
import { buildTicketAgentSystemPrompt, parseTicketAgentOutput, runTicketAgent } from './ticket.js';
import { createEmptyBinDir, createMockClaude, pathWithMock } from './test-support/mock-claude.js';

test('buildTicketAgentSystemPrompt: haengt die Output-Format-Anweisung an die konfigurierte Aufgabe an', () => {
  const prompt = buildTicketAgentSystemPrompt('Erstelle ein Ticket aus dem Text.');
  assert.match(prompt, /^Erstelle ein Ticket aus dem Text\./);
  assert.match(prompt, /"summary": "\.\.\.", "claudeInstruction": "\.\.\.", "category": "\.\.\."/);
});

function validTicketJson(overrides: Partial<Record<string, unknown>> = {}): string {
  return JSON.stringify({
    summary: 'Kurze Zusammenfassung.',
    claudeInstruction: 'Konkrete Anweisung fuer Claude.',
    category: 'Backend',
    ...overrides,
  });
}

test('parseTicketAgentOutput: parsed ein sauberes JSON-Objekt', () => {
  const result = parseTicketAgentOutput(validTicketJson());
  assert.deepEqual(result, {
    summary: 'Kurze Zusammenfassung.',
    claudeInstruction: 'Konkrete Anweisung fuer Claude.',
    category: 'Backend',
  });
});

test('parseTicketAgentOutput: ignoriert Markdown-Codeblock-Zaeune', () => {
  const output = `\`\`\`json\n${validTicketJson()}\n\`\`\``;
  assert.deepEqual(parseTicketAgentOutput(output), {
    summary: 'Kurze Zusammenfassung.',
    claudeInstruction: 'Konkrete Anweisung fuer Claude.',
    category: 'Backend',
  });
});

test('parseTicketAgentOutput: ignoriert Prosa vor und nach dem JSON', () => {
  const output = `Hier ist das Ticket:\n${validTicketJson()}\nHoffe das hilft!`;
  assert.deepEqual(parseTicketAgentOutput(output), {
    summary: 'Kurze Zusammenfassung.',
    claudeInstruction: 'Konkrete Anweisung fuer Claude.',
    category: 'Backend',
  });
});

test('parseTicketAgentOutput: trimmt Whitespace in den Feldern', () => {
  const output = validTicketJson({ summary: '  Zusammenfassung mit Leerzeichen  ' });
  assert.equal(parseTicketAgentOutput(output).summary, 'Zusammenfassung mit Leerzeichen');
});

test('parseTicketAgentOutput: waehlt das letzte gueltige Objekt, wenn mehrere vorkommen', () => {
  const first = JSON.stringify({ summary: 'Erstes', claudeInstruction: 'x', category: 'y' });
  const second = JSON.stringify({ summary: 'Zweites', claudeInstruction: 'x', category: 'y' });
  const result = parseTicketAgentOutput(`${first}\n${second}`);
  assert.equal(result.summary, 'Zweites');
});

test('parseTicketAgentOutput: ueberspringt ein fruehes Objekt ohne Ticket-Form und nimmt ein spaeteres gueltiges', () => {
  const irrelevant = JSON.stringify({ foo: 'bar' });
  const valid = validTicketJson();
  const result = parseTicketAgentOutput(`${irrelevant}\n${valid}`);
  assert.equal(result.summary, 'Kurze Zusammenfassung.');
});

test('parseTicketAgentOutput: wirft bei leerem Output', () => {
  assert.throws(() => parseTicketAgentOutput(''), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft, wenn kein JSON-Objekt enthalten ist', () => {
  assert.throws(() => parseTicketAgentOutput('nur Text, kein JSON'), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei fehlendem Feld "category"', () => {
  const output = JSON.stringify({ summary: 'x', claudeInstruction: 'y' });
  assert.throws(() => parseTicketAgentOutput(output), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei fehlendem Feld "summary"', () => {
  const output = JSON.stringify({ claudeInstruction: 'y', category: 'z' });
  assert.throws(() => parseTicketAgentOutput(output), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei falschem Typ eines Feldes', () => {
  const output = JSON.stringify({ summary: 42, claudeInstruction: 'y', category: 'z' });
  assert.throws(() => parseTicketAgentOutput(output), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei nur Whitespace in einem Feld', () => {
  const output = validTicketJson({ claudeInstruction: '   ' });
  assert.throws(() => parseTicketAgentOutput(output), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei kaputtem JSON', () => {
  assert.throws(
    () => parseTicketAgentOutput('{"summary": "x", "claudeInstruction": }'),
    /kein gueltiges Ticket-JSON/,
  );
});

test('parseTicketAgentOutput: ignoriert Extra-Felder, akzeptiert das Objekt trotzdem', () => {
  const output = validTicketJson({ extra: 'wird ignoriert' });
  assert.deepEqual(parseTicketAgentOutput(output), {
    summary: 'Kurze Zusammenfassung.',
    claudeInstruction: 'Konkrete Anweisung fuer Claude.',
    category: 'Backend',
  });
});

const testTicketAgent: TicketAgentConfig = { model: 'haiku', task: 'Test-Aufgabe' };

test('runTicketAgent: baut Args aus model/task und parsed die Agent-Antwort', async () => {
  const mock = createMockClaude({ outputChunks: [validTicketJson()], exitCode: 0 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    const result = await runTicketAgent(process.cwd(), testTicketAgent, 'ein Text');
    assert.deepEqual(result, {
      summary: 'Kurze Zusammenfassung.',
      claudeInstruction: 'Konkrete Anweisung fuer Claude.',
      category: 'Backend',
    });
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('runTicketAgent: wirft bei nicht-null Exit-Code', async () => {
  const mock = createMockClaude({ outputChunks: ['irgendwas'], exitCode: 1 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await assert.rejects(
      () => runTicketAgent(process.cwd(), testTicketAgent, 'ein Text'),
      /Ticket-Agent \(Model "haiku"\) ist fehlgeschlagen/,
    );
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('runTicketAgent: wirft bei unparsebarer Agent-Antwort trotz Exit-Code 0', async () => {
  const mock = createMockClaude({ outputChunks: ['nur Text, kein JSON'], exitCode: 0 });
  const previousPath = process.env.PATH;
  process.env.PATH = pathWithMock(mock.binDir);
  try {
    await assert.rejects(
      () => runTicketAgent(process.cwd(), testTicketAgent, 'ein Text'),
      /kein gueltiges Ticket-JSON/,
    );
  } finally {
    process.env.PATH = previousPath;
    mock.cleanup();
  }
});

test('runTicketAgent: rejected wenn "claude" nicht im PATH gefunden wird', async () => {
  const empty = createEmptyBinDir();
  const previousPath = process.env.PATH;
  process.env.PATH = empty.binDir;
  try {
    await assert.rejects(() => runTicketAgent(process.cwd(), testTicketAgent, 'ein Text'));
  } finally {
    process.env.PATH = previousPath;
    empty.cleanup();
  }
});
