import type { SystemHealthResponse } from '../types';
import SystemHealthNodeTable from './SystemHealthNodeTable';

type AerospikeSystemHealthViewProps = {
    response: SystemHealthResponse;
};

export default function AerospikeSystemHealthView({ response }: AerospikeSystemHealthViewProps) {
    const serverVersion = response.details?.serverVersion as string | undefined;
    const clusterName = response.details?.clusterName as string | undefined;
    const namespace = response.details?.namespace as string | undefined;

    return (
        <div className="panel-inner">
            <h3>Aerospike Cluster</h3>

            <div className="result-stats-grid">
                {clusterName ? (
                    <div className="result-stat">
                        <span>Cluster</span>
                        <strong>{clusterName}</strong>
                    </div>
                ) : null}
                {namespace ? (
                    <div className="result-stat">
                        <span>Namespace</span>
                        <strong>{namespace}</strong>
                    </div>
                ) : null}
                {serverVersion ? (
                    <div className="result-stat">
                        <span>Version</span>
                        <strong>{serverVersion}</strong>
                    </div>
                ) : null}
            </div>

            <SystemHealthNodeTable nodes={response.nodes} />
        </div>
    );
}

