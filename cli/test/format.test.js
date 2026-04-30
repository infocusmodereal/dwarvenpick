import assert from 'node:assert/strict';
import test from 'node:test';
import { formatConnections, formatCsv, formatResults } from '../src/format.js';

test('formatCsv escapes cells', () => {
  assert.equal(formatCsv(['name', 'note'], [['khazad', 'hello, "friend"']]), 'name,note\nkhazad,"hello, ""friend"""\n');
});

test('formatResults emits JSON payload', () => {
  const output = formatResults({
    columns: [{ name: 'value' }],
    rows: [['1']],
    format: 'json',
  });

  assert.deepEqual(JSON.parse(output), { columns: ['value'], rows: [['1']] });
});

test('formatConnections emits table with key fields', () => {
  const output = formatConnections([
    {
      id: 'postgresql-core',
      name: 'PostgreSQL Core',
      engine: 'POSTGRESQL',
      credentialProfiles: ['readonly'],
    },
  ]);

  assert.match(output, /postgresql-core/);
  assert.match(output, /POSTGRESQL/);
});
