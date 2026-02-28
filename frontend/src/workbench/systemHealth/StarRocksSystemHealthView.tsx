import type { SystemHealthNode, SystemHealthResponse } from '../types';
import SystemHealthNodeTable from './SystemHealthNodeTable';

type StarRocksSystemHealthViewProps = {
    response: SystemHealthResponse;
};

const filterNodesByRole = (nodes: SystemHealthNode[], role: string): SystemHealthNode[] =>
    nodes.filter((node) => (node.role ?? '').toLowerCase() === role.toLowerCase());

export default function StarRocksSystemHealthView({ response }: StarRocksSystemHealthViewProps) {
    const frontends = filterNodesByRole(response.nodes, 'frontend');
    const backends = filterNodesByRole(response.nodes, 'backend');

    return (
        <div className="panel-inner">
            <h3>StarRocks Cluster</h3>
            <h4>Frontends</h4>
            <SystemHealthNodeTable nodes={frontends} emptyMessage="No frontends returned." />
            <h4>Backends</h4>
            <SystemHealthNodeTable nodes={backends} emptyMessage="No backends returned." />
        </div>
    );
}
