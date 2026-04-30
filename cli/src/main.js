import { readFile, writeFile } from 'node:fs/promises';
import { parseArgs, parseDurationMs, parsePositiveInteger, UsageError } from './args.js';
import { DwarvenpickClient } from './client.js';
import { formatConnections, formatResults } from './format.js';

const packageMetadata = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));

export async function main(argv = process.argv.slice(2), env = process.env, streams = process) {
  const parsed = parseArgs(argv);

  if (parsed.options.version || parsed.command === 'version') {
    streams.stdout.write(`${packageMetadata.version}\n`);
    return;
  }

  if (parsed.options.help || parsed.command === 'help') {
    streams.stdout.write(helpText());
    return;
  }

  const config = resolveConfig(parsed.options, env);
  const client = new DwarvenpickClient({ baseUrl: config.url });

  if (parsed.command === 'login') {
    const user = await authenticate(client, config);
    streams.stdout.write(`Authenticated as ${user.username} (${user.provider}).\n`);
    return;
  }

  if (parsed.command === 'connections') {
    await authenticate(client, config);
    const connections = await client.listConnections();
    await emit(formatConnections(connections, config.format), config.output, streams);
    return;
  }

  if (parsed.command === 'query') {
    await runQuery(client, parsed, config, streams);
    return;
  }

  throw new UsageError(`Unknown command '${parsed.command}'. Run dwarvenpick --help for usage.`);
}

export async function runQuery(client, parsed, config, streams = process) {
  await authenticate(client, config);

  const sql = await resolveSql(parsed.options, parsed.positional);
  const datasourceId = parsed.options.connection || parsed.options.datasource || config.connection;
  if (!datasourceId) {
    throw new UsageError('A connection is required. Use --connection or DWARVENPICK_CONNECTION.');
  }

  const submitResponse = await client.submitQuery({
    datasourceId,
    sql,
    credentialProfile: parsed.options['credential-profile'] || config.credentialProfile || null,
    scriptMode: Boolean(parsed.options.script),
    stopOnError: !parsed.options['continue-on-error'],
    transactionMode: (parsed.options.transaction || 'AUTOCOMMIT').toUpperCase(),
  });

  if (!config.quiet) {
    streams.stderr.write(`Submitted query ${submitResponse.executionId} on ${submitResponse.datasourceId}.\n`);
  }

  const status = await waitForQuery(client, submitResponse.executionId, config, streams);
  if (status.status !== 'SUCCEEDED') {
    const details = status.errorSummary || status.message || status.status;
    throw new Error(`Query ${status.executionId} finished with ${status.status}: ${details}`);
  }

  const results = await collectResults(client, submitResponse.executionId, config.pageSize);
  const output = formatResults({
    columns: results.columns,
    rows: results.rows,
    format: config.format,
    headers: !parsed.options['no-headers'],
  });
  await emit(output, config.output, streams);
}

export function resolveConfig(options, env) {
  return {
    url: options.url || env.DWARVENPICK_URL || 'http://localhost:3000',
    auth: (options.auth || env.DWARVENPICK_AUTH || 'auto').toLowerCase(),
    username: options.username || env.DWARVENPICK_USERNAME,
    password: options.password || env.DWARVENPICK_PASSWORD,
    connection: options.connection || env.DWARVENPICK_CONNECTION,
    credentialProfile: options['credential-profile'] || env.DWARVENPICK_CREDENTIAL_PROFILE,
    format: (options.format || env.DWARVENPICK_FORMAT || 'table').toLowerCase(),
    output: options.output,
    pageSize: Math.min(parsePositiveInteger(options['page-size'], '--page-size', 500), 1000),
    pollIntervalMs: parseDurationMs(options['poll-interval'], '--poll-interval', 1_000),
    timeoutMs: parseDurationMs(options.timeout, '--timeout', 120_000),
    quiet: Boolean(options.quiet),
  };
}

export async function authenticate(client, config) {
  if (!config.username) {
    throw new UsageError('A username is required. Use --username or DWARVENPICK_USERNAME.');
  }
  if (!config.password) {
    throw new UsageError('A password is required. Use --password or DWARVENPICK_PASSWORD.');
  }
  const authMode = await client.resolveAuthMode(config.auth);
  return client.login({
    authMode,
    username: config.username,
    password: config.password,
  });
}

export async function resolveSql(options, positional) {
  if (options.sql) {
    return options.sql;
  }
  if (options.file) {
    return readFile(options.file, 'utf8');
  }
  if (positional.length > 0) {
    return positional.join(' ');
  }
  throw new UsageError('SQL is required. Use --sql, --file, or pass SQL as an argument.');
}

export async function waitForQuery(client, executionId, config, streams = process) {
  const deadline = Date.now() + config.timeoutMs;

  while (Date.now() <= deadline) {
    const status = await client.queryStatus(executionId);
    if (!['QUEUED', 'RUNNING'].includes(status.status)) {
      return status;
    }
    if (!config.quiet) {
      streams.stderr.write(`Query ${executionId} is ${status.status.toLowerCase()}...\n`);
    }
    await sleep(config.pollIntervalMs);
  }

  throw new Error(`Timed out waiting for query ${executionId} after ${config.timeoutMs}ms.`);
}

export async function collectResults(client, executionId, pageSize) {
  const rows = [];
  let columns = [];
  let pageToken = null;
  let rowLimitReached = false;

  do {
    const page = await client.queryResults(executionId, { pageSize, pageToken });
    columns = page.columns || columns;
    rows.push(...(page.rows || []));
    pageToken = page.nextPageToken;
    rowLimitReached = Boolean(page.rowLimitReached);
  } while (pageToken);

  return { columns, rows, rowLimitReached };
}

function helpText() {
  return `dwarvenpick CLI ${packageMetadata.version}

Usage:
  dwarvenpick login [options]
  dwarvenpick connections [options]
  dwarvenpick query --connection <id> --sql <sql> [options]
  dwarvenpick run --connection <id> --file query.sql [options]

Auth options:
  --url <url>                 Base URL. Default: DWARVENPICK_URL or http://localhost:3000
  --auth <auto|local|ldap>    Password auth method. Default: DWARVENPICK_AUTH or auto
  -u, --username <username>   Username. Default: DWARVENPICK_USERNAME
  -p, --password <password>   Password. Default: DWARVENPICK_PASSWORD

Query options:
  -c, --connection <id>       Connection ID. Default: DWARVENPICK_CONNECTION
  --credential-profile <id>   Credential profile override
  -q, --sql <sql>             SQL text
  -f, --file <path>           Read SQL from a file
  --script                    Run as a script
  --continue-on-error         Continue script execution after statement errors
  --transaction <mode>        AUTOCOMMIT or TRANSACTION
  --page-size <rows>          Results page size, max 1000. Default: 500
  --timeout <duration>        Wait timeout, for example 120s or 2m. Default: 120s
  --poll-interval <duration>  Poll interval. Default: 1s

Output options:
  --format <table|json|csv>   Output format. Default: table
  --no-headers                Omit CSV headers
  -o, --output <path>         Write output to a file
  --quiet                     Suppress progress messages
  -h, --help                  Show help
  -v, --version               Show version
`;
}

async function emit(content, outputPath, streams) {
  if (outputPath) {
    await writeFile(outputPath, content, 'utf8');
    return;
  }
  streams.stdout.write(content);
}

function sleep(milliseconds) {
  return new Promise((resolve) => {
    setTimeout(resolve, milliseconds);
  });
}
