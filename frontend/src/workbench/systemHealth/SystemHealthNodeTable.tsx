import type { SystemHealthNode } from '../types';
import { toStatusToneClass } from '../utils';

type SystemHealthNodeTableProps = {
    nodes: SystemHealthNode[];
    emptyMessage?: string;
};

export default function SystemHealthNodeTable({
    nodes,
    emptyMessage = 'No nodes returned for this health check.'
}: SystemHealthNodeTableProps) {
    return (
        <div className="history-table-wrap">
            <table className="result-table history-table">
                <thead>
                    <tr>
                        <th>Name</th>
                        <th>Role</th>
                        <th>Status</th>
                        <th>Details</th>
                    </tr>
                </thead>
                <tbody>
                    {nodes.length === 0 ? (
                        <tr>
                            <td colSpan={4}>{emptyMessage}</td>
                        </tr>
                    ) : (
                        nodes.map((node, index) => (
                            <tr key={`health-node-${node.name}-${node.role ?? 'unknown'}-${index}`}>
                                <td>{node.name}</td>
                                <td>{node.role ?? '-'}</td>
                                <td>
                                    <span className={toStatusToneClass(node.status)}>
                                        {node.status}
                                    </span>
                                </td>
                                <td className="history-query audit-details">
                                    {Object.keys(node.details ?? {}).length > 0 ? (
                                        <details className="health-node-details">
                                            <summary>
                                                View details ({Object.keys(node.details).length})
                                            </summary>
                                            <dl className="inspection-detail-fields">
                                                {Object.entries(node.details).map(
                                                    ([key, value]) => (
                                                        <div key={key}>
                                                            <dt>{key}</dt>
                                                            <dd>
                                                                {typeof value === 'object'
                                                                    ? JSON.stringify(value, null, 2)
                                                                    : String(value)}
                                                            </dd>
                                                        </div>
                                                    )
                                                )}
                                            </dl>
                                        </details>
                                    ) : (
                                        '-'
                                    )}
                                </td>
                            </tr>
                        ))
                    )}
                </tbody>
            </table>
        </div>
    );
}
