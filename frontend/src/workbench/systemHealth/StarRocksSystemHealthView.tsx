import type { SystemHealthNode, SystemHealthResponse } from '../types';
import SystemHealthNodeTable from './SystemHealthNodeTable';

type StarRocksSystemHealthViewProps = {
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

const filterNodesByRole = (nodes: SystemHealthNode[], role: string): SystemHealthNode[] =>
    nodes.filter((node) => (node.role ?? '').toLowerCase() === role.toLowerCase());

export default function StarRocksSystemHealthView({ response }: StarRocksSystemHealthViewProps) {
    const frontends = filterNodesByRole(response.nodes, 'frontend');
    const backends = filterNodesByRole(response.nodes, 'backend');
    const frontendCount = asNumber(response.details?.frontends);
    const backendCount = asNumber(response.details?.backends);
    const frontendsAlive = asNumber(response.details?.frontendsAlive);
    const backendsAlive = asNumber(response.details?.backendsAlive);

    return (
        <div className="panel-inner">
            <h3>StarRocks Cluster</h3>
            <div className="result-stats-grid">
                {typeof frontendCount === 'number' ? (
                    <div className="result-stat">
                        <span>Frontends</span>
                        <strong>{frontendCount.toLocaleString()}</strong>
                    </div>
                ) : null}
                {typeof frontendsAlive === 'number' ? (
                    <div className="result-stat">
                        <span>Frontends Alive</span>
                        <strong>{frontendsAlive.toLocaleString()}</strong>
                    </div>
                ) : null}
                {typeof backendCount === 'number' ? (
                    <div className="result-stat">
                        <span>Backends</span>
                        <strong>{backendCount.toLocaleString()}</strong>
                    </div>
                ) : null}
                {typeof backendsAlive === 'number' ? (
                    <div className="result-stat">
                        <span>Backends Alive</span>
                        <strong>{backendsAlive.toLocaleString()}</strong>
                    </div>
                ) : null}
            </div>
            <h4>Frontends</h4>
            <SystemHealthNodeTable nodes={frontends} emptyMessage="No frontends returned." />
            <h4>Backends</h4>
            <SystemHealthNodeTable nodes={backends} emptyMessage="No backends returned." />
        </div>
    );
}
