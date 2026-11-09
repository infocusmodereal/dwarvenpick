import SearchablePicker from './SearchablePicker';
import type { CatalogDatasourceResponse } from '../types';
import { resolveDatasourceIcon } from '../icons';
import { IconGlyph } from './WorkbenchIcons';

const engineNames: Record<string, string> = {
    POSTGRESQL: 'PostgreSQL',
    MYSQL: 'MySQL',
    MARIADB: 'MariaDB',
    STARROCKS: 'StarRocks',
    TRINO: 'Trino',
    VERTICA: 'Vertica',
    AEROSPIKE: 'Aerospike'
};

type ExplorerControlsProps = {
    activeDatasource?: CatalogDatasourceResponse;
    disabled: boolean;
    visibleDatasources: CatalogDatasourceResponse[];
    datasourceHealthTone: string;
    datasourceHealthLabel: string;
    searchQuery: string;
    onDatasourceChange: (id: string) => void;
    onSearchQueryChange: (query: string) => void;
};

export default function ExplorerControls({
    activeDatasource,
    disabled,
    visibleDatasources,
    datasourceHealthTone,
    datasourceHealthLabel,
    searchQuery,
    onDatasourceChange,
    onSearchQueryChange
}: ExplorerControlsProps) {
    return (
        <div className="explorer-toolbar-fields">
            <SearchablePicker
                noun="connection"
                triggerLabel="Active tab connection"
                value={activeDatasource?.id ?? ''}
                disabled={disabled}
                items={visibleDatasources.map((d) => ({
                    value: d.id,
                    label: d.name,
                    iconLabel: engineNames[d.engine] ?? d.engine,
                    icon: resolveDatasourceIcon(d.engine)
                }))}
                onChange={onDatasourceChange}
            >
                <img
                    src={resolveDatasourceIcon(activeDatasource?.engine)}
                    alt={
                        activeDatasource
                            ? (engineNames[activeDatasource.engine] ?? activeDatasource.engine)
                            : ''
                    }
                    title={
                        activeDatasource
                            ? (engineNames[activeDatasource.engine] ?? activeDatasource.engine)
                            : undefined
                    }
                />
                <span className="connection-identity">
                    <strong>{activeDatasource?.name ?? 'Select connection'}</strong>
                    <small>
                        <i className={'editor-connection-health tone-' + datasourceHealthTone} />
                        {datasourceHealthLabel}
                    </small>
                </span>
            </SearchablePicker>
            <div className="explorer-control-group">
                <div className="explorer-toolbar-control-row">
                    <div className="explorer-search-wrap">
                        <span className="explorer-search-icon" aria-hidden>
                            <IconGlyph icon="search" />
                        </span>
                        <input
                            id="explorer-search"
                            aria-label="Search explorer objects"
                            placeholder="Search objects"
                            value={searchQuery}
                            onChange={(event) => onSearchQueryChange(event.target.value)}
                        />
                        {searchQuery.trim() ? (
                            <button
                                type="button"
                                className="explorer-search-clear"
                                aria-label="Clear explorer search"
                                title="Clear search"
                                onClick={() => onSearchQueryChange('')}
                            >
                                <IconGlyph icon="close" />
                            </button>
                        ) : null}
                    </div>
                </div>
            </div>
        </div>
    );
}
