const maxCellWidth = 48;

export function formatConnections(connections, format = 'table') {
  if (format === 'json') {
    return `${JSON.stringify(connections, null, 2)}\n`;
  }
  const rows = connections.flatMap(connectionPolicyRows);
  const columns = [
    'id',
    'name',
    'engine',
    'credentialProfiles',
    'policyProfile',
    'access',
    'export',
    'maxRows',
    'maxRuntimeSeconds',
    'concurrency',
    'justification',
    'elevatedHealth',
  ];
  if (format === 'csv') {
    return formatCsv(columns, rows, { headers: true });
  }
  return formatTable(columns, rows);
}

function connectionPolicyRows(connection) {
  const base = [
    connection.id,
    connection.name,
    connection.engine,
    [...(connection.credentialProfiles || [])].join(', '),
  ];
  const policies = connection.credentialProfilePolicies || [];
  if (policies.length === 0) {
    return [[...base, '', '', '', '', '', '', '', '']];
  }

  return policies.map((policy) => [
    ...base,
    policy.credentialProfile,
    policy.readOnly ? 'read-only (writes blocked)' : 'write-capable',
    policy.canExport ? 'allowed' : 'blocked',
    formatPolicyLimit(policy.maxRowsPerQuery),
    formatPolicyLimit(policy.maxRuntimeSeconds),
    formatPolicyLimit(policy.concurrencyLimit),
    policy.justificationMode === 'PROFILE_REQUIRED' ? 'required for every run' : 'not required',
    policy.sysadmin ? 'yes' : 'no',
  ]);
}

function formatPolicyLimit(value) {
  return value === 2147483647 ? 'unlimited' : value;
}

export function formatCsv(columns, rows, { headers = true } = {}) {
  const lines = [];
  if (headers) {
    lines.push(formatCsvRow(columns));
  }
  for (const row of rows) {
    lines.push(formatCsvRow(row));
  }
  return `${lines.join('\n')}${lines.length > 0 ? '\n' : ''}`;
}

export function formatCsvRow(values) {
  return values.map(escapeCsvCell).join(',');
}

export function formatTable(columns, rows, { startRow = 1 } = {}) {
  const tableColumns = ['ROW', ...columns];
  const tableRows = rows.map((row, index) => [String(startRow + index), ...row.map((cell) => stringifyCell(cell))]);
  const widths = tableColumns.map((column, columnIndex) => {
    const cells = tableRows.map((row) => row[columnIndex] || '');
    return Math.min(maxCellWidth, Math.max(column.length, ...cells.map((cell) => cell.length)));
  });

  const separator = `+-${widths.map((width) => '-'.repeat(width)).join('-+-')}-+`;
  const header = `| ${tableColumns.map((column, index) => pad(truncate(column, widths[index]), widths[index])).join(' | ')} |`;
  const body = tableRows.map(
    (row) => `| ${row.map((cell, index) => pad(truncate(cell, widths[index]), widths[index])).join(' | ')} |`,
  );

  return `${[separator, header, separator, ...body, separator].join('\n')}\n`;
}

export function normalizeColumns(columns) {
  return (columns || []).map((column) => (typeof column === 'string' ? column : column.name));
}

function escapeCsvCell(value) {
  const stringValue = stringifyCell(value);
  if (/[",\n\r]/.test(stringValue)) {
    return `"${stringValue.replaceAll('"', '""')}"`;
  }
  return stringValue;
}

function stringifyCell(value) {
  return value === null || value === undefined ? '' : String(value);
}

function truncate(value, width) {
  if (value.length <= width) {
    return value;
  }
  if (width <= 3) {
    return value.slice(0, width);
  }
  return `${value.slice(0, Math.max(0, width - 3))}...`;
}

function pad(value, width) {
  return `${value}${' '.repeat(Math.max(0, width - value.length))}`;
}
