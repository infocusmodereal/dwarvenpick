import { createRef } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import QueryResultsSection from '../workbench/sections/QueryResultsSection';
import type { QueryResultsView } from '../workbench/useQueryResultsWorkflow';
import { buildWorkspaceTab } from '../workbench/useWorkspaceTabs';
import { queryCapabilities } from '../workbench/queryCapabilities';
import { canExportLoadedResults } from '../workbench/queryResults';
import type { CatalogDatasourceResponse } from '../workbench/types';

const rows = [['alpha'], [null]];
const view = (overrides: Partial<QueryResultsView> = {}): QueryResultsView => ({
    search: '',
    onSearchChange: vi.fn(),
    density: 'compact',
    onDensityChange: vi.fn(),
    onViewportHeightChange: vi.fn(),
    onClearSort: vi.fn(),
    loadedRows: rows,
    exportIncludeHeaders: true,
    exportMenuRef: createRef<HTMLDivElement>(),
    exportingCsv: false,
    onExportCsv: vi.fn().mockResolvedValue(undefined),
    onExportIncludeHeadersChange: vi.fn(),
    onLoadNextResults: vi.fn(),
    onLoadPreviousResults: vi.fn(),
    onResultGridScroll: vi.fn(),
    onResultsPageSizeChange: vi.fn(),
    onToggleExportMenu: vi.fn(),
    onToggleResultSort: vi.fn(),
    resultSortState: null,
    resultsPageSize: 100,
    showExportMenu: false,
    visibleResultRows: { start: 0, end: 2, topSpacerPx: 0, bottomSpacerPx: 0, rows },
    ...overrides
});
const resultTab = {
    ...buildWorkspaceTab('test', 'Query 1', 'select value'),
    executionId: 'exec-1',
    resultColumns: [{ name: 'value', jdbcType: 'VARCHAR' }],
    resultRows: rows,
    executionStatus: 'SUCCEEDED',
    rowCount: 2,
    columnCount: 1,
    maxRowsPerQuery: 5000,
    maxExportRows: 5000,
    nextPageToken: '',
    previousPageTokens: []
};
const copy = () => vi.fn().mockResolvedValue(undefined);

describe('QueryResultsSection', () => {
    it('keeps query limit separate from pagination and omits redundant sort status', () => {
        const workflow = view();
        render(<QueryResultsSection tab={resultTab} view={workflow} onCopyCell={copy()} />);
        expect(screen.getByText('Limit 5,000')).toBeInTheDocument();
        expect(screen.getByText('Showing 1–2 of 2 rows')).toBeInTheDocument();
        expect(screen.getByText('1 of 1')).toBeInTheDocument();
        expect(screen.queryByText(/sort: none/)).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Previous Page' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Next Page' })).toBeDisabled();
        fireEvent.change(screen.getByLabelText('Rows per page'), { target: { value: '250' } });
        expect(workflow.onResultsPageSizeChange).toHaveBeenCalledWith(250);
        fireEvent.click(screen.getByRole('button', { name: 'Sort current page by value' }));
        expect(workflow.onToggleResultSort).toHaveBeenCalledWith(0);
    });
    it('preserves paging, copying selected cells and CSV policy', () => {
        const workflow = view();
        const onCopyCell = copy();
        render(
            <QueryResultsSection
                tab={{
                    ...resultTab,
                    rowCount: 202,
                    previousPageTokens: ['', 'page-1'],
                    nextPageToken: 'page-3'
                }}
                view={workflow}
                onCopyCell={onCopyCell}
            />
        );
        fireEvent.click(screen.getByRole('button', { name: 'Previous Page' }));
        fireEvent.click(screen.getByRole('button', { name: 'Next Page' }));
        expect(workflow.onLoadPreviousResults).toHaveBeenCalledOnce();
        expect(workflow.onLoadNextResults).toHaveBeenCalledOnce();
        fireEvent.click(screen.getByRole('button', { name: 'Select value, row 201' }));
        fireEvent.click(screen.getByRole('button', { name: 'More result actions' }));
        fireEvent.click(screen.getByRole('button', { name: 'Copy selected cells' }));
        expect(onCopyCell).toHaveBeenCalledWith('alpha');
        expect(
            screen.queryByRole('button', { name: 'Export CSV (all result rows)' })
        ).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Export' }));
        fireEvent.click(screen.getByRole('checkbox', { name: 'Include CSV headers' }));
        expect(workflow.onExportIncludeHeadersChange).toHaveBeenCalledWith(false);
        fireEvent.click(screen.getByRole('button', { name: 'Export CSV (all result rows)' }));
        expect(workflow.onExportCsv).toHaveBeenCalledOnce();
        expect(
            screen.getByText('CSV exports all 202 rows in their original order.')
        ).toBeInTheDocument();
    });
    it('provides an independent Export popover and restores keyboard focus on Escape', () => {
        render(<QueryResultsSection tab={resultTab} view={view()} onCopyCell={copy()} />);
        const trigger = screen.getByRole('button', { name: 'Export' });
        fireEvent.click(trigger);
        expect(screen.getByRole('dialog', { name: 'Export results' })).toBeInTheDocument();
        fireEvent.keyDown(screen.getByRole('button', { name: 'Export CSV (all result rows)' }), {
            key: 'Escape'
        });
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(trigger).toHaveFocus();
        fireEvent.click(trigger);
        fireEvent.click(screen.getByRole('button', { name: 'More result actions' }));
        expect(screen.queryByRole('dialog', { name: 'Export results' })).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Copy selected cells' })).toBeDisabled();
    });
    it('keeps alternating row shading stable when the virtual window starts on an odd row', () => {
        render(
            <QueryResultsSection
                tab={resultTab}
                view={view({
                    visibleResultRows: {
                        start: 1,
                        end: 2,
                        topSpacerPx: 28,
                        bottomSpacerPx: 0,
                        rows: [['alpha']]
                    }
                })}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByText('alpha').closest('tr')).toHaveClass('result-row-alt');
    });

    it('hides/restores columns without changing SQL or loaded results', () => {
        render(<QueryResultsSection tab={resultTab} view={view()} onCopyCell={copy()} />);
        fireEvent.click(screen.getByRole('button', { name: 'Show columns' }));
        fireEvent.click(screen.getByRole('checkbox', { name: 'value' }));
        expect(
            screen.queryByRole('button', { name: 'Sort current page by value' })
        ).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Restore all columns' }));
        expect(
            screen.getByRole('button', { name: 'Sort current page by value' })
        ).toBeInTheDocument();
        fireEvent.keyDown(screen.getByRole('dialog', { name: 'Result columns' }), {
            key: 'Escape'
        });
        expect(screen.getByRole('button', { name: 'Show columns' })).toHaveFocus();
    });
    it('announces only active local sorting', () => {
        render(
            <QueryResultsSection
                tab={resultTab}
                view={view({ resultSortState: { columnIndex: 0, direction: 'desc' } })}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByText('Sorted by value ↓ (loaded page)')).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: 'Sort current page by value' }).closest('th')
        ).toHaveAttribute('aria-sort', 'descending');
    });
    it('opening plan and stats never runs a query; Explain is explicit', () => {
        const onExplain = vi.fn();
        render(
            <QueryResultsSection
                tab={resultTab}
                view={view()}
                onCopyCell={copy()}
                capabilities={queryCapabilities('POSTGRESQL')}
                onExplain={onExplain}
            />
        );
        fireEvent.click(screen.getByRole('tab', { name: 'Query Plan' }));
        expect(onExplain).not.toHaveBeenCalled();
        expect(screen.getByText('No execution plan available')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Explain current SQL' }));
        expect(onExplain).toHaveBeenCalledOnce();
        fireEvent.keyDown(screen.getByRole('tab', { name: 'Query Plan' }), { key: 'ArrowRight' });
        expect(screen.getByRole('tab', { name: 'Stats' })).toHaveFocus();
        expect(screen.getByText('Returned rows')).toBeInTheDocument();
        expect(screen.queryByText('CPU time')).not.toBeInTheDocument();
    });
    it('renders engine plans and gracefully handles unsupported connectors', () => {
        const { rerender } = render(
            <QueryResultsSection
                tab={{
                    ...resultTab,
                    lastRunKind: 'explain',
                    resultRows: [['Seq Scan on orders (cost=0.00..12.00)']]
                }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByText('Seq Scan on orders (cost=0.00..12.00)')).toBeInTheDocument();
        rerender(
            <QueryResultsSection
                tab={{
                    ...resultTab,
                    lastRunKind: 'explain',
                    resultColumns: [
                        { name: 'rows', jdbcType: 'INTEGER' },
                        { name: 'Extra', jdbcType: 'VARCHAR' }
                    ],
                    resultRows: [['10', null]]
                }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByText(/rows \| Extra/)).toHaveTextContent('10 | NULL');

        rerender(
            <QueryResultsSection
                tab={resultTab}
                view={view()}
                onCopyCell={copy()}
                capabilities={queryCapabilities('AEROSPIKE')}
            />
        );
        expect(
            screen.getByText(/This connector does not provide an execution plan/)
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Explain current SQL' })
        ).not.toBeInTheDocument();
    });
    it('keeps query rows and stats when a separate plan succeeds or fails', () => {
        const plan = {
            ...resultTab,
            executionId: 'plan',
            lastRunKind: 'explain' as const,
            resultRows: [['Seq Scan on orders']],
            rowCount: 16
        };
        const { rerender } = render(
            <QueryResultsSection
                tab={{ ...resultTab, planExecution: plan }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByRole('tab', { name: 'Query Plan' })).toHaveAttribute(
            'aria-selected',
            'true'
        );
        expect(screen.getByText('Seq Scan on orders')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('tab', { name: 'Results' }));
        expect(screen.getByText('alpha')).toBeInTheDocument();
        expect(screen.queryByText('Seq Scan on orders')).not.toBeInTheDocument();
        expect(screen.getByText('Showing 1–2 of 2 rows')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('tab', { name: 'Stats' }));
        expect(screen.getByText('Returned rows').nextSibling).toHaveTextContent('2');
        rerender(
            <QueryResultsSection
                tab={{
                    ...resultTab,
                    planExecution: {
                        ...plan,
                        executionId: 'failed-plan',
                        resultRows: [],
                        errorMessage: 'Explain failed'
                    }
                }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByRole('alert')).toHaveTextContent('Explain failed');
        fireEvent.click(screen.getByRole('tab', { name: 'Results' }));
        expect(screen.getByText('alpha')).toBeInTheDocument();
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('shows zero rows as success and update counts without a synthetic table', () => {
        const { rerender } = render(
            <QueryResultsSection
                tab={{ ...resultTab, rowCount: 0, resultRows: [] }}
                view={view({
                    loadedRows: [],
                    visibleResultRows: {
                        start: 0,
                        end: 0,
                        topSpacerPx: 0,
                        bottomSpacerPx: 0,
                        rows: []
                    }
                })}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByText('0 rows')).toBeInTheDocument();
        expect(screen.getByText('No rows returned.')).toBeInTheDocument();
        rerender(
            <QueryResultsSection
                tab={{
                    ...resultTab,
                    resultColumns: [
                        { name: 'affected_rows', jdbcType: 'INTEGER', updateCount: true }
                    ],
                    resultRows: [['7']]
                }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByText('7 affected rows')).toBeInTheDocument();
        expect(screen.queryByRole('table')).not.toBeInTheDocument();
        rerender(
            <QueryResultsSection
                tab={{
                    ...resultTab,
                    resultColumns: [{ name: 'affected_rows', jdbcType: 'INTEGER' }],
                    resultRows: [['7']]
                }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByRole('table')).toBeInTheDocument();
    });
    it('shows failed/running/cancelled diagnostics and keeps Stats accessible', () => {
        const { rerender } = render(
            <QueryResultsSection
                tab={{
                    ...resultTab,
                    resultColumns: [],
                    isExecuting: true,
                    executionStatus: 'RUNNING'
                }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByLabelText('Running')).toBeInTheDocument();
        rerender(
            <QueryResultsSection
                tab={{ ...resultTab, executionStatus: 'FAILED', errorMessage: 'Query timed out' }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByRole('alert')).toHaveTextContent('Query timed out');
        fireEvent.click(screen.getByRole('tab', { name: 'Stats' }));
        expect(screen.getByRole('tabpanel')).toBeInTheDocument();
        rerender(
            <QueryResultsSection
                tab={{ ...resultTab, executionStatus: 'CANCELED' }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByLabelText('CANCELED')).toBeInTheDocument();
    });
    it('requires a reported row cap for local JSON export while keeping CSV server-enforced', () => {
        const { rerender } = render(
            <QueryResultsSection
                tab={{ ...resultTab, maxExportRows: undefined }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        fireEvent.click(screen.getByRole('button', { name: 'Export' }));
        expect(screen.getByRole('button', { name: 'Export JSON (loaded page)' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Export CSV (all result rows)' })).toBeEnabled();
        rerender(
            <QueryResultsSection
                tab={{ ...resultTab, maxExportRows: 1 }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        expect(screen.getByRole('button', { name: 'Export JSON (loaded page)' })).toBeDisabled();
    });

    it('disables governed exports and respects CSV caps', () => {
        const { rerender } = render(
            <QueryResultsSection
                tab={{ ...resultTab, rowCount: 5001 }}
                view={view()}
                onCopyCell={copy()}
            />
        );
        fireEvent.click(screen.getByRole('button', { name: 'Export' }));
        expect(screen.getByRole('button', { name: 'Export CSV (all result rows)' })).toBeDisabled();
        expect(screen.getByText(/This result exceeds the 5,000-row CSV limit/)).toBeInTheDocument();
        rerender(
            <QueryResultsSection
                tab={resultTab}
                view={view()}
                onCopyCell={copy()}
                canExport={false}
            />
        );
        expect(screen.getByRole('button', { name: 'Export JSON (loaded page)' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Export CSV (all result rows)' })).toBeDisabled();
    });
});

describe('local export provenance', () => {
    it('uses the executed profile and connection instead of a later editor selection', () => {
        const policy = {
            readOnly: true,
            maxRowsPerQuery: 5000,
            maxRuntimeSeconds: 60,
            concurrencyLimit: 1,
            sysadmin: false,
            justificationMode: 'NONE' as const
        };
        const sources: CatalogDatasourceResponse[] = [
            {
                id: 'original',
                name: 'original',
                engine: 'POSTGRESQL',
                credentialProfiles: ['restricted', 'allowed'],
                credentialProfilePolicies: [
                    { ...policy, credentialProfile: 'restricted', canExport: false },
                    { ...policy, credentialProfile: 'allowed', canExport: true }
                ]
            },
            {
                id: 'other',
                name: 'other',
                engine: 'MYSQL',
                credentialProfiles: ['restricted'],
                credentialProfilePolicies: [
                    { ...policy, credentialProfile: 'restricted', canExport: true }
                ]
            }
        ];
        const executed = {
            ...resultTab,
            executionDatasourceId: 'original',
            datasourceId: 'other',
            credentialProfile: 'restricted',
            requestedCredentialProfile: 'allowed'
        };
        expect(canExportLoadedResults(executed, sources)).toBe(false);
        expect(canExportLoadedResults({ ...executed, credentialProfile: 'allowed' }, sources)).toBe(
            true
        );
        expect(
            canExportLoadedResults({ ...executed, executionDatasourceId: undefined }, sources)
        ).toBe(false);
    });
});
