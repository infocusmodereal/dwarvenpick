import type { SystemHealthResponse } from '../types';
import SystemHealthNodeTable from './SystemHealthNodeTable';

type MariaDbSystemHealthViewProps = {
    response: SystemHealthResponse;
};

export default function MariaDbSystemHealthView({ response }: MariaDbSystemHealthViewProps) {
    return (
        <div className="panel-inner">
            <h3>MariaDB Cluster</h3>
            <SystemHealthNodeTable nodes={response.nodes} />
        </div>
    );
}
