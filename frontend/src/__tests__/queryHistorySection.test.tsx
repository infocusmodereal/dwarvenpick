import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { afterEach, vi } from 'vitest';
import QueryHistorySection from '../workbench/sections/QueryHistorySection';
import type { QueryHistoryEntryResponse } from '../workbench/types';

const longQuery = `SELECT campaignID, siteID, really_long_metric_name
FROM Viper2.systemFilter
WHERE siteID IN (101, 102, 103)
ORDER BY campaignID, siteID;`;

const historyEntry: QueryHistoryEntryResponse = {
    executionId: 'exec-1',
    actor: 'analyst',
    datasourceId: 'starrocks-prod-adhoc',
    status: 'SUCCEEDED',
    message: 'ok',
    queryHash: 'hash-1',
    queryText: longQuery,
    queryTextRedacted: false,
    rowCount: 50,
    columnCount: 3,
    rowLimitReached: false,
    maxRowsPerQuery: 5000,
    maxRuntimeSeconds: 300,
    credentialProfile: 'read-only',
    justification: 'TOPS-123 maintenance window',
    submittedAt: '2026-06-18T10:00:00Z',
    completedAt: '2026-06-18T10:00:01Z',
    durationMs: 1000
};
const originalExecCommand = document.execCommand;

const renderQueryHistorySection = (
    overrides: Partial<ComponentProps<typeof QueryHistorySection>> = {}
) =>
    render(
        <QueryHistorySection
            hidden={false}
            visibleDatasources={[]}
            datasourceFilter=""
            onDatasourceFilterChange={vi.fn()}
            statusFilter=""
            onStatusFilterChange={vi.fn()}
            fromFilter=""
            onFromFilterChange={vi.fn()}
            toFilter=""
            onToFilterChange={vi.fn()}
            loadingQueryHistory={false}
            errorMessage=""
            onRefresh={vi.fn()}
            sortOrder="newest"
            onToggleSortOrder={vi.fn()}
            onClearFilters={vi.fn()}
            entries={[historyEntry]}
            pageIndex={0}
            pageSize={100}
            hasNextPage={false}
            onPageIndexChange={vi.fn()}
            onPageSizeChange={vi.fn()}
            onOpenEntry={vi.fn()}
            {...overrides}
        />
    );

afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    Object.defineProperty(document, 'execCommand', {
        configurable: true,
        value: originalExecCommand
    });
});

describe('QueryHistorySection', () => {
    it('renders governed query justification in history rows', () => {
        renderQueryHistorySection();

        expect(screen.getByText('Justification')).toBeInTheDocument();
        expect(screen.getByText('TOPS-123 maintenance window')).toBeInTheDocument();
    });

    it('renders query hover card outside the scrollable table wrapper', () => {
        const { container } = renderQueryHistorySection();
        const preview = container.querySelector<HTMLElement>('.history-query-preview');
        const wrapper = container.querySelector<HTMLElement>('.history-table-wrap');

        expect(preview).toBeInTheDocument();
        expect(wrapper).toBeInTheDocument();

        fireEvent.mouseEnter(preview!);

        const tooltip = screen.getByRole('tooltip');
        expect(tooltip.querySelector('.history-query-full')?.textContent).toBe(longQuery);
        expect(tooltip.closest('.history-table-wrap')).toBeNull();

        fireEvent.scroll(wrapper!);
        expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    });

    it('dispatches open and rerun while keeping redacted SQL unavailable', () => {
        const onOpenEntry = vi.fn();
        const { rerender } = renderQueryHistorySection({ onOpenEntry });

        fireEvent.click(screen.getByRole('button', { name: 'Open' }));
        fireEvent.click(screen.getByRole('button', { name: 'Rerun' }));
        expect(onOpenEntry).toHaveBeenNthCalledWith(1, historyEntry, false);
        expect(onOpenEntry).toHaveBeenNthCalledWith(2, historyEntry, true);

        rerender(
            <QueryHistorySection
                hidden={false}
                visibleDatasources={[]}
                datasourceFilter=""
                onDatasourceFilterChange={vi.fn()}
                statusFilter=""
                onStatusFilterChange={vi.fn()}
                fromFilter=""
                onFromFilterChange={vi.fn()}
                toFilter=""
                onToFilterChange={vi.fn()}
                loadingQueryHistory={false}
                errorMessage=""
                onRefresh={vi.fn()}
                sortOrder="newest"
                onToggleSortOrder={vi.fn()}
                onClearFilters={vi.fn()}
                entries={[{ ...historyEntry, queryTextRedacted: true }]}
                pageIndex={0}
                pageSize={100}
                hasNextPage={false}
                onPageIndexChange={vi.fn()}
                onPageSizeChange={vi.fn()}
                onOpenEntry={onOpenEntry}
            />
        );

        expect(screen.getByText('[REDACTED]')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Open' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Rerun' })).toBeDisabled();
        fireEvent.mouseEnter(screen.getByText('[REDACTED]'));
        expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    });

    it('renders distinct loading, empty, and error states', () => {
        const { rerender } = renderQueryHistorySection({
            entries: [],
            loadingQueryHistory: true
        });
        expect(screen.getByText('Loading query history...')).toBeInTheDocument();

        rerender(
            <QueryHistorySection
                hidden={false}
                visibleDatasources={[]}
                datasourceFilter=""
                onDatasourceFilterChange={vi.fn()}
                statusFilter=""
                onStatusFilterChange={vi.fn()}
                fromFilter=""
                onFromFilterChange={vi.fn()}
                toFilter=""
                onToFilterChange={vi.fn()}
                loadingQueryHistory={false}
                errorMessage="History is temporarily unavailable."
                onRefresh={vi.fn()}
                sortOrder="newest"
                onToggleSortOrder={vi.fn()}
                onClearFilters={vi.fn()}
                entries={[]}
                pageIndex={0}
                pageSize={100}
                hasNextPage={false}
                onPageIndexChange={vi.fn()}
                onPageSizeChange={vi.fn()}
                onOpenEntry={vi.fn()}
            />
        );

        expect(screen.getByRole('alert')).toHaveTextContent('History is temporarily unavailable.');
        expect(
            screen.getByText('No history entries found for current filters.')
        ).toBeInTheDocument();
    });

    it('reports successful and failed query copy attempts', async () => {
        const writeText = vi.fn().mockResolvedValue(undefined);
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
        const { container, unmount } = renderQueryHistorySection();

        fireEvent.mouseEnter(container.querySelector('.history-query-preview')!);
        fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
        await waitFor(() =>
            expect(screen.getByRole('status')).toHaveTextContent('Query text copied to clipboard.')
        );
        expect(writeText).toHaveBeenCalledWith(longQuery);
        unmount();

        const rejectedWrite = vi.fn().mockRejectedValue(new Error('denied'));
        vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: rejectedWrite } });
        const failed = renderQueryHistorySection();
        fireEvent.mouseEnter(failed.container.querySelector('.history-query-preview')!);
        fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
        await waitFor(() =>
            expect(screen.getByRole('alert')).toHaveTextContent('Unable to copy query text.')
        );
    });

    it('reports a rejected legacy clipboard fallback', async () => {
        vi.stubGlobal('navigator', { ...navigator, clipboard: undefined });
        Object.defineProperty(document, 'execCommand', {
            configurable: true,
            value: vi.fn().mockReturnValue(false)
        });
        const { container } = renderQueryHistorySection();

        fireEvent.mouseEnter(container.querySelector('.history-query-preview')!);
        fireEvent.click(screen.getByRole('button', { name: 'Copy' }));

        await waitFor(() =>
            expect(screen.getByRole('alert')).toHaveTextContent('Unable to copy query text.')
        );
        expect(document.querySelector('textarea[readonly]')).not.toBeInTheDocument();
    });

    it('passes pagination and filter actions through the focused section contract', () => {
        const onStatusFilterChange = vi.fn();
        const onPageIndexChange = vi.fn();
        const onPageSizeChange = vi.fn();
        renderQueryHistorySection({
            hasNextPage: true,
            onStatusFilterChange,
            onPageIndexChange,
            onPageSizeChange
        });

        fireEvent.change(screen.getByLabelText('Status'), {
            target: { value: 'FAILED' }
        });
        fireEvent.click(screen.getByRole('button', { name: 'Next Page' }));
        fireEvent.change(screen.getByLabelText('Rows per page'), { target: { value: '10' } });

        expect(onStatusFilterChange).toHaveBeenCalledWith('FAILED');
        expect(onPageIndexChange).toHaveBeenCalledWith(1);
        expect(onPageSizeChange).toHaveBeenCalledWith(10);
    });
});
