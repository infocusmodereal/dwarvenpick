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
      credentialProfilePolicies: [
        {
          credentialProfile: 'readonly',
          readOnly: true,
          canExport: false,
          maxRowsPerQuery: 1000,
          maxRuntimeSeconds: 60,
          concurrencyLimit: 2,
          sysadmin: false,
          justificationMode: 'NONE',
        },
      ],
    },
  ]);

  assert.match(output, /postgresql-core/);
  assert.match(output, /POSTGRESQL/);
  assert.match(output, /read-only \(writes blocked\)/);
  assert.match(output, /blocked/);
});

test('formatConnections emits one CSV row per effective profile policy', () => {
  const output = formatConnections(
    [
      {
        id: 'starrocks-dev',
        name: 'StarRocks Dev',
        engine: 'STARROCKS',
        credentialProfiles: ['read-only', 'read-write'],
        credentialProfilePolicies: [
          {
            credentialProfile: 'read-only',
            readOnly: true,
            canExport: false,
            maxRowsPerQuery: 1000,
            maxRuntimeSeconds: 60,
            concurrencyLimit: 2,
            sysadmin: false,
            justificationMode: 'NONE',
          },
          {
            credentialProfile: 'read-write',
            readOnly: false,
            canExport: false,
            maxRowsPerQuery: 500,
            maxRuntimeSeconds: 30,
            concurrencyLimit: 1,
            sysadmin: false,
            justificationMode: 'PROFILE_REQUIRED',
          },
        ],
      },
    ],
    'csv',
  );

  assert.match(
    output,
    /policyProfile,access,export,maxRows,maxRuntimeSeconds,concurrency,justification,elevatedHealth/,
  );
  assert.match(output, /read-only,read-only \(writes blocked\),blocked,1000,60,2,not required,no/);
  assert.match(output, /read-write,write-capable,blocked,500,30,1,required for every run,no/);
});

test('formatConnections preserves structured policy fields in JSON', () => {
  const connections = [
    {
      id: 'starrocks-dev',
      name: 'StarRocks Dev',
      engine: 'STARROCKS',
      credentialProfiles: ['read-write'],
      credentialProfilePolicies: [
        {
          credentialProfile: 'read-write',
          readOnly: false,
          canExport: true,
          maxRowsPerQuery: 500,
          maxRuntimeSeconds: 30,
          concurrencyLimit: 1,
          sysadmin: false,
          justificationMode: 'PROFILE_REQUIRED',
        },
      ],
    },
  ];

  assert.deepEqual(JSON.parse(formatConnections(connections, 'json')), connections);
});
