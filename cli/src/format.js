const maxCellWidth = 48;

export function formatResults({ columns, rows, format = 'table', headers = true }) {
  const normalizedColumns = normalizeColumns(columns);
  if (format === 'json') {
    return `${JSON.stringify({ columns: normalizedColumns, rows }, null, 2)}\n`;
  }
  if (format === 'csv') {
    return formatCsv(normalizedColumns, rows, { headers });
  }
  if (format === 'table') {
    return formatTable(normalizedColumns, rows);
  }
  throw new Error(`Unsupported output format '${format}'. Use table, json, or csv.`);
}

export function formatConnections(connections, format = 'table') {
  const rows = connections.map((connection) => [
    connection.id,
    connection.name,
    connection.engine,
    [...(connection.credentialProfiles || [])].join(', '),
  ]);

  if (format === 'json') {
    return `${JSON.stringify(connections, null, 2)}\n`;
  }
  if (format === 'csv') {
    return formatCsv(['id', 'name', 'engine', 'credentialProfiles'], rows, { headers: true });
  }
  return formatTable(['id', 'name', 'engine', 'credentialProfiles'], rows);
}

export function formatCsv(columns, rows, { headers = true } = {}) {
  const lines = [];
  if (headers) {
    lines.push(columns.map(escapeCsvCell).join(','));
  }
  for (const row of rows) {
    lines.push(row.map(escapeCsvCell).join(','));
  }
  return `${lines.join('\n')}${lines.length > 0 ? '\n' : ''}`;
}

export function formatTable(columns, rows) {
  const tableColumns = ['ROW', ...columns];
  const tableRows = rows.map((row, index) => [String(index + 1), ...row.map((cell) => stringifyCell(cell))]);
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
