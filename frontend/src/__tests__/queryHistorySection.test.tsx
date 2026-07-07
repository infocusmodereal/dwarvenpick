import { fireEvent, render, screen } from '@testing-library/react';
import { vi } from 'vitest';
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

const renderQueryHistorySection = () =>
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
        />
    );

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
});
