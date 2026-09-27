import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { ResultSortDirection, WorkspaceTab } from '../types';
import type { QueryResultsView } from '../useQueryResultsWorkflow';
import type { QueryCapabilities } from '../queryCapabilities';
import { sortDownIcon, sortNeutralIcon, sortUpIcon } from '../icons';
import { IconGlyph } from '../components/WorkbenchIcons';
import ResultPopover from '../components/ResultPopover';
import { csvExportAvailability } from '../queryResults';
import { formatExecutionDuration, formatExecutionTimestamp } from '../utils';

type Props = {
    onCopyCell: (value: string | null) => Promise<void>;
    tab: WorkspaceTab;
    view: QueryResultsView;
    expanded?: boolean;
    onToggleExpanded?: () => void;
    onClear?: () => void;
    onExplain?: () => void;
    onPlanPage?: (token: string, previousTokens: string[]) => void;
    capabilities?: QueryCapabilities;
    canExport?: boolean;
    notice?: ReactNode;
};
const ResultSortIcon = ({ direction }: { direction: ResultSortDirection | null }) => (
    <span
        className={`result-sort-icon ${direction ? `is-${direction}` : 'is-none'}`}
        aria-hidden
        dangerouslySetInnerHTML={{
            __html:
                direction === 'asc'
                    ? sortUpIcon
                    : direction === 'desc'
                      ? sortDownIcon
                      : sortNeutralIcon
        }}
    />
);
const formatCount = (value: number) => value.toLocaleString();
const countLabel = (value: number, noun: string) =>
    `${formatCount(value)} ${noun}${value === 1 ? '' : 's'}`;

export default function QueryResultsSection({
    onCopyCell,
    tab,
    view,
    expanded = false,
    onToggleExpanded,
    onClear,
    onExplain,
    onPlanPage,
    capabilities,
    canExport = true,
    notice
}: Props) {
    const [panel, setPanel] = useState<'results' | 'plan' | 'stats'>(
        tab.lastRunKind === 'explain' || tab.lastRunKind === 'analyze' ? 'plan' : 'results'
    );
    const [hidden, setHidden] = useState<number[]>([]);
    const [selectedRows, setSelectedRows] = useState<number[]>([]);
    const [selectedCells, setSelectedCells] = useState<string[]>([]);
    const [columnsOpen, setColumnsOpen] = useState(false);
    const [moreOpen, setMoreOpen] = useState(false);
    const [exportOpen, setExportOpen] = useState(false);
    const root = useRef<HTMLDivElement>(null);
    const tableWrap = useRef<HTMLDivElement>(null);
    const columnsTrigger = useRef<HTMLButtonElement>(null);
    const exportTrigger = useRef<HTMLButtonElement>(null);
    const moreTrigger = useRef<HTMLButtonElement>(null);
    const {
        resultSortState,
        resultsPageSize,
        visibleResultRows,
        onResultGridScroll,
        onViewportHeightChange
    } = view;
    const plan =
        tab.planExecution ??
        (tab.lastRunKind === 'explain' || tab.lastRunKind === 'analyze' ? tab : undefined);
    const planExecutionId = tab.planExecution?.executionId;
    useEffect(() => {
        if (planExecutionId !== undefined) setPanel('plan');
    }, [planExecutionId]);
    const updateCount = tab.resultColumns.length === 1 && tab.resultColumns[0].updateCount === true;
    const affectedRows =
        updateCount && tab.resultRows[0]?.[0] !== null ? Number(tab.resultRows[0]?.[0]) : NaN;
    const hasTable = tab.resultColumns.length > 0 && !updateCount;
    const completed = tab.executionStatus === 'SUCCEEDED';
    const warning =
        ['CANCELED', 'CANCELLED', 'TIMED_OUT'].includes(tab.executionStatus) || tab.rowLimitReached;
    const failed = tab.executionStatus === 'FAILED' || !!tab.errorMessage;
    const duration =
        tab.startedAt && tab.completedAt
            ? formatExecutionDuration(tab.startedAt, tab.completedAt)
            : '';
    const columns = tab.resultColumns
        .map((column, index) => ({ ...column, index }))
        .filter((column) => !hidden.includes(column.index));
    const page = tab.previousPageTokens.length + 1;
    const pages = Math.max(page, Math.ceil(tab.rowCount / resultsPageSize));
    const pageOffset = (page - 1) * resultsPageSize;
    const activeSort = resultSortState ? tab.resultColumns[resultSortState.columnIndex] : null;
    const exportAvailability = csvExportAvailability(tab);
    const loadedJsonWithinLimit =
        Number.isInteger(tab.maxExportRows) &&
        (tab.maxExportRows ?? 0) > 0 &&
        tab.resultRows.length <= (tab.maxExportRows ?? 0);
    const loadedRows = view.loadedRows;
    const planText =
        plan && plan.resultRows.length
            ? [
                  ...(plan.resultColumns.length > 1
                      ? [plan.resultColumns.map((column) => column.name).join(' | ')]
                      : []),
                  ...plan.resultRows.map((row) => row.map((value) => value ?? 'NULL').join(' | '))
              ].join('\n')
            : '';
    let formattedPlan = planText;
    try {
        formattedPlan = JSON.stringify(JSON.parse(planText), null, 2);
    } catch {
        /* Text plans retain indentation and operator labels. */
    }

    useEffect(() => {
        setSelectedRows([]);
        setSelectedCells([]);
        if (tableWrap.current) tableWrap.current.scrollTop = 0;
        onResultGridScroll(0);
    }, [
        tab.currentPageToken,
        tab.resultRows,
        view.search,
        view.density,
        resultSortState,
        onResultGridScroll
    ]);
    useEffect(() => {
        const element = tableWrap.current;
        if (!element || typeof ResizeObserver === 'undefined') return;
        const observer = new ResizeObserver(() => onViewportHeightChange(element.clientHeight));
        observer.observe(element);
        return () => observer.disconnect();
    }, [panel, hasTable, onViewportHeightChange]);
    useEffect(() => {
        const close = (event: MouseEvent) => {
            if (!root.current?.contains(event.target as Node)) {
                setColumnsOpen(false);
                setMoreOpen(false);
                setExportOpen(false);
            }
        };
        document.addEventListener('mousedown', close);
        return () => document.removeEventListener('mousedown', close);
    }, []);
    const reset = () => {
        setHidden([]);
        setSelectedRows([]);
        setSelectedCells([]);
        view.onClearSort();
        view.onSearchChange('');
    };
    const copyRows = (rows: Array<Array<string | null>>) =>
        void onCopyCell(
            rows.map((row) => row.map((value) => value ?? 'NULL').join('\t')).join('\n')
        );
    const downloadJson = () => {
        // A columns + rows envelope preserves duplicate SQL column names and NULL values.
        const blob = new Blob(
            [
                JSON.stringify(
                    {
                        columns: tab.resultColumns.map((column) => column.name),
                        rows: tab.resultRows
                    },
                    null,
                    2
                )
            ],
            { type: 'application/json' }
        );
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `query-${tab.executionId}-loaded-page.json`;
        anchor.click();
        URL.revokeObjectURL(url);
        setMoreOpen(false);
        setExportOpen(false);
    };
    const stats: Array<[string, string]> = [];
    if (duration) stats.push(['Execution duration', duration]);
    if (completed && !updateCount)
        stats.push(
            ['Returned rows', formatCount(tab.rowCount)],
            ['Returned columns', formatCount(tab.columnCount)]
        );
    if (Number.isFinite(affectedRows) && affectedRows >= 0)
        stats.push(['Affected rows', formatCount(affectedRows)]);
    if (tab.maxRowsPerQuery > 0) stats.push(['Query row limit', formatCount(tab.maxRowsPerQuery)]);
    if (tab.maxRuntimeSeconds > 0) stats.push(['Runtime limit', `${tab.maxRuntimeSeconds}s`]);
    if (tab.submittedAt && tab.startedAt) {
        const queued = Date.parse(tab.startedAt) - Date.parse(tab.submittedAt);
        if (Number.isFinite(queued) && queued >= 0)
            stats.push(['App queue time', `${formatCount(queued)} ms`]);
    }

    const explainButton = capabilities?.explain ? (
        <button
            type="button"
            className="result-plan-trigger"
            title="Run EXPLAIN for the current statement or selection. Your query results are preserved."
            disabled={tab.isExecuting || plan?.isExecuting}
            onClick={onExplain}
        >
            <IconGlyph icon={plan ? 'refresh' : 'plan'} />
            {plan ? 'Refresh plan' : 'Explain'}
        </button>
    ) : null;

    return (
        <div
            className={`execution-results density-${view.density}`}
            ref={root}
            onKeyDown={(event) => {
                if (event.key === 'Escape' && (columnsOpen || moreOpen || exportOpen)) {
                    event.stopPropagation();
                    (columnsOpen
                        ? columnsTrigger
                        : exportOpen
                          ? exportTrigger
                          : moreTrigger
                    ).current?.focus();
                    setColumnsOpen(false);
                    setMoreOpen(false);
                    setExportOpen(false);
                }
            }}
        >
            <div className="result-toolbar">
                <div
                    className="result-tabs"
                    role="tablist"
                    aria-label="Execution results"
                    onKeyDown={(event) => {
                        const choices = ['results', 'plan', 'stats'] as const;
                        if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
                            event.preventDefault();
                            const next =
                                event.key === 'Home'
                                    ? 0
                                    : event.key === 'End'
                                      ? 2
                                      : (choices.indexOf(panel) +
                                            (event.key === 'ArrowRight' ? 1 : 2)) %
                                        3;
                            setPanel(choices[next]);
                            event.currentTarget
                                .querySelectorAll<HTMLButtonElement>('[role="tab"]')
                                .item(next)
                                ?.focus();
                        }
                    }}
                >
                    {(['results', 'plan', 'stats'] as const).map((item, index) => (
                        <button
                            key={item}
                            id={`${tab.id}-${item}-tab`}
                            type="button"
                            role="tab"
                            aria-selected={panel === item}
                            aria-controls={`${tab.id}-result-panel`}
                            tabIndex={panel === item ? 0 : -1}
                            title={
                                [
                                    'Inspect returned rows',
                                    'Inspect the execution plan',
                                    'Inspect execution costs'
                                ][index]
                            }
                            onClick={() => setPanel(item)}
                        >
                            <IconGlyph icon={(['table', 'plan', 'chart'] as const)[index]} />
                            {['Results', 'Query Plan', 'Stats'][index]}
                        </button>
                    ))}
                </div>
                <div className="result-view-controls">
                    {panel === 'results' && hasTable && (
                        <>
                            <input
                                type="search"
                                className="result-search"
                                placeholder="Search results…"
                                aria-label="Search results on loaded page"
                                title={`Search only the ${formatCount(tab.resultRows.length)} loaded rows; does not run SQL`}
                                value={view.search}
                                onChange={(event) => view.onSearchChange(event.target.value)}
                            />
                            <div className="result-popover-anchor">
                                <button
                                    ref={columnsTrigger}
                                    type="button"
                                    title="Show or hide columns for this result"
                                    aria-expanded={columnsOpen}
                                    aria-haspopup="dialog"
                                    onClick={() => {
                                        setColumnsOpen(!columnsOpen);
                                        setMoreOpen(false);
                                        setExportOpen(false);
                                    }}
                                >
                                    Show columns
                                </button>
                                {columnsOpen && (
                                    <ResultPopover
                                        className="columns-popover"
                                        label="Result columns"
                                        anchor={columnsTrigger}
                                    >
                                        <button type="button" onClick={() => setHidden([])}>
                                            Restore all columns
                                        </button>
                                        {tab.resultColumns.map((column, index) => (
                                            <label key={index}>
                                                <input
                                                    type="checkbox"
                                                    checked={!hidden.includes(index)}
                                                    onChange={() =>
                                                        setHidden((current) =>
                                                            current.includes(index)
                                                                ? current.filter(
                                                                      (value) => value !== index
                                                                  )
                                                                : [...current, index]
                                                        )
                                                    }
                                                />
                                                {column.name}
                                            </label>
                                        ))}
                                    </ResultPopover>
                                )}
                            </div>
                            <label
                                className="result-density"
                                title="Change row height and vertical padding"
                            >
                                <select
                                    aria-label="Density"
                                    value={view.density}
                                    onChange={(event) =>
                                        view.onDensityChange(
                                            event.target.value as 'compact' | 'comfortable'
                                        )
                                    }
                                >
                                    <option value="comfortable">Comfortable</option>
                                    <option value="compact">Compact</option>
                                </select>
                            </label>
                        </>
                    )}
                    <div className="result-popover-anchor">
                        <button
                            ref={exportTrigger}
                            type="button"
                            title="Export results"
                            aria-expanded={exportOpen}
                            aria-haspopup="dialog"
                            onClick={() => {
                                setExportOpen(!exportOpen);
                                setColumnsOpen(false);
                                setMoreOpen(false);
                            }}
                        >
                            <IconGlyph icon="download" />
                            Export
                        </button>
                        {exportOpen && (
                            <ResultPopover
                                className="result-export-menu"
                                label="Export results"
                                anchor={exportTrigger}
                            >
                                <strong>Export results</strong>
                                <label>
                                    <input
                                        type="checkbox"
                                        checked={view.exportIncludeHeaders}
                                        onChange={(event) =>
                                            view.onExportIncludeHeadersChange(event.target.checked)
                                        }
                                    />
                                    Include CSV headers
                                </label>
                                <button
                                    type="button"
                                    disabled={
                                        !canExport ||
                                        !hasTable ||
                                        !completed ||
                                        view.exportingCsv ||
                                        exportAvailability.exceeded
                                    }
                                    onClick={() => void view.onExportCsv()}
                                >
                                    {view.exportingCsv
                                        ? 'Exporting...'
                                        : 'Export CSV (all result rows)'}
                                </button>
                                <p className="result-export-note">{exportAvailability.note}</p>
                                <button
                                    type="button"
                                    disabled={
                                        !canExport ||
                                        !hasTable ||
                                        !completed ||
                                        !loadedJsonWithinLimit
                                    }
                                    title={
                                        loadedJsonWithinLimit
                                            ? 'Export the original loaded page, including hidden columns'
                                            : 'JSON export requires a reported export limit large enough for the loaded page'
                                    }
                                    onClick={downloadJson}
                                >
                                    Export JSON (loaded page)
                                </button>
                            </ResultPopover>
                        )}
                    </div>
                    <button
                        type="button"
                        className="result-expand"
                        title={
                            expanded
                                ? 'Restore editor (Esc)'
                                : 'Expand results; temporarily hide SQL editor'
                        }
                        aria-pressed={expanded}
                        onClick={onToggleExpanded}
                    >
                        <svg
                            viewBox="0 0 20 20"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="1.5"
                            aria-hidden
                        >
                            <path
                                d={
                                    expanded
                                        ? 'M2 7h5V2m6 0v5h5M2 13h5v5m6 0v-5h5'
                                        : 'M7 2H2v5m11-5h5v5M2 13v5h5m6 0h5v-5'
                                }
                            />
                        </svg>
                        {expanded ? 'Restore' : 'Expand'}
                    </button>
                    <div className="result-popover-anchor">
                        <button
                            ref={moreTrigger}
                            type="button"
                            className="result-more"
                            title="More result actions"
                            aria-label="More result actions"
                            aria-expanded={moreOpen}
                            aria-haspopup="dialog"
                            onClick={() => {
                                setMoreOpen(!moreOpen);
                                setExportOpen(false);
                                setColumnsOpen(false);
                            }}
                        >
                            ⋮
                        </button>
                        {moreOpen && (
                            <ResultPopover
                                className="result-more-popover"
                                label="Result actions"
                                anchor={moreTrigger}
                            >
                                <strong>Copy</strong>
                                <button
                                    type="button"
                                    title={
                                        selectedCells.length
                                            ? 'Copy selected cell values'
                                            : 'Select cells in the table first'
                                    }
                                    disabled={!selectedCells.length}
                                    onClick={() =>
                                        void onCopyCell(
                                            selectedCells
                                                .map((key) => {
                                                    const [row, col] = key.split(':').map(Number);
                                                    return loadedRows[row]?.[col] ?? 'NULL';
                                                })
                                                .join('\t')
                                        )
                                    }
                                >
                                    <IconGlyph icon="copy" />
                                    <span>Copy selected cells</span>
                                </button>
                                <button
                                    type="button"
                                    title={
                                        selectedRows.length
                                            ? 'Copy selected rows'
                                            : 'Select rows using the checkboxes first'
                                    }
                                    disabled={!selectedRows.length}
                                    onClick={() =>
                                        copyRows(
                                            loadedRows.filter((_, index) =>
                                                selectedRows.includes(index)
                                            )
                                        )
                                    }
                                >
                                    <IconGlyph icon="copy" />
                                    <span>Copy selected rows</span>
                                </button>
                                <button
                                    type="button"
                                    disabled={!hasTable || !tab.resultRows.length}
                                    onClick={() => copyRows(tab.resultRows)}
                                >
                                    <IconGlyph icon="copy" />
                                    <span>Copy all loaded results</span>
                                </button>
                                <strong>Table view</strong>
                                <button
                                    type="button"
                                    disabled={!resultSortState}
                                    onClick={view.onClearSort}
                                >
                                    <IconGlyph icon="close" />
                                    <span>Clear sorting</span>
                                </button>
                                <button
                                    type="button"
                                    disabled={!view.search}
                                    onClick={() => view.onSearchChange('')}
                                >
                                    <IconGlyph icon="search" />
                                    <span>Clear search</span>
                                </button>
                                <button type="button" onClick={reset}>
                                    <IconGlyph icon="refresh" />
                                    <span>Reset table view</span>
                                </button>
                                <strong>Result</strong>
                                <button
                                    type="button"
                                    disabled={
                                        tab.isExecuting || plan?.isExecuting || !tab.executionId
                                    }
                                    onClick={onClear}
                                >
                                    <IconGlyph icon="delete" />
                                    <span>Clear results</span>
                                </button>
                            </ResultPopover>
                        )}
                    </div>
                </div>
            </div>
            <div className="result-meta-row">
                <div className="result-execution-summary" role="status">
                    {tab.executionId || tab.isExecuting || failed ? (
                        <>
                            <span
                                className={`execution-state ${failed ? 'is-error' : warning ? 'is-warning' : tab.isExecuting ? 'is-running' : 'is-success'}`}
                                title={tab.isExecuting ? 'Running' : tab.executionStatus}
                                aria-label={
                                    tab.isExecuting
                                        ? 'Running'
                                        : failed
                                          ? 'Failed'
                                          : warning
                                            ? tab.rowLimitReached
                                                ? 'Partial: query row limit reached'
                                                : tab.executionStatus
                                            : 'Succeeded'
                                }
                            >
                                {tab.isExecuting ? (
                                    <span className="execution-spinner" />
                                ) : failed ? (
                                    '×'
                                ) : warning ? (
                                    '!'
                                ) : (
                                    '✓'
                                )}
                            </span>
                            {tab.isExecuting ? (
                                <span>
                                    {tab.executionStatus === 'QUEUED' ? 'Queued' : 'Running'}
                                </span>
                            ) : failed ? (
                                <span>Failed</span>
                            ) : warning && !completed ? (
                                <span>
                                    {tab.executionStatus.toLowerCase().replaceAll('_', ' ')}
                                </span>
                            ) : hasTable ? (
                                <span aria-label="Returned rows">
                                    {countLabel(tab.rowCount, 'row')}
                                </span>
                            ) : (
                                <span>
                                    {Number.isFinite(affectedRows) && affectedRows >= 0
                                        ? countLabel(affectedRows, 'affected row')
                                        : 'Succeeded'}
                                </span>
                            )}
                            {duration && <span>{duration}</span>}
                            {hasTable && <span>{countLabel(tab.columnCount, 'column')}</span>}
                            {tab.maxRowsPerQuery > 0 && (
                                <span title="Query limit: maximum rows this execution can return">
                                    Limit {formatCount(tab.maxRowsPerQuery)}
                                </span>
                            )}
                        </>
                    ) : (
                        <span>Run a query to see results</span>
                    )}
                </div>
                {panel === 'results' && hasTable && (
                    <div className="result-pagination">
                        <label>
                            Show rows{' '}
                            <select
                                aria-label="Show rows"
                                title="Rows displayed per page, independent of query limit"
                                value={resultsPageSize}
                                onChange={(event) =>
                                    view.onResultsPageSizeChange(Number(event.target.value))
                                }
                            >
                                {[10, 100, 250, 500, 1000].map((size) => (
                                    <option key={size} value={size}>
                                        {size}
                                    </option>
                                ))}
                            </select>
                        </label>
                        <button
                            type="button"
                            aria-label="Previous Page"
                            title="Previous page"
                            onClick={view.onLoadPreviousResults}
                            disabled={!tab.previousPageTokens.length}
                        >
                            ‹
                        </button>
                        <span>
                            {page} of {pages}
                        </span>
                        <button
                            type="button"
                            aria-label="Next Page"
                            title="Next page"
                            onClick={view.onLoadNextResults}
                            disabled={!tab.nextPageToken}
                        >
                            ›
                        </button>
                    </div>
                )}
            </div>
            {tab.errorMessage && (
                <div className="result-error" role="alert">
                    {tab.errorMessage}
                </div>
            )}
            {tab.rowLimitReached && (
                <div className="result-warning">
                    Query row limit reached. These results may be partial.
                </div>
            )}
            {tab.executionId &&
                !tab.isExecuting &&
                tab.statusMessage &&
                !tab.errorMessage &&
                !/^query (succeeded|canceled|cancelled)\.?$/i.test(tab.statusMessage.trim()) && (
                    <div className="result-scope-note result-diagnostic">{tab.statusMessage}</div>
                )}
            {notice}
            <div
                id={`${tab.id}-result-panel`}
                role="tabpanel"
                aria-labelledby={`${tab.id}-${panel}-tab`}
                className="result-panel"
                tabIndex={0}
            >
                {panel === 'plan' ? (
                    <div className="result-plan">
                        {plan && <div className="result-plan-actions">{explainButton}</div>}
                        {plan?.isExecuting ? (
                            <div className="result-plan-empty" role="status">
                                <span className="execution-spinner" />
                                <span>Generating plan…</span>
                            </div>
                        ) : plan?.errorMessage ? (
                            <div className="result-error" role="alert">
                                {plan.errorMessage}
                            </div>
                        ) : planText ? (
                            <>
                                <pre>{formattedPlan}</pre>
                                {plan &&
                                    (plan.nextPageToken || plan.previousPageTokens.length > 0) && (
                                        <div
                                            className="result-plan-actions"
                                            aria-label="Plan pagination"
                                        >
                                            <button
                                                type="button"
                                                disabled={!plan.previousPageTokens.length}
                                                onClick={() =>
                                                    onPlanPage?.(
                                                        plan.previousPageTokens.at(-1) ?? '',
                                                        plan.previousPageTokens.slice(0, -1)
                                                    )
                                                }
                                            >
                                                Previous plan page
                                            </button>
                                            <span>Page {plan.previousPageTokens.length + 1}</span>
                                            <button
                                                type="button"
                                                disabled={!plan.nextPageToken}
                                                onClick={() =>
                                                    onPlanPage?.(plan.nextPageToken, [
                                                        ...plan.previousPageTokens,
                                                        plan.currentPageToken
                                                    ])
                                                }
                                            >
                                                Next plan page
                                            </button>
                                        </div>
                                    )}
                            </>
                        ) : (
                            <div className="result-plan-empty">
                                <span className="result-plan-empty-icon">
                                    <IconGlyph icon="plan" />
                                </span>
                                <span>
                                    {!capabilities?.explain
                                        ? 'Plans unavailable for this connection.'
                                        : plan
                                          ? plan.executionStatus === 'CANCELED' ||
                                            plan.executionStatus === 'CANCELLED'
                                              ? 'Plan request canceled.'
                                              : plan.executionStatus === 'TIMED_OUT'
                                                ? 'Plan request timed out.'
                                                : 'No plan returned.'
                                          : 'No plan yet'}
                                </span>
                                {!plan && explainButton}
                            </div>
                        )}
                    </div>
                ) : panel === 'stats' ? (
                    <div className="result-stats-panel">
                        {stats.length ? (
                            <dl>
                                {stats.map(([label, value]) => (
                                    <div key={label}>
                                        <dt>{label}</dt>
                                        <dd>{value}</dd>
                                    </div>
                                ))}
                            </dl>
                        ) : (
                            <p>Execution metrics will appear when available.</p>
                        )}
                        {(tab.submittedAt || tab.completedAt) && (
                            <dl className="result-stats-timeline">
                                {tab.submittedAt && (
                                    <div>
                                        <dt>Submitted</dt>
                                        <dd>{formatExecutionTimestamp(tab.submittedAt)}</dd>
                                    </div>
                                )}
                                {tab.completedAt && (
                                    <div>
                                        <dt>Completed</dt>
                                        <dd>{formatExecutionTimestamp(tab.completedAt)}</dd>
                                    </div>
                                )}
                            </dl>
                        )}
                        <p className="result-stats-note">
                            <IconGlyph icon="info" />
                            Additional engine metrics were not reported for this execution.
                        </p>
                        {tab.scriptSummary && (
                            <details className="script-summary">
                                <summary>
                                    Script: {tab.scriptSummary.statementCount} statements
                                </summary>
                                <ol>
                                    {tab.scriptSummary.statements.map((statement) => (
                                        <li key={statement.index}>
                                            <strong>{statement.status}</strong>{' '}
                                            <code>{statement.sqlPreview}</code>
                                            <p>{statement.message}</p>
                                        </li>
                                    ))}
                                </ol>
                            </details>
                        )}
                    </div>
                ) : hasTable ? (
                    <div className="results-body">
                        {(activeSort || view.search) && (
                            <div className="result-local-state" aria-live="polite">
                                {activeSort && resultSortState && (
                                    <span>
                                        Sorted by {activeSort.name}{' '}
                                        {resultSortState.direction === 'asc' ? '↑' : '↓'} (loaded
                                        page)
                                    </span>
                                )}
                                {view.search && (
                                    <span>
                                        {loadedRows.length} matches in {tab.resultRows.length}{' '}
                                        loaded rows
                                    </span>
                                )}
                            </div>
                        )}
                        <div
                            className="result-table-wrap"
                            ref={tableWrap}
                            onScroll={(event) => onResultGridScroll(event.currentTarget.scrollTop)}
                        >
                            <table className="result-table">
                                <thead>
                                    <tr>
                                        <th className="result-meta-heading">#</th>
                                        {columns.map((column) => {
                                            const direction =
                                                resultSortState?.columnIndex === column.index
                                                    ? resultSortState.direction
                                                    : null;
                                            return (
                                                <th
                                                    key={column.index}
                                                    aria-sort={
                                                        direction === 'asc'
                                                            ? 'ascending'
                                                            : direction === 'desc'
                                                              ? 'descending'
                                                              : 'none'
                                                    }
                                                >
                                                    <button
                                                        type="button"
                                                        className="result-sort-trigger"
                                                        onClick={() =>
                                                            view.onToggleResultSort(column.index)
                                                        }
                                                        title={`Sort current page by ${column.name}`}
                                                        aria-label={`Sort current page by ${column.name}`}
                                                    >
                                                        <span>{column.name}</span>
                                                        <ResultSortIcon direction={direction} />
                                                    </button>
                                                </th>
                                            );
                                        })}
                                    </tr>
                                </thead>
                                <tbody>
                                    {visibleResultRows.topSpacerPx > 0 && (
                                        <tr aria-hidden>
                                            <td
                                                colSpan={columns.length + 1}
                                                style={{
                                                    height: visibleResultRows.topSpacerPx,
                                                    padding: 0,
                                                    border: 0
                                                }}
                                            />
                                        </tr>
                                    )}
                                    {visibleResultRows.rows.map((row, relativeIndex) => {
                                        const index = visibleResultRows.start + relativeIndex;
                                        return (
                                            <tr
                                                key={index}
                                                className={
                                                    (pageOffset + index) % 2
                                                        ? 'result-row-alt'
                                                        : undefined
                                                }
                                            >
                                                <td className="result-row-index">
                                                    <label title="Select row to copy">
                                                        <input
                                                            type="checkbox"
                                                            aria-label={`Select row ${pageOffset + index + 1}`}
                                                            checked={selectedRows.includes(index)}
                                                            onChange={() =>
                                                                setSelectedRows((current) =>
                                                                    current.includes(index)
                                                                        ? current.filter(
                                                                              (item) =>
                                                                                  item !== index
                                                                          )
                                                                        : [...current, index]
                                                                )
                                                            }
                                                        />
                                                        {pageOffset + index + 1}
                                                    </label>
                                                </td>
                                                {columns.map((column) => {
                                                    const value = row[column.index] ?? null;
                                                    const key = `${index}:${column.index}`;
                                                    return (
                                                        <td key={column.index}>
                                                            <div className="result-cell">
                                                                <button
                                                                    type="button"
                                                                    className="result-value"
                                                                    title={`${value ?? 'NULL'} (click to select cell)`}
                                                                    aria-label={`Select ${column.name}, row ${pageOffset + index + 1}`}
                                                                    aria-pressed={selectedCells.includes(
                                                                        key
                                                                    )}
                                                                    onClick={() =>
                                                                        setSelectedCells(
                                                                            (current) =>
                                                                                current.includes(
                                                                                    key
                                                                                )
                                                                                    ? current.filter(
                                                                                          (item) =>
                                                                                              item !==
                                                                                              key
                                                                                      )
                                                                                    : [
                                                                                          ...current,
                                                                                          key
                                                                                      ]
                                                                        )
                                                                    }
                                                                >
                                                                    {value ?? 'NULL'}
                                                                </button>
                                                                <button
                                                                    type="button"
                                                                    className="result-copy-icon"
                                                                    onClick={() =>
                                                                        void onCopyCell(value)
                                                                    }
                                                                    title="Copy cell value"
                                                                    aria-label="Copy cell value"
                                                                >
                                                                    <IconGlyph icon="copy" />
                                                                </button>
                                                            </div>
                                                        </td>
                                                    );
                                                })}
                                            </tr>
                                        );
                                    })}
                                    {visibleResultRows.bottomSpacerPx > 0 && (
                                        <tr aria-hidden>
                                            <td
                                                colSpan={columns.length + 1}
                                                style={{
                                                    height: visibleResultRows.bottomSpacerPx,
                                                    padding: 0,
                                                    border: 0
                                                }}
                                            />
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                            {!loadedRows.length && (
                                <p className="result-empty-state">
                                    {view.search
                                        ? 'No matches on this loaded page.'
                                        : 'No rows returned.'}
                                </p>
                            )}
                            {!columns.length && (
                                <p className="result-empty-state">
                                    All columns are hidden. Restore them using Show columns.
                                </p>
                            )}
                        </div>
                        <footer className="result-table-footer">
                            <span>
                                {view.search
                                    ? `${loadedRows.length} matching rows on this page`
                                    : `Showing ${tab.resultRows.length ? formatCount(pageOffset + 1) : '0'}–${formatCount(pageOffset + tab.resultRows.length)} of ${countLabel(tab.rowCount, 'row')}`}
                            </span>
                            <span>Search and sorting apply to the loaded page only</span>
                        </footer>
                    </div>
                ) : (
                    <div className="result-empty-state">
                        {tab.isExecuting
                            ? 'Waiting for execution to finish…'
                            : completed
                              ? updateCount
                                  ? 'Statement completed successfully.'
                                  : 'Completed without a result set.'
                              : failed
                                ? 'Execution failed. Review the error above and available Stats.'
                                : tab.executionId
                                  ? 'No result set available.'
                                  : 'Execute a statement or script to inspect its results.'}
                    </div>
                )}
            </div>
        </div>
    );
}
