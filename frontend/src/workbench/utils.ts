import type { ResultSortDirection } from './types';

export const toStatusToneClass = (rawValue: string): string => {
    const value = rawValue.trim().toUpperCase();
    if (value === 'SUCCEEDED' || value === 'SUCCESS') {
        return 'status-pill tone-success';
    }
    if (value === 'FAILED' || value === 'DENIED') {
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

export const compareResultValues = (
    left: string | null,
    right: string | null,
    direction: ResultSortDirection
): number => {
    if (left === right) {
        return 0;
    }

    if (left === null) {
        return 1;
    }
    if (right === null) {
        return -1;
    }

    const normalizedLeft = left.trim();
    const normalizedRight = right.trim();
    const leftNumeric = Number(normalizedLeft);
    const rightNumeric = Number(normalizedRight);
    const bothNumeric = Number.isFinite(leftNumeric) && Number.isFinite(rightNumeric);

    if (bothNumeric) {
        return direction === 'asc' ? leftNumeric - rightNumeric : rightNumeric - leftNumeric;
    }

    const lexicalOrder = normalizedLeft.localeCompare(normalizedRight, undefined, {
        numeric: true,
        sensitivity: 'base'
    });
    return direction === 'asc' ? lexicalOrder : -lexicalOrder;
};
