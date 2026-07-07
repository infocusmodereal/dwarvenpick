import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { QueryInterruptedError, resolveConfig, runQuery, streamResults, waitForQuery } from '../src/main.js';

test('streamResults writes CSV rows page by page', async () => {
  const calls = [];
  const writes = [];
  const client = pagedClient(calls, [
    { columns: [{ name: 'id' }], rows: [[1]], nextPageToken: 'next' },
    { rows: [[2]] },
  ]);

  await streamResults(
    client,
    'exec-1',
    { pageSize: 1, format: 'csv', headers: true },
    recordingStreams(writes),
  );

  assert.deepEqual(calls, [
    { executionId: 'exec-1', pageSize: 1, pageToken: null },
    { executionId: 'exec-1', pageSize: 1, pageToken: 'next' },
  ]);
  assert.deepEqual(writes, ['id\n', '1\n', '2\n']);
});

test('runQuery submits justification from query options', async () => {
  const submitRequests = [];
  const client = {
    async resolveAuthMode() {
      return 'local';
    },
    async login() {
      return { username: 'analyst', provider: 'local' };
    },
    async submitQuery(request) {
      submitRequests.push(request);
      return { executionId: 'exec-justification', datasourceId: request.datasourceId, status: 'QUEUED' };
    },
    async queryStatus() {
      return { executionId: 'exec-justification', status: 'SUCCEEDED' };
    },
    async queryResults() {
      return { columns: [{ name: 'value' }], rows: [[1]] };
    },
  };
  const parsed = {
    command: 'query',
    options: {
      connection: 'postgresql-core',
      sql: 'select 1',
      justification: 'TOPS-123 maintenance window',
    },
    positional: [],
  };
  const writes = [];

  await runQuery(client, parsed, queryConfig(), recordingStreams(writes));

  assert.equal(submitRequests.length, 1);
  assert.equal(submitRequests[0].justification, 'TOPS-123 maintenance window');
});

test('resolveConfig prefers --justification over DWARVENPICK_JUSTIFICATION', () => {
  const config = resolveConfig(
    { justification: 'flag reason' },
    { DWARVENPICK_JUSTIFICATION: 'env reason', DWARVENPICK_USERNAME: 'analyst', DWARVENPICK_PASSWORD: 'secret' },
  );

  assert.equal(config.justification, 'flag reason');
});

test('waitForQuery requests backend cancel on timeout', async () => {
  const calls = [];
  const writes = [];
  const client = {
    async queryStatus(executionId) {
      calls.push(['status', executionId]);
      return { executionId, status: 'RUNNING' };
    },
    async cancelQuery(executionId) {
      calls.push(['cancel', executionId]);
      return { executionId, status: 'CANCELED' };
    },
  };

  await assert.rejects(
    () =>
      waitForQuery(
        client,
        'exec-timeout',
        queryConfig({ quiet: false, timeoutMs: 2, pollIntervalMs: 1 }),
        recordingStreams(writes),
        new EventEmitter(),
      ),
    /Timed out waiting for query exec-timeout.*backend cancellation requested/,
  );

  assert.ok(calls.some(([type]) => type === 'status'));
  assert.deepEqual(calls.at(-1), ['cancel', 'exec-timeout']);
  assert.match(writes.join(''), /Requested backend cancellation/);
});

test('waitForQuery requests backend cancel on SIGINT and exits as interrupted', async () => {
  const signalTarget = new EventEmitter();
  const calls = [];
  const client = {
    async queryStatus(executionId) {
      calls.push(['status', executionId]);
      return { executionId, status: 'RUNNING' };
    },
    async cancelQuery(executionId) {
      calls.push(['cancel', executionId]);
      return { executionId, status: 'CANCELED' };
    },
  };

  const waitPromise = waitForQuery(
    client,
    'exec-sigint',
    queryConfig({ timeoutMs: 100, pollIntervalMs: 1 }),
    recordingStreams([]),
    signalTarget,
  );
  signalTarget.emit('SIGINT');

  await assert.rejects(waitPromise, (error) => {
    assert.ok(error instanceof QueryInterruptedError);
    assert.equal(error.exitCode, 130);
    assert.match(error.message, /backend cancellation requested/);
    return true;
  });
  assert.deepEqual(calls.at(-1), ['cancel', 'exec-sigint']);
});

test('streamResults omits CSV headers when requested', async () => {
  const writes = [];
  const client = pagedClient([], [{ columns: [{ name: 'id' }], rows: [[1]] }]);

  await streamResults(client, 'exec-headers', { pageSize: 1, format: 'csv', headers: false }, recordingStreams(writes));

  assert.deepEqual(writes, ['1\n']);
});

test('streamResults emits valid JSON without collecting all rows first', async () => {
  const calls = [];
  const writes = [];
  const client = pagedClient(calls, [
    { columns: [{ name: 'name' }], rows: [['durin']], nextPageToken: 'next' },
    { rows: [['nain']] },
  ]);

  await streamResults(client, 'exec-2', { pageSize: 1, format: 'json' }, recordingStreams(writes));

  assert.ok(writes.length > 2);
  assert.deepEqual(JSON.parse(writes.join('')), {
    columns: ['name'],
    rows: [['durin'], ['nain']],
  });
  assert.deepEqual(calls.map((call) => call.pageToken), [null, 'next']);
});

test('streamResults emits valid empty JSON results', async () => {
  const writes = [];
  const client = pagedClient([], [{ columns: [{ name: 'id' }], rows: [] }]);

  await streamResults(client, 'exec-empty', { pageSize: 1, format: 'json' }, recordingStreams(writes));

  assert.deepEqual(JSON.parse(writes.join('')), {
    columns: ['id'],
    rows: [],
  });
});

test('streamResults keeps table output bounded to each result page', async () => {
  const writes = [];
  const client = pagedClient([], [
    { columns: [{ name: 'name' }], rows: [['durin']], nextPageToken: 'next' },
    { rows: [['nain']] },
  ]);

  await streamResults(client, 'exec-3', { pageSize: 1, format: 'table' }, recordingStreams(writes));

  const output = writes.join('');
  assert.match(output, /\|\s+1\s+\|\s+durin\s+\|/);
  assert.match(output, /\|\s+2\s+\|\s+nain\s+\|/);
  assert.equal(writes.filter((chunk) => chunk.startsWith('+-')).length, 2);
});

test('streamResults waits for sink drain when output applies backpressure', async () => {
  const events = [];
  const client = pagedClient([], [{ columns: [{ name: 'id' }], rows: [[1]] }]);
  const stdout = new EventEmitter();
  stdout.write = (chunk) => {
    events.push(`write:${chunk}`);
    setImmediate(() => {
      events.push('drain');
      stdout.emit('drain');
    });
    return false;
  };

  await streamResults(client, 'exec-backpressure', { pageSize: 1, format: 'csv', headers: false }, { stdout });

  assert.deepEqual(events, ['write:1\n', 'drain']);
});

test('streamResults writes streamed output to a file', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'dwarvenpick-cli-'));
  const outputPath = join(directory, 'results.csv');
  const client = pagedClient([], [{ columns: [{ name: 'id' }], rows: [[1]] }]);

  try {
    await streamResults(client, 'exec-4', { pageSize: 1, format: 'csv', outputPath }, recordingStreams([]));

    assert.equal(await readFile(outputPath, 'utf8'), 'id\n1\n');
  } finally {
    await rm(directory, { force: true, recursive: true });
  }
});

test('streamResults rejects cleanly when an output file cannot be opened', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'dwarvenpick-cli-missing-'));
  const outputPath = join(directory, 'missing', 'results.csv');
  const client = pagedClient([], [{ columns: [{ name: 'id' }], rows: [[1]] }]);

  try {
    await assert.rejects(
      () => streamResults(client, 'exec-bad-output', { pageSize: 1, format: 'csv', outputPath }, recordingStreams([])),
      /ENOENT|no such file/i,
    );
  } finally {
    await rm(directory, { force: true, recursive: true });
  }
});

test('streamResults rejects unsupported formats before fetching results', async () => {
  const client = {
    async queryResults() {
      throw new Error('should not fetch');
    },
  };

  await assert.rejects(
    () => streamResults(client, 'exec-invalid', { pageSize: 1, format: 'yaml' }, recordingStreams([])),
    /Unsupported output format 'yaml'/,
  );
});

function pagedClient(calls, pages) {
  let index = 0;
  return {
    async queryResults(executionId, { pageSize, pageToken }) {
      calls.push({ executionId, pageSize, pageToken });
      const page = pages[index];
      index += 1;
      return page;
    },
  };
}

function queryConfig(overrides = {}) {
  return {
    url: 'http://localhost:3000',
    auth: 'local',
    username: 'analyst',
    password: 'secret',
    connection: undefined,
    credentialProfile: undefined,
    justification: undefined,
    format: 'json',
    output: undefined,
    pageSize: 500,
    pollIntervalMs: 1,
    timeoutMs: 1_000,
    quiet: true,
    ...overrides,
  };
}

function recordingStreams(writes) {
  return {
    stdout: {
      write(chunk) {
        writes.push(chunk);
        return true;
      },
    },
    stderr: {
      write(chunk) {
        writes.push(chunk);
        return true;
      },
    },
  };
}
