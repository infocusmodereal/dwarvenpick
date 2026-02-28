import type { SystemHealthResponse } from '../types';
import SystemHealthNodeTable from './SystemHealthNodeTable';

type PostgresSystemHealthViewProps = {
    response: SystemHealthResponse;
};

export default function PostgresSystemHealthView({ response }: PostgresSystemHealthViewProps) {
    return (
        <div className="panel-inner">
            <h3>PostgreSQL Cluster</h3>
            <SystemHealthNodeTable nodes={response.nodes} />
        </div>
    );
}
