import type { SystemHealthResponse } from '../types';
import SystemHealthNodeTable from './SystemHealthNodeTable';

type TrinoSystemHealthViewProps = {
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

export default function TrinoSystemHealthView({ response }: TrinoSystemHealthViewProps) {
    const serverVersion = response.details?.serverVersion as string | undefined;
    const coordinators = asNumber(response.details?.coordinators);
    const nodes = asNumber(response.details?.nodes);

    return (
        <div className="panel-inner">
            <h3>Trino Cluster</h3>

            <div className="result-stats-grid">
                {serverVersion ? (
                    <div className="result-stat">
                        <span>Version</span>
                        <strong>{serverVersion}</strong>
                    </div>
                ) : null}
                {typeof nodes === 'number' ? (
                    <div className="result-stat">
                        <span>Nodes</span>
                        <strong>{nodes.toLocaleString()}</strong>
                    </div>
                ) : null}
                {typeof coordinators === 'number' ? (
                    <div className="result-stat">
                        <span>Coordinators</span>
                        <strong>{coordinators.toLocaleString()}</strong>
                    </div>
                ) : null}
            </div>

            <SystemHealthNodeTable nodes={response.nodes} />
        </div>
    );
}
