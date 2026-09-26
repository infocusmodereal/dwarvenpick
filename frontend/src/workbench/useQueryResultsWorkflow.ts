import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { firstPageToken, resultViewportHeightPx } from './constants';
import {
    buildCsvExportUrl,
    buildResultPageUrl,
    csvExportAvailability,
    csvExportFileName,
    nextResultPageRequest,
    previousResultPageRequest
} from './queryResults';
import type { QueryResultsResponse, ResultSortState, WorkspaceTab } from './types';
import { compareResultValues } from './utils';

type UpdateWorkspaceTab = (
    tabId: string,
    updater: (currentTab: WorkspaceTab) => WorkspaceTab
) => void;

export type VisibleResultRows = {
    start: number;
    end: number;
    topSpacerPx: number;
    bottomSpacerPx: number;
    rows: Array<Array<string | null>>;
};

export type QueryResultsView = {
    search: string;
    onSearchChange: (value: string) => void;
    density: 'comfortable' | 'compact';
    onDensityChange: (value: 'comfortable' | 'compact') => void;
    onViewportHeightChange: (height: number) => void;
    onClearSort: () => void;
    loadedRows: Array<Array<string | null>>;
    exportIncludeHeaders: boolean;
    exportMenuRef: RefObject<HTMLDivElement | null>;
    exportingCsv: boolean;
    onExportCsv: () => Promise<void>;
    onExportIncludeHeadersChange: (value: boolean) => void;
    onLoadNextResults: () => void;
    onLoadPreviousResults: () => void;
    onResultGridScroll: (scrollTop: number) => void;
    onResultsPageSizeChange: (pageSize: number) => void;
    onToggleExportMenu: () => void;
    onToggleResultSort: (columnIndex: number) => void;
    resultSortState: ResultSortState;
    resultsPageSize: number;
    showExportMenu: boolean;
    visibleResultRows: VisibleResultRows;
};

type UseQueryResultsWorkflowOptions = {
    activeTab: WorkspaceTab | null;
    activeTabId: string;
    onFeedback: (message: string, tone?: 'info' | 'success' | 'warning' | 'error') => void;
    readFriendlyError: (response: Response) => Promise<string>;
    updateWorkspaceTab: UpdateWorkspaceTab;
};

export const useQueryResultsWorkflow = ({
    activeTab,
    activeTabId,
    onFeedback,
    readFriendlyError,
    updateWorkspaceTab
}: UseQueryResultsWorkflowOptions): {
    fetchQueryResultsPage: (
        tabId: string,
        executionId: string,
        pageToken?: string,
        previousPageTokens?: string[]
    ) => Promise<void>;
    view: QueryResultsView;
} => {
    const [search, setSearch] = useState('');
    const [density, setDensity] = useState<'comfortable' | 'compact'>(() => {
        try {
            return localStorage.getItem('dwarvenpick.results.density') === 'comfortable'
                ? 'comfortable'
                : 'compact';
        } catch {
            return 'compact';
        }
    });
    const [viewportHeight, setViewportHeight] = useState(resultViewportHeightPx);
    const resultRowHeightPx = density === 'compact' ? 28 : 38;
    useEffect(() => {
        try {
            localStorage.setItem('dwarvenpick.results.density', density);
        } catch {
            /* Optional preference. */
        }
    }, [density]);
    const [exportIncludeHeaders, setExportIncludeHeaders] = useState(true);
    const [exportingCsv, setExportingCsv] = useState(false);
    const [showExportMenu, setShowExportMenu] = useState(false);
    const [resultsPageSize, setResultsPageSize] = useState(500);
    const [resultSortState, setResultSortState] = useState<ResultSortState>(null);
    const [resultGridScrollTop, setResultGridScrollTop] = useState(0);
    const exportMenuRef = useRef<HTMLDivElement>(null);
    const feedbackRef = useRef(onFeedback);
    feedbackRef.current = onFeedback;
    const pageRequestVersion = useRef<Record<string, number>>({});

    useEffect(() => {
        const handleOutsideClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (!target || !exportMenuRef.current?.contains(target)) {
                setShowExportMenu(false);
            }
        };

        if (showExportMenu) {
            document.addEventListener('mousedown', handleOutsideClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick);
        };
    }, [showExportMenu]);

    useEffect(() => {
        setSearch('');
        setResultGridScrollTop(0);
        setResultSortState(null);
        setShowExportMenu(false);
    }, [activeTabId, activeTab?.executionId]);

    const sortedResultRows = useMemo(() => {
        const needle = search.trim().toLocaleLowerCase();
        const rows = (activeTab?.resultRows ?? []).filter(
            (row) =>
                !needle ||
                row.some((value) => (value ?? 'NULL').toLocaleLowerCase().includes(needle))
        );
        if (!resultSortState) {
            return rows;
        }

        return [...rows].sort((left, right) =>
            compareResultValues(
                left[resultSortState.columnIndex] ?? null,
                right[resultSortState.columnIndex] ?? null,
                resultSortState.direction
            )
        );
    }, [activeTab?.resultRows, resultSortState, search]);

    const visibleResultRows = useMemo<VisibleResultRows>(() => {
        if (sortedResultRows.length === 0) {
            return {
                start: 0,
                end: 0,
                topSpacerPx: 0,
                bottomSpacerPx: 0,
                rows: []
            };
        }

        const viewportRows = Math.ceil(viewportHeight / resultRowHeightPx);
        const overscanRows = 8;
        const start = Math.max(
            0,
            Math.floor(resultGridScrollTop / resultRowHeightPx) - overscanRows
        );
        const end = Math.min(sortedResultRows.length, start + viewportRows + overscanRows * 2);

        return {
            start,
            end,
            topSpacerPx: start * resultRowHeightPx,
            bottomSpacerPx: Math.max(0, (sortedResultRows.length - end) * resultRowHeightPx),
            rows: sortedResultRows.slice(start, end)
        };
    }, [resultGridScrollTop, sortedResultRows, resultRowHeightPx, viewportHeight]);

    const fetchQueryResultsPage = useCallback(
        async (
            tabId: string,
            executionId: string,
            pageToken = firstPageToken,
            previousPageTokens?: string[]
        ) => {
            const requestVersion = (pageRequestVersion.current[tabId] ?? 0) + 1;
            pageRequestVersion.current[tabId] = requestVersion;
            const response = await fetch(
                buildResultPageUrl(executionId, resultsPageSize, pageToken),
                {
                    method: 'GET',
                    credentials: 'include'
                }
            );
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as QueryResultsResponse;
            updateWorkspaceTab(tabId, (currentTab) => {
                if (
                    currentTab.executionId !== executionId ||
                    pageRequestVersion.current[tabId] !== requestVersion
                ) {
                    return currentTab;
                }

                return {
                    ...currentTab,
                    resultColumns: payload.columns,
                    resultRows: payload.rows,
                    nextPageToken: payload.nextPageToken ?? '',
                    currentPageToken: pageToken,
                    previousPageTokens: previousPageTokens ?? currentTab.previousPageTokens,
                    rowLimitReached: payload.rowLimitReached,
                    errorMessage: ''
                };
            });
        },
        [readFriendlyError, resultsPageSize, updateWorkspaceTab]
    );

    const handleLoadNextResults = useCallback(() => {
        if (!activeTab) {
            return;
        }

        const request = nextResultPageRequest(activeTab);
        if (!request) {
            return;
        }

        void fetchQueryResultsPage(
            activeTab.id,
            activeTab.executionId,
            request.pageToken,
            request.previousPageTokens
        ).catch((error) =>
            onFeedback(
                error instanceof Error ? error.message : 'Unable to load result page.',
                'error'
            )
        );
    }, [activeTab, fetchQueryResultsPage, onFeedback]);

    const handleLoadPreviousResults = useCallback(() => {
        if (!activeTab) {
            return;
        }

        const request = previousResultPageRequest(activeTab);
        if (!request) {
            return;
        }

        void fetchQueryResultsPage(
            activeTab.id,
            activeTab.executionId,
            request.pageToken,
            request.previousPageTokens
        ).catch((error) =>
            onFeedback(
                error instanceof Error ? error.message : 'Unable to load result page.',
                'error'
            )
        );
    }, [activeTab, fetchQueryResultsPage, onFeedback]);

    useEffect(() => {
        if (!activeTab?.executionId || activeTab.executionStatus !== 'SUCCEEDED') {
            return;
        }

        setResultGridScrollTop(0);
        void fetchQueryResultsPage(activeTab.id, activeTab.executionId, firstPageToken, []).catch(
            (error) =>
                feedbackRef.current(
                    error instanceof Error ? error.message : 'Unable to load result page.',
                    'error'
                )
        );
    }, [
        activeTab?.executionId,
        activeTab?.executionStatus,
        activeTab?.id,
        fetchQueryResultsPage,
        resultsPageSize
    ]);

    const handleToggleResultSort = useCallback((columnIndex: number) => {
        setResultSortState((current) => {
            if (!current || current.columnIndex !== columnIndex) {
                return { columnIndex, direction: 'asc' };
            }
            if (current.direction === 'asc') {
                return { columnIndex, direction: 'desc' };
            }
            return null;
        });
    }, []);

    const handleExportCsv = useCallback(async () => {
        if (!activeTab?.executionId) {
            onFeedback('Run a query first to export CSV.', 'warning');
            return;
        }
        const exportAvailability = csvExportAvailability(activeTab);
        if (exportAvailability.exceeded) {
            onFeedback(exportAvailability.note, 'warning');
            return;
        }

        setExportingCsv(true);
        try {
            const response = await fetch(
                buildCsvExportUrl(activeTab.executionId, exportIncludeHeaders),
                {
                    method: 'GET',
                    credentials: 'include'
                }
            );
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const blob = await response.blob();
            const fallbackName = `query-${activeTab.executionId}.csv`;
            const fileName = csvExportFileName(
                response.headers.get('Content-Disposition'),
                fallbackName
            );
            const objectUrl = window.URL.createObjectURL(blob);
            const anchor = document.createElement('a');
            anchor.href = objectUrl;
            anchor.download = fileName;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            window.URL.revokeObjectURL(objectUrl);
            onFeedback(`CSV export downloaded: ${fileName}`, 'success');
            setShowExportMenu(false);
        } catch (error) {
            onFeedback(error instanceof Error ? error.message : 'CSV export failed.', 'error');
        } finally {
            setExportingCsv(false);
        }
    }, [activeTab, exportIncludeHeaders, onFeedback, readFriendlyError]);

    const handleResultsPageSizeChange = useCallback((pageSize: number) => {
        setResultsPageSize(pageSize);
        setResultGridScrollTop(0);
    }, []);

    return {
        fetchQueryResultsPage,
        view: {
            search,
            onSearchChange: (value) => {
                setSearch(value);
                setResultGridScrollTop(0);
            },
            density,
            onDensityChange: (value) => {
                setDensity(value);
                setResultGridScrollTop(0);
            },
            onViewportHeightChange: setViewportHeight,
            onClearSort: () => setResultSortState(null),
            loadedRows: sortedResultRows,
            exportIncludeHeaders,
            exportMenuRef,
            exportingCsv,
            onExportCsv: handleExportCsv,
            onExportIncludeHeadersChange: setExportIncludeHeaders,
            onLoadNextResults: handleLoadNextResults,
            onLoadPreviousResults: handleLoadPreviousResults,
            onResultGridScroll: setResultGridScrollTop,
            onResultsPageSizeChange: handleResultsPageSizeChange,
            onToggleExportMenu: () => setShowExportMenu((current) => !current),
            onToggleResultSort: handleToggleResultSort,
            resultSortState,
            resultsPageSize,
            showExportMenu,
            visibleResultRows
        }
    };
};
