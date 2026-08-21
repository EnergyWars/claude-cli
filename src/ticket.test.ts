import assert from 'node:assert/strict';
import { test } from 'node:test';

import type { TicketAgentConfig } from './config.js';
import {
  buildTicketAgentSystemPrompt,
  extractJsonObjects,
  parseTicketAgentOutput,
  runTicketAgent,
} from './ticket.js';
import { createEmptyBinDir, createMockClaude, pathWithMock } from './test-support/mock-claude.js';

test('buildTicketAgentSystemPrompt: haengt die Output-Format-Anweisung an die konfigurierte Aufgabe an', () => {
  const prompt = buildTicketAgentSystemPrompt('Erstelle ein Ticket aus dem Text.');
  assert.match(prompt, /^Erstelle ein Ticket aus dem Text\./);
  assert.match(prompt, /"title": "\.\.\.", "description": "\.\.\.", "task": "\.\.\."/);
});

test('extractJsonObjects: findet ein einzelnes Top-Level-Objekt', () => {
  assert.deepEqual(extractJsonObjects('{"a":1}'), ['{"a":1}']);
});

test('extractJsonObjects: ignoriert verschachtelte Objekte, liefert nur das aeusserste', () => {
  assert.deepEqual(extractJsonObjects('{"a":{"b":1}}'), ['{"a":{"b":1}}']);
});

test('extractJsonObjects: findet mehrere Top-Level-Objekte in Reihenfolge', () => {
  assert.deepEqual(extractJsonObjects('vorher {"a":1} dazwischen {"b":2} danach'), [
    '{"a":1}',
    '{"b":2}',
  ]);
});

test('extractJsonObjects: ignoriert Braces innerhalb von Strings', () => {
  const text = '{"title": "Fix {bug} in parser", "n": 1}';
  assert.deepEqual(extractJsonObjects(text), [text]);
});

test('extractJsonObjects: behandelt escapte Anfuehrungszeichen in Strings korrekt', () => {
  const text = String.raw`{"title": "Er sagte \"Hallo {Welt}\""}`;
  assert.deepEqual(extractJsonObjects(text), [text]);
});

test('extractJsonObjects: leerer Text liefert leeres Array', () => {
  assert.deepEqual(extractJsonObjects(''), []);
});

test('extractJsonObjects: Text ohne Braces liefert leeres Array', () => {
  assert.deepEqual(extractJsonObjects('kein json hier'), []);
});

test('extractJsonObjects: unausgeglichene Braces liefern keinen Treffer', () => {
  assert.deepEqual(extractJsonObjects('{"a": 1'), []);
});

function validTicketJson(overrides: Partial<Record<string, unknown>> = {}): string {
  return JSON.stringify({
    title: 'Kurzer Titel',
    description: 'Kurze Beschreibung.',
    task: 'Konkrete Aufgabe.',
    ...overrides,
  });
}

test('parseTicketAgentOutput: parsed ein sauberes JSON-Objekt', () => {
  const result = parseTicketAgentOutput(validTicketJson());
  assert.deepEqual(result, {
    title: 'Kurzer Titel',
    description: 'Kurze Beschreibung.',
    task: 'Konkrete Aufgabe.',
  });
});

test('parseTicketAgentOutput: ignoriert Markdown-Codeblock-Zaeune', () => {
  const output = `\`\`\`json\n${validTicketJson()}\n\`\`\``;
  assert.deepEqual(parseTicketAgentOutput(output), {
    title: 'Kurzer Titel',
    description: 'Kurze Beschreibung.',
    task: 'Konkrete Aufgabe.',
  });
});

test('parseTicketAgentOutput: ignoriert Prosa vor und nach dem JSON', () => {
  const output = `Hier ist das Ticket:\n${validTicketJson()}\nHoffe das hilft!`;
  assert.deepEqual(parseTicketAgentOutput(output), {
    title: 'Kurzer Titel',
    description: 'Kurze Beschreibung.',
    task: 'Konkrete Aufgabe.',
  });
});

test('parseTicketAgentOutput: trimmt Whitespace in den Feldern', () => {
  const output = validTicketJson({ title: '  Titel mit Leerzeichen  ' });
  assert.equal(parseTicketAgentOutput(output).title, 'Titel mit Leerzeichen');
});

test('parseTicketAgentOutput: waehlt das letzte gueltige Objekt, wenn mehrere vorkommen', () => {
  const first = JSON.stringify({ title: 'Erstes', description: 'x', task: 'y' });
  const second = JSON.stringify({ title: 'Zweites', description: 'x', task: 'y' });
  const result = parseTicketAgentOutput(`${first}\n${second}`);
  assert.equal(result.title, 'Zweites');
});

test('parseTicketAgentOutput: ueberspringt ein fruehes Objekt ohne Ticket-Form und nimmt ein spaeteres gueltiges', () => {
  const irrelevant = JSON.stringify({ foo: 'bar' });
  const valid = validTicketJson();
  const result = parseTicketAgentOutput(`${irrelevant}\n${valid}`);
  assert.equal(result.title, 'Kurzer Titel');
});

test('parseTicketAgentOutput: wirft bei leerem Output', () => {
  assert.throws(() => parseTicketAgentOutput(''), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft, wenn kein JSON-Objekt enthalten ist', () => {
  assert.throws(() => parseTicketAgentOutput('nur Text, kein JSON'), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei fehlendem Feld "task"', () => {
  const output = JSON.stringify({ title: 'x', description: 'y' });
  assert.throws(() => parseTicketAgentOutput(output), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei fehlendem Feld "title"', () => {
  const output = JSON.stringify({ description: 'y', task: 'z' });
  assert.throws(() => parseTicketAgentOutput(output), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei falschem Typ eines Feldes', () => {
  const output = JSON.stringify({ title: 42, description: 'y', task: 'z' });
  assert.throws(() => parseTicketAgentOutput(output), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei nur Whitespace in einem Feld', () => {
  const output = validTicketJson({ description: '   ' });
  assert.throws(() => parseTicketAgentOutput(output), /kein gueltiges Ticket-JSON/);
});

test('parseTicketAgentOutput: wirft bei kaputtem JSON', () => {
  assert.throws(
    () => parseTicketAgentOutput('{"title": "x", "description": }'),
    /kein gueltiges Ticket-JSON/,
  );
});

test('parseTicketAgentOutput: ignoriert Extra-Felder, akzeptiert das Objekt trotzdem', () => {
  const output = validTicketJson({ extra: 'wird ignoriert' });
  assert.deepEqual(parseTicketAgentOutput(output), {
    title: 'Kurzer Titel',
    description: 'Kurze Beschreibung.',
    task: 'Konkrete Aufgabe.',
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
      title: 'Kurzer Titel',
      description: 'Kurze Beschreibung.',
      task: 'Konkrete Aufgabe.',
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
