import type {
    CatalogDatasourceResponse,
    ControlPlaneActiveQuery,
    ControlPlaneDatasourceStatusResponse,
    SystemHealthResponse
} from '../types';
import { IconButton } from '../components/WorkbenchIcons';
import AerospikeSystemHealthView from '../systemHealth/AerospikeSystemHealthView';
import MariaDbSystemHealthView from '../systemHealth/MariaDbSystemHealthView';
import PostgresSystemHealthView from '../systemHealth/PostgresSystemHealthView';
import StarRocksSystemHealthView from '../systemHealth/StarRocksSystemHealthView';
import TrinoSystemHealthView from '../systemHealth/TrinoSystemHealthView';

type ControlPlanePanelProps = {
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

type SystemHealthSectionProps = {
    hidden: boolean;
    visibleDatasources: CatalogDatasourceResponse[];
    datasourceId: string;
    onDatasourceChange: (value: string) => void;
    credentialProfile: string;
    onCredentialProfileChange: (value: string) => void;
    loading: boolean;
    error: string;
    response: SystemHealthResponse | null;
    onRefresh: () => void;
    controlPlane: ControlPlanePanelProps;
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

export default function SystemHealthSection({
    hidden,
    visibleDatasources,
    datasourceId,
    onDatasourceChange,
    credentialProfile,
    onCredentialProfileChange,
    loading,
    error,
    response,
    onRefresh,
    controlPlane
}: SystemHealthSectionProps) {
    const datasourcesWithSysadminProfiles = visibleDatasources.filter(
        (datasource) => (datasource.sysadminCredentialProfiles ?? []).length > 0
    );
    const hasSysadminConnections = datasourcesWithSysadminProfiles.length > 0;
    const selectedDatasource = visibleDatasources.find(
        (datasource) => datasource.id === datasourceId
    );
    const availableProfiles = selectedDatasource?.sysadminCredentialProfiles ?? [];
    const controlPlaneActorOptions = buildActorOptions(controlPlane.response?.activeQueries ?? []);
    const normalizedControlPlaneActorFilter = controlPlane.actorFilter.trim();
    const displayedActiveQueries = (controlPlane.response?.activeQueries ?? []).filter((query) =>
        normalizedControlPlaneActorFilter ? query.actor === normalizedControlPlaneActorFilter : true
    );

    return (
        <section className="panel system-health-panel" hidden={hidden}>
            {!hasSysadminConnections ? (
                <p className="form-error" role="alert">
                    No connections have a sysadmin credential profile. Mark a credential profile as
                    sysadmin in the Connections admin page to enable health checks.
                </p>
            ) : null}

            <div className="history-filters">
                <div className="filter-field">
                    <label htmlFor="system-health-datasource">Connection</label>
                    <div className="select-wrap">
                        <select
                            id="system-health-datasource"
                            value={datasourceId}
                            onChange={(event) => onDatasourceChange(event.target.value)}
                            disabled={!hasSysadminConnections}
                        >
                            {hasSysadminConnections ? (
                                <>
                                    <option value="">Select a connection</option>
                                    {datasourcesWithSysadminProfiles.map((datasource) => (
                                        <option
                                            key={`health-${datasource.id}`}
                                            value={datasource.id}
                                        >
                                            {datasource.name}
                                        </option>
                                    ))}
                                </>
                            ) : (
                                <option value="">No sysadmin connections</option>
                            )}
                        </select>
                    </div>
                </div>

                <div className="filter-field">
                    <label htmlFor="system-health-credential-profile">Credential Profile</label>
                    <div className="select-wrap">
                        <select
                            id="system-health-credential-profile"
                            value={credentialProfile}
                            onChange={(event) => onCredentialProfileChange(event.target.value)}
                            disabled={!datasourceId || availableProfiles.length === 0}
                        >
                            {availableProfiles.length === 0 ? (
                                <option value="">
                                    {datasourceId
                                        ? 'No sysadmin credential profiles'
                                        : 'Select a connection first'}
                                </option>
                            ) : (
                                availableProfiles.map((profile) => (
                                    <option
                                        key={`health-${datasourceId}-${profile}`}
                                        value={profile}
                                    >
                                        {profile}
                                    </option>
                                ))
                            )}
                        </select>
                    </div>
                </div>
            </div>

            <div className="row toolbar-actions">
                <IconButton
                    icon="refresh"
                    title={loading ? 'Refreshing system health...' : 'Refresh system health'}
                    onClick={onRefresh}
                    disabled={loading || !datasourceId || !credentialProfile}
                />
            </div>

            {error ? (
                <p className="form-error" role="alert">
                    {error}
                </p>
            ) : null}

            {!datasourceId ? (
                <p className="muted-id">Select a connection to view system health.</p>
            ) : null}

            {datasourceId ? (
                <div className="panel-inner">
                    <h3>Control Plane</h3>
                    <div className="history-filters">
                        <div className="filter-field">
                            <label htmlFor="control-plane-window">Window</label>
                            <div className="select-wrap">
                                <select
                                    id="control-plane-window"
                                    value={controlPlane.windowSeconds}
                                    onChange={(event) =>
                                        controlPlane.onWindowSecondsChange(
                                            Number(event.target.value)
                                        )
                                    }
                                >
                                    <option value={300}>Last 5 minutes</option>
                                    <option value={900}>Last 15 minutes</option>
                                    <option value={3600}>Last hour</option>
                                    <option value={21600}>Last 6 hours</option>
                                </select>
                            </div>
                        </div>

                        <div className="filter-field">
                            <label htmlFor="control-plane-actor-filter">Actor</label>
                            <input
                                id="control-plane-actor-filter"
                                value={controlPlane.actorFilter}
                                onChange={(event) =>
                                    controlPlane.onActorFilterChange(event.target.value)
                                }
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
                                        controlPlane.onAutoRefreshChange(
                                            event.target.value === 'on'
                                        )
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
                        <IconButton
                            icon="download"
                            title="Download running/queued queries as CSV"
                            onClick={controlPlane.onExportCsv}
                            disabled={!controlPlane.response || controlPlane.loading}
                        />
                    </div>

                    {controlPlane.error ? (
                        <p className="form-error" role="alert">
                            {controlPlane.error}
                        </p>
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
                                    <strong>
                                        {controlPlane.response.queuedCount.toLocaleString()}
                                    </strong>
                                </div>
                                <div className="result-stat">
                                    <span>Running</span>
                                    <strong>
                                        {controlPlane.response.runningCount.toLocaleString()}
                                    </strong>
                                </div>
                                <div className="result-stat">
                                    <span>Pools</span>
                                    <strong>
                                        {controlPlane.response.pools.length.toLocaleString()}
                                    </strong>
                                </div>
                                <div className="result-stat">
                                    <span>Updated</span>
                                    <strong title={controlPlane.response.fetchedAt}>
                                        {new Date(controlPlane.response.fetchedAt).toLocaleString()}
                                    </strong>
                                </div>
                            </div>

                            <div className="panel-inner">
                                <h4>Latency</h4>
                                <div className="result-stats-grid">
                                    <div className="result-stat">
                                        <span>Samples</span>
                                        <strong>
                                            {controlPlane.response.latency.sampleSize.toLocaleString()}
                                        </strong>
                                    </div>
                                    <div className="result-stat">
                                        <span>Avg</span>
                                        <strong>
                                            {formatDuration(
                                                controlPlane.response.latency.averageMs ?? null
                                            )}
                                        </strong>
                                    </div>
                                    <div className="result-stat">
                                        <span>P50</span>
                                        <strong>
                                            {formatDuration(
                                                controlPlane.response.latency.p50Ms ?? null
                                            )}
                                        </strong>
                                    </div>
                                    <div className="result-stat">
                                        <span>P90</span>
                                        <strong>
                                            {formatDuration(
                                                controlPlane.response.latency.p90Ms ?? null
                                            )}
                                        </strong>
                                    </div>
                                    <div className="result-stat">
                                        <span>Max</span>
                                        <strong>
                                            {formatDuration(
                                                controlPlane.response.latency.maxMs ?? null
                                            )}
                                        </strong>
                                    </div>
                                </div>

                                {controlPlane.response.latency.latestErrors.length > 0 ? (
                                    <div className="panel-inner">
                                        <h4>Latest errors</h4>
                                        <ul className="bullet-list">
                                            {controlPlane.response.latency.latestErrors.map(
                                                (message) => (
                                                    <li key={`err-${message}`}>{message}</li>
                                                )
                                            )}
                                        </ul>
                                    </div>
                                ) : null}
                            </div>

                            <div className="panel-inner">
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
                                                        <td>
                                                            {pool.activeConnections.toLocaleString()}
                                                        </td>
                                                        <td>
                                                            {pool.idleConnections.toLocaleString()}
                                                        </td>
                                                        <td>
                                                            {pool.totalConnections.toLocaleString()}
                                                        </td>
                                                        <td>
                                                            {pool.maximumPoolSize.toLocaleString()}
                                                        </td>
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

                            <div className="panel-inner">
                                <h4>Queued/running queries</h4>
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
                                                            <span className="status-pill">
                                                                {query.status}
                                                            </span>
                                                        </td>
                                                        <td>{query.actor}</td>
                                                        <td>{formatDuration(query.durationMs)}</td>
                                                        <td title={query.submittedAt}>
                                                            {new Date(
                                                                query.submittedAt
                                                            ).toLocaleString()}
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
                        <p className="muted-id">Loading control plane status...</p>
                    )}
                </div>
            ) : null}

            {response ? (
                <>
                    <div className="result-stats-grid">
                        <div className="result-stat">
                            <span>Engine</span>
                            <strong>{response.engine}</strong>
                        </div>
                        <div className="result-stat">
                            <span>Status</span>
                            <strong>{response.status}</strong>
                        </div>
                        <div className="result-stat">
                            <span>Nodes</span>
                            <strong>{response.nodeCount.toLocaleString()}</strong>
                        </div>
                        <div className="result-stat">
                            <span>Healthy</span>
                            <strong>{response.healthyNodeCount.toLocaleString()}</strong>
                        </div>
                        <div className="result-stat">
                            <span>Checked</span>
                            <strong title={response.checkedAt}>
                                {new Date(response.checkedAt).toLocaleString()}
                            </strong>
                        </div>
                    </div>

                    {response.status === 'INSUFFICIENT_PRIVILEGES' ? (
                        <p className="form-error" role="alert">
                            {response.message ?? 'Insufficient privileges for health checks.'}
                        </p>
                    ) : response.status === 'ERROR' ? (
                        <p className="form-error" role="alert">
                            {response.message ?? 'System health check failed.'}
                        </p>
                    ) : response.status === 'UNSUPPORTED' ? (
                        <p className="muted-id">
                            {response.message ?? 'Health checks unsupported.'}
                        </p>
                    ) : null}

                    {response.engine === 'POSTGRESQL' ? (
                        <PostgresSystemHealthView response={response} />
                    ) : response.engine === 'STARROCKS' ? (
                        <StarRocksSystemHealthView response={response} />
                    ) : response.engine === 'MARIADB' || response.engine === 'MYSQL' ? (
                        <MariaDbSystemHealthView response={response} />
                    ) : response.engine === 'TRINO' ? (
                        <TrinoSystemHealthView response={response} />
                    ) : response.engine === 'AEROSPIKE' ? (
                        <AerospikeSystemHealthView response={response} />
                    ) : (
                        <div className="panel-inner">
                            <h3>System Health</h3>
                            <p className="muted-id">
                                No engine-specific health view is available yet for{' '}
                                {response.engine}.
                            </p>
                        </div>
                    )}
                </>
            ) : null}
        </section>
    );
}
