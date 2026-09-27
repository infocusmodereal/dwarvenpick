import type { ControlPlaneActiveQuery, ControlPlaneDatasourceStatusResponse } from '../types';
import { toStatusToneClass } from '../utils';
import { IconButton, LabeledActionButton } from '../components/WorkbenchIcons';
import InlineNotice from '../components/InlineNotice';

export type ControlPlanePanelProps = {
    loading: boolean;
    error: string;
    response: ControlPlaneDatasourceStatusResponse | null;
    windowSeconds: number;
    onWindowSecondsChange: (value: number) => void;
    actorFilter: string;
    onActorFilterChange: (value: string) => void;
    autoRefresh: boolean;
    onAutoRefreshChange: (value: boolean) => void;
    onRefresh: () => void;
    onPause: () => void;
    onResume: () => void;
    onCancelAll: () => void;
    onKillAll: () => void;
    onCancelExecution: (executionId: string) => void;
    onKillExecution: (executionId: string) => void;
    onExportCsv: () => void;
};

const formatDuration = (durationMs: number | null | undefined): string => {
    if (durationMs === null || durationMs === undefined) {
        return '-';
    }
    if (durationMs < 1000) {
        return `${durationMs.toLocaleString()} ms`;
    }
    const seconds = Math.floor(durationMs / 1000);
    return `${seconds.toLocaleString()} s`;
};

const buildActorOptions = (queries: ControlPlaneActiveQuery[]): string[] =>
    Array.from(new Set(queries.map((query) => query.actor).filter(Boolean))).sort();

export default function SystemHealthActivity({
    datasourceId,
    view,
    controlPlane
}: {
    datasourceId: string;
    view: 'engine' | 'activity' | 'pools';
    controlPlane: ControlPlanePanelProps;
}) {
    const controlPlaneActorOptions = buildActorOptions(controlPlane.response?.activeQueries ?? []);
    const normalizedControlPlaneActorFilter = controlPlane.actorFilter.trim();
    const displayedActiveQueries = (controlPlane.response?.activeQueries ?? []).filter((query) =>
        normalizedControlPlaneActorFilter ? query.actor === normalizedControlPlaneActorFilter : true
    );

    return (
        <div className="panel-inner system-health-control-plane" hidden={view === 'engine'}>
            <div className="history-filters health-activity-filters">
                <div className="filter-field">
                    <label htmlFor="control-plane-window">Window</label>
                    <div className="select-wrap">
                        <select
                            id="control-plane-window"
                            value={controlPlane.windowSeconds}
                            onChange={(event) =>
                                controlPlane.onWindowSecondsChange(Number(event.target.value))
                            }
                        >
                            <option value={300}>Last 5 minutes</option>
                            <option value={900}>Last 15 minutes</option>
                            <option value={3600}>Last hour</option>
                            <option value={21600}>Last 6 hours</option>
                        </select>
                    </div>
                </div>

                <div className="filter-field" hidden={view !== 'activity'}>
                    <label htmlFor="control-plane-actor-filter">Query actor</label>
                    <input
                        id="control-plane-actor-filter"
                        value={controlPlane.actorFilter}
                        onChange={(event) => controlPlane.onActorFilterChange(event.target.value)}
                        placeholder="Filter actor..."
                        list="control-plane-actor-options"
                    />
                    <datalist id="control-plane-actor-options">
                        {controlPlaneActorOptions.map((actor) => (
                            <option key={`actor-${actor}`} value={actor} />
                        ))}
                    </datalist>
                </div>

                <div className="filter-field">
                    <label htmlFor="control-plane-auto-refresh">Auto Refresh</label>
                    <div className="select-wrap">
                        <select
                            id="control-plane-auto-refresh"
                            value={controlPlane.autoRefresh ? 'on' : 'off'}
                            onChange={(event) =>
                                controlPlane.onAutoRefreshChange(event.target.value === 'on')
                            }
                        >
                            <option value="on">On (5s)</option>
                            <option value="off">Off</option>
                        </select>
                    </div>
                </div>
            </div>

            <div className="row toolbar-actions">
                <IconButton
                    icon="refresh"
                    title={
                        controlPlane.loading
                            ? 'Refreshing control plane...'
                            : 'Refresh control plane'
                    }
                    onClick={controlPlane.onRefresh}
                    disabled={controlPlane.loading || !datasourceId}
                />
                <details className="health-management">
                    <summary>Manage connection</summary>
                    <div className="health-management-menu">
                        <p>Applies to this connection</p>
                        <button
                            type="button"
                            onClick={
                                controlPlane.response?.paused
                                    ? controlPlane.onResume
                                    : controlPlane.onPause
                            }
                            disabled={!controlPlane.response || controlPlane.loading}
                        >
                            {controlPlane.response?.paused
                                ? 'Resume connection'
                                : 'Pause connection'}
                        </button>
                        <button
                            type="button"
                            onClick={controlPlane.onCancelAll}
                            disabled={!controlPlane.response || controlPlane.loading}
                        >
                            Cancel queued/running
                        </button>
                        <button
                            type="button"
                            onClick={controlPlane.onKillAll}
                            disabled={!controlPlane.response || controlPlane.loading}
                        >
                            Kill queued/running
                        </button>
                    </div>
                </details>
                <LabeledActionButton
                    label="Export queries"
                    icon="download"
                    title="Download running/queued queries as CSV"
                    onClick={controlPlane.onExportCsv}
                    disabled={!controlPlane.response || controlPlane.loading}
                />
            </div>

            {controlPlane.error ? (
                <InlineNotice tone="error">{controlPlane.error}</InlineNotice>
            ) : null}

            {controlPlane.response ? (
                <>
                    <div className="result-stats-grid">
                        <div className="result-stat">
                            <span>Paused</span>
                            <strong>{controlPlane.response.paused ? 'Yes' : 'No'}</strong>
                        </div>
                        <div className="result-stat">
                            <span>Queued</span>
                            <strong>{controlPlane.response.queuedCount.toLocaleString()}</strong>
                        </div>
                        <div className="result-stat">
                            <span>Running</span>
                            <strong>{controlPlane.response.runningCount.toLocaleString()}</strong>
                        </div>
                        <div className="result-stat">
                            <span>Pools</span>
                            <strong>{controlPlane.response.pools.length.toLocaleString()}</strong>
                        </div>
                        <div className="result-stat">
                            <span>Updated</span>
                            <strong title={controlPlane.response.fetchedAt}>
                                {new Date(controlPlane.response.fetchedAt).toLocaleString()}
                            </strong>
                        </div>
                    </div>

                    <div className="panel-inner health-latency" hidden={view !== 'activity'}>
                        <h4>
                            Query latency{' '}
                            <span className="health-scope">in the selected time window</span>
                        </h4>
                        {controlPlane.response.latency.sampleSize === 0 ? (
                            <p className="health-empty">
                                No completed queries in this time window.
                            </p>
                        ) : (
                            <dl className="health-latency-metrics">
                                {(
                                    [
                                        [
                                            'Samples',
                                            controlPlane.response.latency.sampleSize.toLocaleString()
                                        ],
                                        [
                                            'Average',
                                            controlPlane.response.latency.averageMs != null
                                                ? formatDuration(
                                                      controlPlane.response.latency.averageMs
                                                  )
                                                : null
                                        ],
                                        [
                                            'P50',
                                            controlPlane.response.latency.p50Ms != null
                                                ? formatDuration(
                                                      controlPlane.response.latency.p50Ms
                                                  )
                                                : null
                                        ],
                                        [
                                            'P90',
                                            controlPlane.response.latency.p90Ms != null
                                                ? formatDuration(
                                                      controlPlane.response.latency.p90Ms
                                                  )
                                                : null
                                        ],
                                        [
                                            'Maximum',
                                            controlPlane.response.latency.maxMs != null
                                                ? formatDuration(
                                                      controlPlane.response.latency.maxMs
                                                  )
                                                : null
                                        ]
                                    ] as const
                                )
                                    .filter(([, value]) => value !== null)
                                    .map(([label, value]) => (
                                        <div key={label}>
                                            <dt>{label}</dt>
                                            <dd>{value}</dd>
                                        </div>
                                    ))}
                            </dl>
                        )}

                        {controlPlane.response.latency.latestErrors.length > 0 ? (
                            <details className="health-errors">
                                <summary>
                                    Recent errors (
                                    {controlPlane.response.latency.latestErrors.length})
                                </summary>
                                <div className="history-table-wrap">
                                    <table className="result-table history-table control-plane-errors-table">
                                        <thead>
                                            <tr>
                                                <th>Message</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {controlPlane.response.latency.latestErrors.map(
                                                (message, index) => (
                                                    <tr key={`err-${index}-${message}`}>
                                                        <td className="audit-details">{message}</td>
                                                    </tr>
                                                )
                                            )}
                                        </tbody>
                                    </table>
                                </div>
                            </details>
                        ) : null}
                    </div>

                    <div className="panel-inner health-pools" hidden={view !== 'pools'}>
                        <h4>Connection pools</h4>
                        <div className="history-table-wrap">
                            <table className="result-table history-table">
                                <thead>
                                    <tr>
                                        <th>Credential Profile</th>
                                        <th>Active</th>
                                        <th>Idle</th>
                                        <th>Total</th>
                                        <th>Max</th>
                                        <th>Awaiting</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {controlPlane.response.pools.length === 0 ? (
                                        <tr>
                                            <td colSpan={6} className="muted-id">
                                                No pools reported.
                                            </td>
                                        </tr>
                                    ) : (
                                        controlPlane.response.pools.map((pool) => (
                                            <tr
                                                key={`pool-${pool.datasourceId}-${pool.credentialProfile}`}
                                            >
                                                <td>{pool.credentialProfile}</td>
                                                <td>{pool.activeConnections.toLocaleString()}</td>
                                                <td>{pool.idleConnections.toLocaleString()}</td>
                                                <td>{pool.totalConnections.toLocaleString()}</td>
                                                <td>{pool.maximumPoolSize.toLocaleString()}</td>
                                                <td>
                                                    {pool.threadsAwaitingConnection.toLocaleString()}
                                                </td>
                                            </tr>
                                        ))
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <div className="panel-inner health-active-queries" hidden={view !== 'activity'}>
                        <h4>
                            Active queries{' '}
                            <span className="health-scope">
                                {displayedActiveQueries.length} shown
                            </span>
                        </h4>
                        <div className="history-table-wrap">
                            <table className="result-table history-table">
                                <thead>
                                    <tr>
                                        <th>Status</th>
                                        <th>Actor</th>
                                        <th>Duration</th>
                                        <th>Submitted</th>
                                        <th>SQL</th>
                                        <th>Actions</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {displayedActiveQueries.length === 0 ? (
                                        <tr>
                                            <td colSpan={6} className="muted-id">
                                                {normalizedControlPlaneActorFilter
                                                    ? 'No queued or running queries for this actor.'
                                                    : 'No queued or running queries.'}
                                            </td>
                                        </tr>
                                    ) : (
                                        displayedActiveQueries.map((query) => (
                                            <tr key={`active-${query.executionId}`}>
                                                <td>
                                                    <span
                                                        className={toStatusToneClass(query.status)}
                                                    >
                                                        {query.status}
                                                    </span>
                                                </td>
                                                <td>{query.actor}</td>
                                                <td>{formatDuration(query.durationMs)}</td>
                                                <td title={query.submittedAt}>
                                                    {new Date(query.submittedAt).toLocaleString()}
                                                </td>
                                                <td title={query.sqlPreview}>
                                                    <code className="inline-code">
                                                        {query.sqlPreview}
                                                    </code>
                                                </td>
                                                <td>
                                                    <div className="row toolbar-actions">
                                                        <button
                                                            type="button"
                                                            onClick={() =>
                                                                controlPlane.onCancelExecution(
                                                                    query.executionId
                                                                )
                                                            }
                                                            disabled={controlPlane.loading}
                                                        >
                                                            Cancel
                                                        </button>
                                                        <button
                                                            type="button"
                                                            onClick={() =>
                                                                controlPlane.onKillExecution(
                                                                    query.executionId
                                                                )
                                                            }
                                                            disabled={controlPlane.loading}
                                                        >
                                                            Kill
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                        ))
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </>
            ) : (
                <p className="muted-id">
                    {controlPlane.loading
                        ? 'Loading query activity...'
                        : controlPlane.error
                          ? 'Query activity is unavailable. Refresh to retry.'
                          : 'No query activity data available.'}
                </p>
            )}
        </div>
    );
}
