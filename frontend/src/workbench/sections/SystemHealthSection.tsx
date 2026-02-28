import type { CatalogDatasourceResponse, SystemHealthResponse } from '../types';
import { IconButton } from '../components/WorkbenchIcons';
import MariaDbSystemHealthView from '../systemHealth/MariaDbSystemHealthView';
import PostgresSystemHealthView from '../systemHealth/PostgresSystemHealthView';
import StarRocksSystemHealthView from '../systemHealth/StarRocksSystemHealthView';

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
};

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
    onRefresh
}: SystemHealthSectionProps) {
    const selectedDatasource = visibleDatasources.find(
        (datasource) => datasource.id === datasourceId
    );
    const availableProfiles = selectedDatasource?.credentialProfiles ?? [];

    return (
        <section className="panel system-health-panel" hidden={hidden}>
            <div className="history-filters">
                <div className="filter-field">
                    <label htmlFor="system-health-datasource">Connection</label>
                    <div className="select-wrap">
                        <select
                            id="system-health-datasource"
                            value={datasourceId}
                            onChange={(event) => onDatasourceChange(event.target.value)}
                        >
                            <option value="">Select a connection</option>
                            {visibleDatasources.map((datasource) => (
                                <option key={`health-${datasource.id}`} value={datasource.id}>
                                    {datasource.name}
                                </option>
                            ))}
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
                                <option value="">Select a connection first</option>
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
                    ) : response.engine === 'MARIADB' ? (
                        <MariaDbSystemHealthView response={response} />
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
