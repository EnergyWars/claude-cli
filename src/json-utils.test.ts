import assert from 'node:assert/strict';
import { test } from 'node:test';

import { extractJsonObjects } from './json-utils.js';

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
  const text = '{"summary": "Fix {bug} in parser", "n": 1}';
  assert.deepEqual(extractJsonObjects(text), [text]);
});

test('extractJsonObjects: behandelt escapte Anfuehrungszeichen in Strings korrekt', () => {
  const text = String.raw`{"summary": "Er sagte \"Hallo {Welt}\""}`;
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
