import type { ResultSortDirection, WorkspaceTab } from '../types';
import type { QueryResultsView } from '../useQueryResultsWorkflow';
import { sortDownIcon, sortNeutralIcon, sortUpIcon } from '../icons';
import { IconButton, IconGlyph } from '../components/WorkbenchIcons';

type QueryResultsSectionProps = {
    onCopyCell: (value: string | null) => Promise<void>;
    tab: WorkspaceTab;
    view: QueryResultsView;
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

export default function QueryResultsSection({ onCopyCell, tab, view }: QueryResultsSectionProps) {
    if (tab.resultColumns.length === 0) {
        return null;
    }

    const {
        exportIncludeHeaders,
        exportMenuRef,
        exportingCsv,
        onExportCsv,
        onExportIncludeHeadersChange,
        onLoadNextResults,
        onLoadPreviousResults,
        onResultGridScroll,
        onResultsPageSizeChange,
        onToggleExportMenu,
        onToggleResultSort,
        resultSortState,
        resultsPageSize,
        showExportMenu,
        visibleResultRows
    } = view;
    const activeSortColumn = resultSortState
        ? tab.resultColumns[resultSortState.columnIndex]?.name
        : undefined;
    const sortStatus =
        activeSortColumn && resultSortState
            ? `${activeSortColumn} ${resultSortState.direction === 'asc' ? 'ascending' : 'descending'}`
            : 'none';

    return (
        <div className="results-body">
            <div className="result-actions row">
                <div className="result-pagination-controls row">
                    <button
                        type="button"
                        onClick={onLoadPreviousResults}
                        disabled={tab.previousPageTokens.length === 0}
                    >
                        Previous Page
                    </button>
                    <button type="button" onClick={onLoadNextResults} disabled={!tab.nextPageToken}>
                        Next Page
                    </button>
                    <div className="result-export-wrapper" ref={exportMenuRef}>
                        <IconButton
                            icon="download"
                            title="Export CSV"
                            onClick={onToggleExportMenu}
                            disabled={exportingCsv}
                        />
                        {showExportMenu ? (
                            <div className="result-export-popover" role="dialog">
                                <label className="checkbox-row">
                                    <input
                                        type="checkbox"
                                        checked={exportIncludeHeaders}
                                        onChange={(event) =>
                                            onExportIncludeHeadersChange(event.target.checked)
                                        }
                                    />
                                    <span>Include headers</span>
                                </label>
                                <button
                                    type="button"
                                    onClick={() => void onExportCsv()}
                                    disabled={exportingCsv}
                                >
                                    {exportingCsv ? 'Exporting...' : 'Download CSV'}
                                </button>
                                <p className="result-export-note">
                                    CSV exports the full result in its original order.
                                </p>
                            </div>
                        ) : null}
                    </div>
                </div>
                <span className="result-sort-scope" aria-live="polite">
                    Current page sort: {sortStatus}
                </span>
                <div className="result-page-size-inline">
                    <label htmlFor="result-page-size">Rows per page</label>
                    <div className="select-wrap">
                        <select
                            id="result-page-size"
                            value={resultsPageSize}
                            onChange={(event) =>
                                onResultsPageSizeChange(Number(event.target.value))
                            }
                        >
                            <option value={10}>10</option>
                            <option value={100}>100</option>
                            <option value={250}>250</option>
                            <option value={500}>500</option>
                            <option value={1000}>1000</option>
                        </select>
                    </div>
                </div>
            </div>
            <div
                className="result-table-wrap"
                onScroll={(event) => onResultGridScroll(event.currentTarget.scrollTop)}
            >
                <table className="result-table">
                    <thead>
                        <tr>
                            <th className="result-meta-heading">Row</th>
                            {tab.resultColumns.map((column, columnIndex) => {
                                const direction =
                                    resultSortState?.columnIndex === columnIndex
                                        ? resultSortState.direction
                                        : null;

                                return (
                                    <th
                                        key={`${column.name}-${column.jdbcType}-${columnIndex}`}
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
                                            onClick={() => onToggleResultSort(columnIndex)}
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
                        {visibleResultRows.topSpacerPx > 0 ? (
                            <tr>
                                <td
                                    colSpan={tab.resultColumns.length + 1}
                                    style={{
                                        height: `${visibleResultRows.topSpacerPx}px`,
                                        padding: 0,
                                        border: 'none'
                                    }}
                                />
                            </tr>
                        ) : null}
                        {visibleResultRows.rows.map((row, relativeIndex) => {
                            const absoluteIndex = visibleResultRows.start + relativeIndex;
                            const pageOffset =
                                (tab.previousPageTokens?.length ?? 0) * resultsPageSize;
                            return (
                                <tr key={`row-${absoluteIndex}`}>
                                    <td className="result-row-index">
                                        {pageOffset + absoluteIndex + 1}
                                    </td>
                                    {row.map((value, columnIndex) => (
                                        <td key={`cell-${absoluteIndex}-${columnIndex}`}>
                                            <div className="result-cell">
                                                <span>{value ?? 'NULL'}</span>
                                                <button
                                                    type="button"
                                                    className="result-copy-icon"
                                                    onClick={() => void onCopyCell(value)}
                                                    title="Copy cell value"
                                                    aria-label="Copy cell value"
                                                >
                                                    <IconGlyph icon="copy" />
                                                </button>
                                            </div>
                                        </td>
                                    ))}
                                </tr>
                            );
                        })}
                        {visibleResultRows.bottomSpacerPx > 0 ? (
                            <tr>
                                <td
                                    colSpan={tab.resultColumns.length + 1}
                                    style={{
                                        height: `${visibleResultRows.bottomSpacerPx}px`,
                                        padding: 0,
                                        border: 'none'
                                    }}
                                />
                            </tr>
                        ) : null}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
