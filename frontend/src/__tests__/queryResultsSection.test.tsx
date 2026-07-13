import { createRef } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import QueryResultsSection from '../workbench/sections/QueryResultsSection';
import type { QueryResultsView } from '../workbench/useQueryResultsWorkflow';
import { buildWorkspaceTab } from '../workbench/useWorkspaceTabs';

const view = (overrides: Partial<QueryResultsView> = {}): QueryResultsView => ({
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
    showExportMenu: true,
    visibleResultRows: {
        start: 0,
        end: 2,
        topSpacerPx: 0,
        bottomSpacerPx: 0,
        rows: [['alpha'], [null]]
    },
    ...overrides
});

const resultTab = {
    ...buildWorkspaceTab('starrocks-prod-adhoc', 'Query 1', 'select value'),
    resultColumns: [{ name: 'value', jdbcType: 'VARCHAR' }],
    resultRows: [['alpha'], [null]],
    nextPageToken: 'page-3',
    previousPageTokens: ['', 'page-1']
};

describe('QueryResultsSection', () => {
    it('preserves paging, export, page-size, sort and copy interactions', () => {
        const workflow = view();
        const onCopyCell = vi.fn().mockResolvedValue(undefined);
        render(<QueryResultsSection tab={resultTab} view={workflow} onCopyCell={onCopyCell} />);

        fireEvent.click(screen.getByRole('button', { name: 'Previous Page' }));
        fireEvent.click(screen.getByRole('button', { name: 'Next Page' }));
        expect(workflow.onLoadPreviousResults).toHaveBeenCalledOnce();
        expect(workflow.onLoadNextResults).toHaveBeenCalledOnce();

        fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
        fireEvent.click(screen.getByRole('checkbox', { name: 'Include headers' }));
        fireEvent.click(screen.getByRole('button', { name: 'Download CSV' }));
        expect(workflow.onToggleExportMenu).toHaveBeenCalledOnce();
        expect(workflow.onExportIncludeHeadersChange).toHaveBeenCalledWith(false);
        expect(workflow.onExportCsv).toHaveBeenCalledOnce();
        expect(
            screen.getByText('CSV exports the full result in its original order.')
        ).toBeInTheDocument();

        fireEvent.change(screen.getByLabelText('Rows per page'), { target: { value: '250' } });
        expect(workflow.onResultsPageSizeChange).toHaveBeenCalledWith(250);

        const sortButton = screen.getByRole('button', { name: 'Sort current page by value' });
        expect(sortButton.closest('th')).toHaveAttribute('aria-sort', 'none');
        expect(screen.getByText('Current page sort: none')).toBeInTheDocument();
        fireEvent.click(sortButton);
        expect(workflow.onToggleResultSort).toHaveBeenCalledWith(0);

        const copyButtons = screen.getAllByRole('button', { name: 'Copy cell value' });
        fireEvent.click(copyButtons[0]);
        fireEvent.click(copyButtons[1]);
        expect(onCopyCell).toHaveBeenNthCalledWith(1, 'alpha');
        expect(onCopyCell).toHaveBeenNthCalledWith(2, null);
        expect(screen.getByText('201')).toBeInTheDocument();
        expect(screen.getByText('202')).toBeInTheDocument();
    });

    it('keeps pagination disabled states and export loading text', () => {
        const workflow = view({ exportingCsv: true });
        const firstPageTab = {
            ...resultTab,
            nextPageToken: '',
            previousPageTokens: []
        };
        render(
            <QueryResultsSection
                tab={firstPageTab}
                view={workflow}
                onCopyCell={vi.fn().mockResolvedValue(undefined)}
            />
        );

        expect(screen.getByRole('button', { name: 'Previous Page' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Next Page' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Export CSV' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Exporting...' })).toBeDisabled();
    });

    it('announces the active current-page sort direction', () => {
        render(
            <QueryResultsSection
                tab={resultTab}
                view={view({
                    resultSortState: { columnIndex: 0, direction: 'desc' }
                })}
                onCopyCell={vi.fn().mockResolvedValue(undefined)}
            />
        );

        const sortButton = screen.getByRole('button', { name: 'Sort current page by value' });
        expect(sortButton.closest('th')).toHaveAttribute('aria-sort', 'descending');
        expect(screen.getByText('Current page sort: value descending')).toBeInTheDocument();
    });
});
