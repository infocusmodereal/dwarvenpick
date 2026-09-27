import SystemHealthActivity, {
    type ControlPlanePanelProps
} from '../systemHealth/SystemHealthActivity';
import { useState } from 'react';
import { toStatusToneClass } from '../utils';
import type { CatalogDatasourceResponse, SystemHealthResponse } from '../types';
import { LabeledActionButton } from '../components/WorkbenchIcons';
import InlineNotice from '../components/InlineNotice';
import SystemHealthEngineView from '../systemHealth/SystemHealthEngineView';

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

export default function SystemHealthSection({
    hidden,
    visibleDatasources,
    datasourceId,
    onDatasourceChange,
    credentialProfile,
    onCredentialProfileChange,
    loading,
    error,
    response: fetchedResponse,
    onRefresh,
    controlPlane: fetchedControlPlane
}: SystemHealthSectionProps) {
    const response =
        fetchedResponse?.datasourceId === datasourceId &&
        fetchedResponse.credentialProfile === credentialProfile
            ? fetchedResponse
            : null;
    const controlPlane = {
        ...fetchedControlPlane,
        response:
            fetchedControlPlane.response?.datasourceId === datasourceId
                ? fetchedControlPlane.response
                : null
    };
    const [view, setView] = useState<'engine' | 'activity' | 'pools'>('engine');
    const datasourcesWithSysadminProfiles = visibleDatasources.filter(
        (datasource) => (datasource.sysadminCredentialProfiles ?? []).length > 0
    );
    const hasSysadminConnections = datasourcesWithSysadminProfiles.length > 0;
    const selectedDatasource = visibleDatasources.find(
        (datasource) => datasource.id === datasourceId
    );
    const availableProfiles = selectedDatasource?.sysadminCredentialProfiles ?? [];

    return (
        <section className="panel system-health-panel" hidden={hidden} aria-label="System health">
            <header className="inspection-heading">
                <div>
                    <h2>System Health</h2>
                    <p>Engine health and query operations</p>
                </div>
                <LabeledActionButton
                    icon="refresh"
                    label="Refresh"
                    title="Refresh engine health and query activity"
                    disabled={
                        loading || controlPlane.loading || !datasourceId || !credentialProfile
                    }
                    onClick={() => {
                        onRefresh();
                        controlPlane.onRefresh();
                    }}
                />
            </header>
            {!hasSysadminConnections ? (
                <InlineNotice tone="warning">
                    No connections have a sysadmin credential profile. Mark a credential profile as
                    sysadmin in the Connections admin page to enable health checks.
                </InlineNotice>
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

            <div className="health-view-switcher" role="group" aria-label="Health views">
                {(
                    [
                        ['engine', 'Engine health'],
                        ['activity', 'Query activity'],
                        ['pools', 'Connection pools']
                    ] as const
                ).map(([value, label]) => (
                    <button
                        key={value}
                        type="button"
                        aria-pressed={view === value}
                        onClick={() => setView(value)}
                    >
                        {label}
                    </button>
                ))}
            </div>

            {error ? <InlineNotice tone="error">{error}</InlineNotice> : null}

            {!datasourceId ? (
                <p className="muted-id">Select a connection to view system health.</p>
            ) : null}

            {datasourceId && loading && !response && !error ? (
                <p className="muted-id">Loading system health...</p>
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
                            <strong className={toStatusToneClass(response.status)}>
                                {response.status}
                            </strong>
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
                        <InlineNotice tone="warning">
                            {response.message ?? 'Insufficient privileges for health checks.'}
                        </InlineNotice>
                    ) : response.status === 'ERROR' ? (
                        <InlineNotice tone="error">
                            {response.message ?? 'System health check failed.'}
                        </InlineNotice>
                    ) : response.status === 'UNSUPPORTED' ? (
                        <p className="muted-id">
                            {response.message ?? 'Health checks unsupported.'}
                        </p>
                    ) : null}

                    {view === 'engine' && (
                        <div className="health-engine-details">
                            <SystemHealthEngineView response={response} />
                        </div>
                    )}
                </>
            ) : null}

            {datasourceId && (
                <SystemHealthActivity
                    datasourceId={datasourceId}
                    view={view}
                    controlPlane={controlPlane}
                />
            )}
        </section>
    );
}
