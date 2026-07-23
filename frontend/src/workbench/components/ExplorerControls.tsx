import type { Ref } from 'react';
import type { CatalogDatasourceResponse } from '../types';
import { ExplorerIcon, IconGlyph, RailIcon } from './WorkbenchIcons';
import CredentialProfilePolicyControl from './CredentialProfilePolicyControl';

type ExplorerControlsProps = {
    activeDatasourceId: string;
    activeSchema: string;
    requestedCredentialProfile: string;
    queryJustification: string;
    canOverrideCredentialProfile: boolean;
    disabled: boolean;
    visibleDatasources: CatalogDatasourceResponse[];
    activeDatasource?: CatalogDatasourceResponse;
    availableSchemaNames: string[];
    datasourceHealthTone: string;
    datasourceHealthLabel: string;
    datasourceIcon: string;
    searchQuery: string;
    justificationInputRef: Ref<HTMLInputElement>;
    onDatasourceChange: (datasourceId: string) => void;
    onSchemaChange: (schema: string) => void;
    onProfileChange: (credentialProfile: string) => void;
    onJustificationChange: (justification: string) => void;
    onSearchQueryChange: (query: string) => void;
};

export default function ExplorerControls({
    activeDatasourceId,
    activeSchema,
    requestedCredentialProfile,
    queryJustification,
    canOverrideCredentialProfile,
    disabled,
    visibleDatasources,
    activeDatasource,
    availableSchemaNames,
    datasourceHealthTone,
    datasourceHealthLabel,
    datasourceIcon,
    searchQuery,
    justificationInputRef,
    onDatasourceChange,
    onSchemaChange,
    onProfileChange,
    onJustificationChange,
    onSearchQueryChange
}: ExplorerControlsProps) {
    return (
        <div className="explorer-toolbar-fields">
            <div className="explorer-control-group">
                <div className="explorer-toolbar-label-row">
                    <span className="tile-heading-icon" aria-hidden>
                        <RailIcon glyph="connections" />
                    </span>
                    <label htmlFor="tab-datasource" className="explorer-toolbar-label-text">
                        Connection
                    </label>
                </div>
                <div className="explorer-toolbar-control-row">
                    <div className="editor-connection-picker">
                        <span
                            className={`editor-connection-health tone-${datasourceHealthTone}`}
                            title={`Connection status: ${datasourceHealthLabel}`}
                            aria-label={`Connection status ${datasourceHealthLabel}`}
                        />
                        <span className="editor-connection-icon" aria-hidden>
                            <img src={datasourceIcon} alt="" width={16} height={16} />
                        </span>
                        <div className="select-wrap">
                            <select
                                id="tab-datasource"
                                aria-label="Active tab connection"
                                value={activeDatasourceId}
                                onChange={(event) => onDatasourceChange(event.target.value)}
                                disabled={disabled}
                            >
                                {visibleDatasources.map((datasource) => (
                                    <option key={`tab-ds-${datasource.id}`} value={datasource.id}>
                                        {datasource.name}
                                    </option>
                                ))}
                            </select>
                        </div>
                    </div>
                </div>
            </div>

            <div className="explorer-control-group">
                <div className="explorer-toolbar-label-row">
                    <span className="tile-heading-icon" aria-hidden>
                        <ExplorerIcon glyph="schema" />
                    </span>
                    <label htmlFor="tab-schema" className="explorer-toolbar-label-text">
                        Default Schema
                    </label>
                </div>
                <div className="explorer-toolbar-control-row">
                    <div className="select-wrap">
                        <select
                            id="tab-schema"
                            aria-label="Default schema"
                            value={activeSchema}
                            onChange={(event) => onSchemaChange(event.target.value)}
                            disabled={!activeDatasourceId}
                        >
                            <option value="">None</option>
                            {availableSchemaNames.map((schemaName) => (
                                <option key={`schema-option-${schemaName}`} value={schemaName}>
                                    {schemaName}
                                </option>
                            ))}
                            {activeSchema && !availableSchemaNames.includes(activeSchema) ? (
                                <option value={activeSchema}>{activeSchema}</option>
                            ) : null}
                        </select>
                    </div>
                </div>
            </div>

            <div className="explorer-control-group">
                <div className="explorer-toolbar-label-row">
                    <span className="tile-heading-icon" aria-hidden>
                        <IconGlyph icon="search" />
                    </span>
                    <label htmlFor="explorer-search" className="explorer-toolbar-label-text">
                        Search
                    </label>
                </div>
                <div className="explorer-toolbar-control-row">
                    <div className="explorer-search-wrap">
                        <input
                            id="explorer-search"
                            aria-label="Search explorer objects"
                            placeholder="Schemas, tables, columns"
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

            <CredentialProfilePolicyControl
                ref={justificationInputRef}
                datasource={activeDatasource}
                requestedCredentialProfile={requestedCredentialProfile}
                queryJustification={queryJustification}
                canOverride={canOverrideCredentialProfile}
                disabled={disabled}
                onProfileChange={onProfileChange}
                onJustificationChange={onJustificationChange}
            />
        </div>
    );
}
