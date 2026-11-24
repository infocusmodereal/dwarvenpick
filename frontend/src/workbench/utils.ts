import type { ResultSortDirection } from './types';

export const toStatusToneClass = (rawValue: string): string => {
    const value = rawValue.trim().toUpperCase();
    if (value === 'SUCCEEDED' || value === 'SUCCESS') {
        return 'status-pill tone-success';
    }
    if (value === 'UP' || value === 'HEALTHY' || value === 'OK') {
        return 'status-pill tone-success';
    }
    if (value === 'FAILED' || value === 'DENIED') {
        return 'status-pill tone-failed';
    }
    if (value === 'DOWN' || value === 'UNHEALTHY') {
        return 'status-pill tone-failed';
    }
    if (value === 'RUNNING') {
        return 'status-pill tone-running';
    }
    if (value === 'QUEUED') {
        return 'status-pill tone-queued';
    }
    if (value === 'CANCELED') {
        return 'status-pill tone-canceled';
    }
    if (value === 'LIMITED') {
        return 'status-pill tone-limited';
    }
    if (value === 'DEGRADED' || value === 'WARNING') {
        return 'status-pill tone-limited';
    }

    return 'status-pill tone-neutral';
};

export const adminIdentifierPattern = /^[a-z][a-z0-9.-]*$/;

export const normalizeAdminIdentifier = (value: string): string => {
    const trimmed = value.trim().toLowerCase();
    return trimmed
        .replace(/\s+/g, '-')
        .replace(/_/g, '-')
        .replace(/[^a-z0-9.-]/g, '');
};

export const isValidEmailAddress = (value: string): boolean =>
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim());

export const isTerminalExecutionStatus = (status: string): boolean =>
    status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELED';

export const toIsoTimestamp = (value: string): string | null => {
    const trimmed = value.trim();
    if (!trimmed) {
        return null;
    }

    const parsed = new Date(trimmed);
    return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
};

export const formatExecutionTimestamp = (value: string): string => {
    const trimmed = value.trim();
    if (!trimmed) {
        return '-';
    }

    const parsed = new Date(trimmed);
    if (Number.isNaN(parsed.getTime())) {
        return trimmed;
    }
    return parsed.toLocaleString();
};

export const formatExecutionDuration = (start: string, end: string): string => {
    const startedAt = new Date(start);
    const completedAt = new Date(end);
    if (Number.isNaN(startedAt.getTime()) || Number.isNaN(completedAt.getTime())) {
        return '-';
    }

    const durationMs = completedAt.getTime() - startedAt.getTime();
    if (!Number.isFinite(durationMs) || durationMs < 0) {
        return '-';
    }

    if (durationMs < 1000) {
        return `${durationMs} ms`;
    }

    if (durationMs < 60_000) {
        return `${(durationMs / 1000).toFixed(2)} s`;
    }

    const minutes = Math.floor(durationMs / 60_000);
    const seconds = ((durationMs % 60_000) / 1000).toFixed(1);
    return `${minutes}m ${seconds}s`;
};

const exactNumericType =
    /^(?:(?:tiny|small|medium|big)?int(?:eger|[248])?|(?:big|small)?serial|numeric|decimal|number|varint)(?:\b|$)/i;
const decimalValue = /^([+-]?)(\d*?)(?:\.(\d+))?$/;

function parseDecimal(value: string) {
    const match = decimalValue.exec(value.trim());
    if (!match || (!match[2] && !match[3])) return null;
    const integer = match[2].replace(/^0+/, '');
    const fraction = (match[3] ?? '').replace(/0+$/, '');
    return { negative: match[1] === '-' && Boolean(integer || fraction), integer, fraction };
}

function compareDecimals(
    left: ReturnType<typeof parseDecimal>,
    right: ReturnType<typeof parseDecimal>
) {
    if (!left || !right) return left ? -1 : right ? 1 : 0;
    if (left.negative !== right.negative) return left.negative ? -1 : 1;
    const magnitude =
        left.integer.length - right.integer.length ||
        (left.integer < right.integer ? -1 : left.integer > right.integer ? 1 : 0) ||
        (left.fraction < right.fraction ? -1 : left.fraction > right.fraction ? 1 : 0);
    return left.negative ? -magnitude : magnitude;
}

export const compareResultValues = (
    left: string | null,
    right: string | null,
    direction: ResultSortDirection,
    jdbcType: string
): number => {
    if (left === right) return 0;
    if (left === null) return 1;
    if (right === null) return -1;

    let order: number;
    if (exactNumericType.test(jdbcType)) {
        const leftDecimal = parseDecimal(left);
        const rightDecimal = parseDecimal(right);
        order = compareDecimals(leftDecimal, rightDecimal);
        if (!leftDecimal && !rightDecimal) order = left.localeCompare(right);
    } else if (/^(float[48]?|real|double)(?:\b|$)/i.test(jdbcType)) {
        const a = left.trim() ? Number(left) : NaN;
        const b = right.trim() ? Number(right) : NaN;
        order = Number.isNaN(a)
            ? Number.isNaN(b)
                ? left.localeCompare(right)
                : 1
            : Number.isNaN(b)
              ? -1
              : a === b
                ? 0
                : a < b
                  ? -1
                  : 1;
    } else {
        order = left.localeCompare(right);
    }
    return direction === 'asc' ? order : -order;
};
