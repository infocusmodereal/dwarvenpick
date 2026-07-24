import { firstPageToken } from './constants';
import type { WorkspaceTab } from './types';

export type ResultPageRequest = {
    pageToken: string;
    previousPageTokens: string[];
};

export type CsvExportAvailability = {
    capKnown: boolean;
    exceeded: boolean;
    summary: string;
    note: string;
};

export const csvExportAvailability = (tab: WorkspaceTab): CsvExportAvailability => {
    const completed = tab.executionStatus === 'SUCCEEDED';
    const cap = tab.maxExportRows;
    if (!completed || !Number.isInteger(cap) || (cap ?? 0) < 1) {
        return {
            capKnown: false,
            exceeded: false,
            summary: completed
                ? `${tab.rowCount.toLocaleString()} rows · cap not reported`
                : 'CSV cap available after completion',
            note: 'CSV export limit is not reported by this server. Backend enforcement still applies.'
        };
    }

    const resolvedCap = cap as number;
    const exceeded = tab.rowCount > resolvedCap;
    return {
        capKnown: true,
        exceeded,
        summary: `${tab.rowCount.toLocaleString()} / ${resolvedCap.toLocaleString()} rows`,
        note: exceeded
            ? `This result exceeds the ${resolvedCap.toLocaleString()}-row CSV limit. Narrow the query to export.`
            : `CSV exports all ${tab.rowCount.toLocaleString()} rows in their original order.`
    };
};

export const nextResultPageRequest = (tab: WorkspaceTab): ResultPageRequest | null => {
    if (!tab.executionId || !tab.nextPageToken) {
        return null;
    }

    return {
        pageToken: tab.nextPageToken,
        previousPageTokens: [...tab.previousPageTokens, tab.currentPageToken]
    };
};

export const previousResultPageRequest = (tab: WorkspaceTab): ResultPageRequest | null => {
    if (!tab.executionId || tab.previousPageTokens.length === 0) {
        return null;
    }

    const previousPageTokens = [...tab.previousPageTokens];
    const pageToken = previousPageTokens.pop() ?? firstPageToken;
    return {
        pageToken,
        previousPageTokens
    };
};

export const buildResultPageUrl = (
    executionId: string,
    pageSize: number,
    pageToken = firstPageToken
): string => {
    const queryParams = new URLSearchParams();
    queryParams.set('pageSize', pageSize.toString());
    if (pageToken) {
        queryParams.set('pageToken', pageToken);
    }
    return `/api/queries/${executionId}/results?${queryParams.toString()}`;
};

export const buildCsvExportUrl = (executionId: string, includeHeaders: boolean): string => {
    const queryParams = new URLSearchParams();
    queryParams.set('headers', includeHeaders ? 'true' : 'false');
    return `/api/queries/${executionId}/export.csv?${queryParams.toString()}`;
};

export const csvExportFileName = (
    contentDisposition: string | null,
    fallbackName: string
): string => {
    const disposition = contentDisposition ?? '';
    const fileNameMatch = disposition.match(/filename="?([^";]+)"?/i);
    return fileNameMatch?.[1] ?? fallbackName;
};
