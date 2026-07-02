import assert from 'node:assert/strict';
import test from 'node:test';
import { formatConnections, formatCsv, formatCsvRow, formatTable } from '../src/format.js';

test('formatCsv escapes cells', () => {
  assert.equal(formatCsv(['name', 'note'], [['khazad', 'hello, "friend"']]), 'name,note\nkhazad,"hello, ""friend"""\n');
});

test('formatCsvRow escapes a single streaming row', () => {
  assert.equal(formatCsvRow(['khazad', 'hello, "friend"']), 'khazad,"hello, ""friend"""');
});

test('formatTable accepts a starting row number', () => {
  const output = formatTable(['name'], [['nain']], { startRow: 25 });

  assert.match(output, /\|\s+25\s+\|\s+nain\s+\|/);
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
