import type { SystemHealthResponse } from '../types';
import SystemHealthNodeTable from './SystemHealthNodeTable';

type VerticaSystemHealthViewProps = {
    response: SystemHealthResponse;
};

const detailText = (value: unknown): string | null =>
    typeof value === 'string' && value.trim() ? value : null;

export default function VerticaSystemHealthView({ response }: VerticaSystemHealthViewProps) {
    const databaseName = detailText(response.details?.databaseName);
    const serverVersion = detailText(response.details?.serverVersion);
    const loadBalancePolicy = detailText(response.details?.loadBalancePolicy);
    const databaseStartTime = detailText(response.details?.databaseStartTime);

    return (
        <div className="panel-inner">
            <h3>Vertica Cluster</h3>

            <div className="result-stats-grid">
                {databaseName ? (
                    <div className="result-stat">
                        <span>Database</span>
                        <strong>{databaseName}</strong>
                    </div>
                ) : null}
                {serverVersion ? (
                    <div className="result-stat">
                        <span>Version</span>
                        <strong>{serverVersion}</strong>
                    </div>
                ) : null}
                {loadBalancePolicy ? (
                    <div className="result-stat">
                        <span>Load Balance</span>
                        <strong>{loadBalancePolicy}</strong>
                    </div>
                ) : null}
                {databaseStartTime ? (
                    <div className="result-stat">
                        <span>Database Started</span>
                        <strong>{databaseStartTime}</strong>
                    </div>
                ) : null}
            </div>

            <SystemHealthNodeTable nodes={response.nodes} />
        </div>
    );
}
