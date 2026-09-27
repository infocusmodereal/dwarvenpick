import { useState } from 'react';
import type { ManagedDatasourceResponse } from '../types';
import { IconButton, IconGlyph, LabeledActionButton } from '../components/WorkbenchIcons';
import { resolveDatasourceIcon } from '../icons';

type Props = {
    connections: ManagedDatasourceResponse[];
    deleting: boolean;
    reencrypting: boolean;
    onCreate: () => void;
    onEdit: (id: string) => void;
    onDelete: (connection: ManagedDatasourceResponse) => void;
    onReencrypt: () => void;
};
export default function ConnectionsCatalog({
    connections,
    deleting,
    reencrypting,
    onCreate,
    onEdit,
    onDelete,
    onReencrypt
}: Props) {
    const [search, setSearch] = useState('');
    const [engine, setEngine] = useState('');
    const engines = Array.from(new Set(connections.map((connection) => connection.engine))).sort();
    const filtered = connections.filter(
        (connection) =>
            (!engine || connection.engine === engine) &&
            [
                connection.name,
                connection.id,
                connection.host,
                connection.database,
                connection.engine
            ]
                .join(' ')
                .toLowerCase()
                .includes(search.trim().toLowerCase())
    );
    return (
        <section
            className="connection-list-view compact-connections"
            aria-label="Connections catalog"
        >
            <header className="inspection-heading">
                <div>
                    <h2>Connections</h2>
                    <p>Manage engines, endpoints and access profiles</p>
                </div>
                <LabeledActionButton
                    icon="new"
                    label="Create Connection"
                    title="Create connection"
                    onClick={onCreate}
                />
            </header>
            <div className="connections-tools">
                <label className="inspection-search">
                    <IconGlyph icon="search" />
                    <input
                        aria-label="Search connections"
                        placeholder="Search connections"
                        value={search}
                        onChange={(event) => setSearch(event.target.value)}
                    />
                </label>
                <label className="connections-engine-filter">
                    <span>Engine</span>
                    <select
                        aria-label="Filter connections by engine"
                        value={engine}
                        onChange={(event) => setEngine(event.target.value)}
                    >
                        <option value="">All engines</option>
                        {engines.map((value) => (
                            <option key={value} value={value}>
                                {value}
                            </option>
                        ))}
                    </select>
                </label>
                <span className="inspection-count">
                    {filtered.length} of {connections.length} connections
                </span>
                <details className="connections-maintenance">
                    <summary>Maintenance</summary>
                    <button
                        type="button"
                        disabled={reencrypting}
                        title="Re-encrypt stored credentials with the current server key"
                        onClick={onReencrypt}
                    >
                        {reencrypting ? 'Re-encrypting...' : 'Re-encrypt Credentials'}
                    </button>
                </details>
            </div>
            <div className="connections-table-wrap">
                <table className="result-table connections-table">
                    <thead>
                        <tr>
                            <th>Connection</th>
                            <th>Engine</th>
                            <th>Endpoint</th>
                            <th>Database</th>
                            <th>Profiles</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {filtered.length === 0 ? (
                            <tr>
                                <td colSpan={6}>
                                    {connections.length
                                        ? 'No connections match these filters.'
                                        : 'No connections configured yet.'}
                                </td>
                            </tr>
                        ) : (
                            filtered.map((connection) => (
                                <tr key={connection.id}>
                                    <td>
                                        <div className="connection-name-cell">
                                            <img
                                                src={resolveDatasourceIcon(connection.engine)}
                                                alt=""
                                                width={26}
                                                height={26}
                                            />
                                            <div>
                                                <strong>{connection.name}</strong>
                                                {connection.id !== connection.name && (
                                                    <small>{connection.id}</small>
                                                )}
                                            </div>
                                        </div>
                                    </td>
                                    <td>{connection.engine}</td>
                                    <td>
                                        <span className="connection-endpoint">
                                            {connection.host}:{connection.port}
                                        </span>
                                    </td>
                                    <td>{connection.database || '—'}</td>
                                    <td>{connection.credentialProfiles.length}</td>
                                    <td>
                                        <div className="connection-row-actions">
                                            <IconButton
                                                icon="rename"
                                                title={`Edit ${connection.name}`}
                                                onClick={() => onEdit(connection.id)}
                                            />
                                            <IconButton
                                                icon="delete"
                                                title={`Delete ${connection.name}`}
                                                variant="danger"
                                                disabled={deleting}
                                                onClick={() => onDelete(connection)}
                                            />
                                        </div>
                                    </td>
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
        </section>
    );
}
