import type { SystemHealthResponse } from '../types';
import SystemHealthNodeTable from './SystemHealthNodeTable';

type MariaDbSystemHealthViewProps = {
    response: SystemHealthResponse;
};

const asNumber = (value: unknown): number | null => {
    if (typeof value === 'number' && Number.isFinite(value)) {
        return value;
    }
    if (typeof value === 'string') {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : null;
    }
    return null;
};

export default function MariaDbSystemHealthView({ response }: MariaDbSystemHealthViewProps) {
    const title = response.engine === 'MYSQL' ? 'MySQL' : 'MariaDB';
    const serverVersion = response.details?.serverVersion as string | undefined;
    const uptimeSeconds = asNumber(response.details?.uptimeSeconds);
    const wsrepClusterSize = asNumber(response.details?.wsrepClusterSize);
    const wsrepClusterStatus = response.details?.wsrepClusterStatus as string | undefined;
    const wsrepReady = response.details?.wsrepReady as string | undefined;
    const wsrepConnected = response.details?.wsrepConnected as string | undefined;
    const wsrepLocalStateComment = response.details?.wsrepLocalStateComment as string | undefined;
    const readOnly = response.details?.readOnly as boolean | undefined;

    return (
        <div className="panel-inner">
            <h3>{title} Cluster</h3>
            <div className="result-stats-grid">
                {serverVersion ? (
                    <div className="result-stat">
                        <span>Version</span>
                        <strong>{serverVersion}</strong>
                    </div>
                ) : null}
                {typeof uptimeSeconds === 'number' ? (
                    <div className="result-stat">
                        <span>Uptime (s)</span>
                        <strong>{Math.max(0, Math.floor(uptimeSeconds)).toLocaleString()}</strong>
                    </div>
                ) : null}
                {typeof wsrepClusterSize === 'number' ? (
                    <div className="result-stat">
                        <span>Cluster Size</span>
                        <strong>{wsrepClusterSize.toLocaleString()}</strong>
                    </div>
                ) : null}
                {wsrepClusterStatus ? (
                    <div className="result-stat">
                        <span>Cluster Status</span>
                        <strong>{wsrepClusterStatus}</strong>
                    </div>
                ) : null}
                {wsrepReady ? (
                    <div className="result-stat">
                        <span>Galera Ready</span>
                        <strong>{wsrepReady}</strong>
                    </div>
                ) : null}
                {wsrepConnected ? (
                    <div className="result-stat">
                        <span>Galera Connected</span>
                        <strong>{wsrepConnected}</strong>
                    </div>
                ) : null}
                {wsrepLocalStateComment ? (
                    <div className="result-stat">
                        <span>Galera State</span>
                        <strong>{wsrepLocalStateComment}</strong>
                    </div>
                ) : null}
                {typeof readOnly === 'boolean' ? (
                    <div className="result-stat">
                        <span>Read Only</span>
                        <strong>{readOnly ? 'Yes' : 'No'}</strong>
                    </div>
                ) : null}
            </div>
            <SystemHealthNodeTable nodes={response.nodes} />
        </div>
    );
}
