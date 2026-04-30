export class UsageError extends Error {
  constructor(message) {
    super(message);
    this.name = 'UsageError';
    this.exitCode = 2;
  }
}

const booleanOptions = new Set([
  'continue-on-error',
  'help',
  'no-headers',
  'quiet',
  'script',
  'version',
]);

const aliases = new Map([
  ['c', 'connection'],
  ['f', 'file'],
  ['h', 'help'],
  ['o', 'output'],
  ['p', 'password'],
  ['q', 'sql'],
  ['u', 'username'],
  ['v', 'version'],
]);

export function parseArgs(argv) {
  const positional = [];
  const options = {};
  let command = null;

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];

    if (token === '--') {
      positional.push(...argv.slice(index + 1));
      break;
    }

    if (token.startsWith('--')) {
      const optionToken = token.slice(2);
      const equalsIndex = optionToken.indexOf('=');
      const rawName = equalsIndex === -1 ? optionToken : optionToken.slice(0, equalsIndex);
      const name = normalizeOptionName(rawName);

      if (booleanOptions.has(name)) {
        options[name] = equalsIndex === -1 ? true : parseBoolean(optionToken.slice(equalsIndex + 1), name);
        continue;
      }

      if (equalsIndex !== -1) {
        options[name] = optionToken.slice(equalsIndex + 1);
        continue;
      }

      const nextToken = argv[index + 1];
      if (!nextToken || nextToken.startsWith('-')) {
        throw new UsageError(`Missing value for --${rawName}.`);
      }
      options[name] = nextToken;
      index += 1;
      continue;
    }

    if (token.startsWith('-') && token.length > 1) {
      const shorthand = token.slice(1);
      const name = aliases.get(shorthand);
      if (!name) {
        throw new UsageError(`Unknown option -${shorthand}.`);
      }
      if (booleanOptions.has(name)) {
        options[name] = true;
        continue;
      }
      const nextToken = argv[index + 1];
      if (!nextToken || nextToken.startsWith('-')) {
        throw new UsageError(`Missing value for -${shorthand}.`);
      }
      options[name] = nextToken;
      index += 1;
      continue;
    }

    if (!command) {
      command = token;
    } else {
      positional.push(token);
    }
  }

  return {
    command: normalizeCommand(command),
    options,
    positional,
  };
}

export function parsePositiveInteger(value, optionName, defaultValue) {
  if (value === undefined || value === null || value === '') {
    return defaultValue;
  }

  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new UsageError(`${optionName} must be a positive integer.`);
  }
  return parsed;
}

export function parseDurationMs(value, optionName, defaultValue) {
  if (value === undefined || value === null || value === '') {
    return defaultValue;
  }

  const match = String(value).trim().match(/^(\d+)(ms|s|m)?$/i);
  if (!match) {
    throw new UsageError(`${optionName} must be a duration like 500ms, 5s, or 2m.`);
  }

  const amount = Number.parseInt(match[1], 10);
  const unit = (match[2] || 'ms').toLowerCase();
  const multiplier = unit === 'm' ? 60_000 : unit === 's' ? 1_000 : 1;
  return amount * multiplier;
}

function normalizeCommand(command) {
  if (!command) {
    return 'help';
  }
  if (command === 'run') {
    return 'query';
  }
  return command;
}

function normalizeOptionName(name) {
  return aliases.get(name) || name;
}

function parseBoolean(value, optionName) {
  const normalized = String(value).toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(normalized)) {
    return true;
  }
  if (['0', 'false', 'no', 'off'].includes(normalized)) {
    return false;
  }
  throw new UsageError(`--${optionName} expects a boolean value.`);
}
