import assert from 'node:assert/strict';
import test from 'node:test';
import { parseArgs, parseDurationMs, parsePositiveInteger, UsageError } from '../src/args.js';

test('parseArgs handles query options and positional SQL', () => {
  const parsed = parseArgs(['run', '--connection', 'postgresql-core', '--format=json', 'SELECT', '1']);

  assert.equal(parsed.command, 'query');
  assert.equal(parsed.options.connection, 'postgresql-core');
  assert.equal(parsed.options.format, 'json');
  assert.deepEqual(parsed.positional, ['SELECT', '1']);
});

test('parseArgs rejects missing option values', () => {
  assert.throws(() => parseArgs(['query', '--connection']), UsageError);
});

test('parseDurationMs supports human durations', () => {
  assert.equal(parseDurationMs('250ms', '--timeout'), 250);
  assert.equal(parseDurationMs('3s', '--timeout'), 3000);
  assert.equal(parseDurationMs('2m', '--timeout'), 120000);
});

test('parsePositiveInteger rejects invalid values', () => {
  assert.throws(() => parsePositiveInteger('0', '--page-size'), UsageError);
  assert.throws(() => parsePositiveInteger('abc', '--page-size'), UsageError);
});
