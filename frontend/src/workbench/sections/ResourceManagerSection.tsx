import { useEffect, useMemo, useState } from 'react';
import { IconButton, InfoHint } from '../components/WorkbenchIcons';
import type {
    ResourceFormState,
    ResourceManagerMode,
    ResourceScriptResponse,
    ResourceScope,
    ResourceVersionAction,
    ResourceVersionResponse
} from '../types';

type DatasourceOption = {
    id: string;
    name: string;
};

type FolderTreeNode = {
    name: string;
    path: string;
    count: number;
    children: FolderTreeNode[];
};

type MutableFolderTreeNode = FolderTreeNode & {
    childMap: Map<string, MutableFolderTreeNode>;
};

type ResourceManagerSectionProps = {
    hidden: boolean;
    resourceScopeFilter: 'all' | 'private' | 'shared';
    onResourceScopeFilterChange: (scope: 'all' | 'private' | 'shared') => void;
    resourceSearchInput: string;
    onResourceSearchInputChange: (value: string) => void;
    resourceTagFilter: string;
    onResourceTagFilterChange: (value: string) => void;
    resourceGroupFilter: string;
    onResourceGroupFilterChange: (value: string) => void;
    resourceGroupOptions: string[];
    resourceDatasourceFilter: string;
    onResourceDatasourceFilterChange: (value: string) => void;
    resourceDatasourceOptions: DatasourceOption[];
    loadingResources: boolean;
    onRefresh: () => void;
    resources: ResourceScriptResponse[];
    resourceError: string;
    resourceEditorMode: ResourceManagerMode;
    resourceFormState: ResourceFormState;
    onResourceFormChange: (next: ResourceFormState) => void;
    savingResource: boolean;
    resourceVersions: ResourceVersionResponse[];
    loadingResourceVersions: boolean;
    restoringResourceVersionId: string;
    onRefreshVersions: () => void;
    onRestoreResourceVersion: (versionId: string) => void;
    onStartCreate: () => void;
    onSeedFromActiveTab: () => void;
    canSeedFromActiveTab: boolean;
    activeTabLabel: string;
    onSaveResource: () => void;
    onCancelEdit: () => void;
    onOpenResource: (resource: ResourceScriptResponse, runImmediately: boolean) => void;
    onEditResource: (resource: ResourceScriptResponse) => void;
    onDuplicateResource: (resource: ResourceScriptResponse) => void;
    onDeleteResource: (resourceId: string) => void;
};

const formatScopeLabel = (scope: ResourceScope): string =>
    scope === 'PRIVATE' ? 'Private' : 'Shared';

const formatScopeTone = (scope: ResourceScope): string =>
    scope === 'PRIVATE' ? 'tone-private' : 'tone-shared';

const resourceTitleHelpText =
    'Start with a letter or number. Then use letters, numbers, dots or hyphens.';
const resourceFolderHelpText =
    "Use '/' to separate folders. Each folder name must start with a letter or number and may include dots or hyphens.";
const resourceTagHelpText =
    'Use lowercase letters, numbers, dots or hyphens. Press comma or Enter to turn a tag into a chip.';
const resourceTagPattern = /^[a-z0-9][a-z0-9.-]*$/;

const buildFolderTree = (resources: ResourceScriptResponse[]): FolderTreeNode[] => {
    const root = new Map<string, MutableFolderTreeNode>();

    resources.forEach((resource) => {
        const segments = resource.folderPath
            .split('/')
            .map((segment) => segment.trim())
            .filter((segment) => segment.length > 0);

        let level = root;
        let currentPath = '';
        segments.forEach((segment) => {
            currentPath = currentPath ? `${currentPath}/${segment}` : segment;
            const existing = level.get(segment) ?? {
                name: segment,
                path: currentPath,
                count: 0,
                children: [],
                childMap: new Map<string, MutableFolderTreeNode>()
            };
            existing.count += 1;
            level.set(segment, existing);
            level = existing.childMap;
        });
    });

    const finalizeNodes = (nodes: Iterable<MutableFolderTreeNode>): FolderTreeNode[] =>
        Array.from(nodes)
            .map((node) => ({
                name: node.name,
                path: node.path,
                count: node.count,
                children: finalizeNodes(node.childMap.values())
            }))
            .sort((left, right) => left.name.localeCompare(right.name));

    return finalizeNodes(root.values());
};

const parseTagEditorState = (
    value: string
): {
    committedTags: string[];
    draft: string;
} => {
    const segments = value.split(',');
    const trailingSeparator = /,\s*$/.test(value);
    const committedTags =
        segments.length > 1
            ? segments
                  .slice(0, -1)
                  .map((segment) => segment.trim().toLowerCase())
                  .filter(
                      (segment, index, allSegments) =>
                          segment.length > 0 && allSegments.indexOf(segment) === index
                  )
            : [];
    const draft = trailingSeparator ? '' : (segments.at(-1) ?? '').trimStart().toLowerCase();
    return {
        committedTags,
        draft
    };
};

const composeTagInput = (committedTags: string[], draft: string): string => {
    const normalizedTags = committedTags.filter(
        (tag, index) => tag.length > 0 && committedTags.indexOf(tag) === index
    );
    const normalizedDraft = draft.trimStart().toLowerCase();

    if (!normalizedDraft) {
        return normalizedTags.length > 0 ? `${normalizedTags.join(', ')}, ` : '';
    }

    return normalizedTags.length > 0
        ? `${normalizedTags.join(', ')}, ${normalizedDraft}`
        : normalizedDraft;
};

const formatVersionActionLabel = (action: ResourceVersionAction): string => {
    switch (action) {
        case 'CREATED':
            return 'Created';
        case 'UPDATED_METADATA':
            return 'Metadata update';
        case 'UPDATED_CONTENT':
            return 'Content update';
        case 'RESTORED':
            return 'Restored';
        case 'DUPLICATED':
            return 'Duplicated';
        default:
            return action;
    }
};

export default function ResourceManagerSection({
    hidden,
    resourceScopeFilter,
    onResourceScopeFilterChange,
    resourceSearchInput,
    onResourceSearchInputChange,
    resourceTagFilter,
    onResourceTagFilterChange,
    resourceGroupFilter,
    onResourceGroupFilterChange,
    resourceGroupOptions,
    resourceDatasourceFilter,
    onResourceDatasourceFilterChange,
    resourceDatasourceOptions,
    loadingResources,
    onRefresh,
    resources,
    resourceError,
    resourceEditorMode,
    resourceFormState,
    onResourceFormChange,
    savingResource,
    resourceVersions,
    loadingResourceVersions,
    restoringResourceVersionId,
    onRefreshVersions,
    onRestoreResourceVersion,
    onStartCreate,
    onSeedFromActiveTab,
    canSeedFromActiveTab,
    activeTabLabel,
    onSaveResource,
    onCancelEdit,
    onOpenResource,
    onEditResource,
    onDuplicateResource,
    onDeleteResource
}: ResourceManagerSectionProps) {
    const [selectedFolderPath, setSelectedFolderPath] = useState('');
    const [expandedFolders, setExpandedFolders] = useState<Record<string, boolean>>({});
    const [tagEditorError, setTagEditorError] = useState('');

    const folderTree = useMemo(() => buildFolderTree(resources), [resources]);
    const unfiledResourcesCount = useMemo(
        () => resources.filter((resource) => !resource.folderPath.trim()).length,
        [resources]
    );
    const filteredResources = useMemo(() => {
        if (!selectedFolderPath) {
            return resources;
        }
        if (selectedFolderPath === '__unfiled__') {
            return resources.filter((resource) => !resource.folderPath.trim());
        }
        return resources.filter(
            (resource) =>
                resource.folderPath === selectedFolderPath ||
                resource.folderPath.startsWith(`${selectedFolderPath}/`)
        );
    }, [resources, selectedFolderPath]);
    const { committedTags, draft: tagDraft } = useMemo(
        () => parseTagEditorState(resourceFormState.tagsInput),
        [resourceFormState.tagsInput]
    );

    useEffect(() => {
        if (!selectedFolderPath) {
            return;
        }
        if (selectedFolderPath === '__unfiled__') {
            if (unfiledResourcesCount === 0) {
                setSelectedFolderPath('');
            }
            return;
        }

        const hasVisibleMatch = resources.some(
            (resource) =>
                resource.folderPath === selectedFolderPath ||
                resource.folderPath.startsWith(`${selectedFolderPath}/`)
        );
        if (!hasVisibleMatch) {
            setSelectedFolderPath('');
        }
    }, [resources, selectedFolderPath, unfiledResourcesCount]);

    useEffect(() => {
        setTagEditorError('');
    }, [
        resourceEditorMode,
        resourceFormState.folderPath,
        resourceFormState.scope,
        resourceFormState.tagsInput,
        resourceFormState.title
    ]);

    const updateTagsInput = (nextCommittedTags: string[], nextDraft: string) => {
        onResourceFormChange({
            ...resourceFormState,
            tagsInput: composeTagInput(nextCommittedTags, nextDraft)
        });
    };

    const commitTagDraft = () => {
        const normalizedDraft = tagDraft.trim().toLowerCase();
        if (!normalizedDraft) {
            setTagEditorError('');
            updateTagsInput(committedTags, '');
            return;
        }

        if (!resourceTagPattern.test(normalizedDraft)) {
            setTagEditorError(
                'Tags must start with a letter or number and only use lowercase letters, numbers, dots or hyphens.'
            );
            return;
        }

        setTagEditorError('');
        updateTagsInput([...committedTags, normalizedDraft], '');
    };

    const renderFolderNodes = (nodes: FolderTreeNode[], depth = 0) => {
        if (nodes.length === 0) {
            return null;
        }

        return (
            <ul className="resource-folder-tree-list" role={depth === 0 ? 'tree' : undefined}>
                {nodes.map((node) => {
                    const isExpanded = expandedFolders[node.path] ?? true;
                    const isSelected = selectedFolderPath === node.path;
                    return (
                        <li key={`folder-node-${node.path}`}>
                            <div
                                className="resource-folder-tree-row"
                                style={{ paddingLeft: `${depth * 0.85}rem` }}
                            >
                                {node.children.length > 0 ? (
                                    <button
                                        type="button"
                                        className="resource-folder-tree-toggle"
                                        onClick={() =>
                                            setExpandedFolders((current) => ({
                                                ...current,
                                                [node.path]: !isExpanded
                                            }))
                                        }
                                        aria-label={
                                            isExpanded
                                                ? `Collapse ${node.name}`
                                                : `Expand ${node.name}`
                                        }
                                    >
                                        {isExpanded ? '-' : '+'}
                                    </button>
                                ) : (
                                    <span className="resource-folder-tree-spacer" aria-hidden />
                                )}
                                <button
                                    type="button"
                                    className={
                                        isSelected
                                            ? 'resource-folder-tree-button is-active'
                                            : 'resource-folder-tree-button'
                                    }
                                    onClick={() =>
                                        setSelectedFolderPath((current) =>
                                            current === node.path ? '' : node.path
                                        )
                                    }
                                >
                                    <span>{node.name}</span>
                                    <span className="muted-id">{node.count}</span>
                                </button>
                            </div>
                            {node.children.length > 0 && isExpanded
                                ? renderFolderNodes(node.children, depth + 1)
                                : null}
                        </li>
                    );
                })}
            </ul>
        );
    };

    return (
        <section className="panel resource-manager-panel" hidden={hidden}>
            <div className="history-filters">
                <div className="filter-field">
                    <label htmlFor="resource-scope">Space</label>
                    <div className="select-wrap">
                        <select
                            id="resource-scope"
                            value={resourceScopeFilter}
                            onChange={(event) =>
                                onResourceScopeFilterChange(
                                    event.target.value as 'all' | 'private' | 'shared'
                                )
                            }
                        >
                            <option value="all">All spaces</option>
                            <option value="private">Private</option>
                            <option value="shared">Shared</option>
                        </select>
                    </div>
                </div>

                <div className="filter-field">
                    <label htmlFor="resource-search">Search</label>
                    <input
                        id="resource-search"
                        value={resourceSearchInput}
                        onChange={(event) => onResourceSearchInputChange(event.target.value)}
                        placeholder="Title, folder, SQL or owner"
                    />
                </div>

                <div className="filter-field">
                    <label htmlFor="resource-tag">Tag</label>
                    <input
                        id="resource-tag"
                        value={resourceTagFilter}
                        onChange={(event) => onResourceTagFilterChange(event.target.value)}
                        placeholder="incident-response"
                    />
                </div>

                <div className="filter-field">
                    <label htmlFor="resource-group">Group</label>
                    <div className="select-wrap">
                        <select
                            id="resource-group"
                            value={resourceGroupFilter}
                            onChange={(event) => onResourceGroupFilterChange(event.target.value)}
                        >
                            <option value="">All groups</option>
                            {resourceGroupOptions.map((groupId) => (
                                <option key={`resource-group-${groupId}`} value={groupId}>
                                    {groupId}
                                </option>
                            ))}
                        </select>
                    </div>
                </div>

                <div className="filter-field">
                    <label htmlFor="resource-datasource">Connection</label>
                    <div className="select-wrap">
                        <select
                            id="resource-datasource"
                            value={resourceDatasourceFilter}
                            onChange={(event) =>
                                onResourceDatasourceFilterChange(event.target.value)
                            }
                        >
                            <option value="">All connections</option>
                            {resourceDatasourceOptions.map((datasource) => (
                                <option key={`resource-ds-${datasource.id}`} value={datasource.id}>
                                    {datasource.name}
                                </option>
                            ))}
                        </select>
                    </div>
                </div>
            </div>

            <div className="row toolbar-actions">
                <button type="button" className="chip" onClick={onStartCreate}>
                    New Script
                </button>
                <button
                    type="button"
                    className="chip"
                    onClick={onSeedFromActiveTab}
                    disabled={!canSeedFromActiveTab}
                    title={
                        canSeedFromActiveTab
                            ? `Load SQL from ${activeTabLabel}`
                            : 'Open a query tab first'
                    }
                >
                    Load Active Tab
                </button>
                <IconButton
                    icon="refresh"
                    title={loadingResources ? 'Refreshing scripts...' : 'Refresh scripts'}
                    onClick={onRefresh}
                    disabled={loadingResources}
                />
            </div>

            {resourceEditorMode !== 'list' ? (
                <div className="resource-manager-form stack-form">
                    <div className="resource-editor-header">
                        <div>
                            <h3>
                                {resourceEditorMode === 'create' ? 'Create Script' : 'Edit Script'}
                            </h3>
                        </div>
                        <span className={`status-pill ${formatScopeTone(resourceFormState.scope)}`}>
                            {formatScopeLabel(resourceFormState.scope)}
                        </span>
                    </div>
                    <div className="resource-manager-form-grid">
                        <div className="filter-field resource-form-field">
                            <label htmlFor="resource-form-title" className="label-with-help">
                                <span>Title</span>
                                <InfoHint text={resourceTitleHelpText} />
                            </label>
                            <input
                                id="resource-form-title"
                                value={resourceFormState.title}
                                onChange={(event) =>
                                    onResourceFormChange({
                                        ...resourceFormState,
                                        title: event.target.value
                                    })
                                }
                                placeholder="consumer-lag-triage"
                            />
                        </div>

                        <div className="filter-field resource-form-field">
                            <label htmlFor="resource-form-space">Space</label>
                            <div className="select-wrap">
                                <select
                                    id="resource-form-space"
                                    value={resourceFormState.scope}
                                    onChange={(event) =>
                                        onResourceFormChange({
                                            ...resourceFormState,
                                            scope: event.target.value as ResourceScope
                                        })
                                    }
                                >
                                    <option value="PRIVATE">Private</option>
                                    <option value="SHARED">Shared</option>
                                </select>
                            </div>
                        </div>

                        <div className="filter-field resource-form-field">
                            <label htmlFor="resource-form-group">Group</label>
                            <div className="select-wrap">
                                <select
                                    id="resource-form-group"
                                    value={resourceFormState.groupId}
                                    onChange={(event) =>
                                        onResourceFormChange({
                                            ...resourceFormState,
                                            groupId: event.target.value
                                        })
                                    }
                                    disabled={resourceFormState.scope !== 'SHARED'}
                                >
                                    <option value="">
                                        {resourceFormState.scope === 'SHARED'
                                            ? 'Choose a group'
                                            : 'Not required'}
                                    </option>
                                    {resourceGroupOptions.map((groupId) => (
                                        <option
                                            key={`resource-form-group-${groupId}`}
                                            value={groupId}
                                        >
                                            {groupId}
                                        </option>
                                    ))}
                                </select>
                            </div>
                        </div>

                        <div className="filter-field resource-form-field">
                            <label htmlFor="resource-form-folder" className="label-with-help">
                                <span>Folder</span>
                                <InfoHint text={resourceFolderHelpText} />
                            </label>
                            <input
                                id="resource-form-folder"
                                value={resourceFormState.folderPath}
                                onChange={(event) =>
                                    onResourceFormChange({
                                        ...resourceFormState,
                                        folderPath: event.target.value
                                    })
                                }
                                placeholder="oncall/kafka"
                            />
                        </div>

                        <div className="filter-field resource-form-field">
                            <label htmlFor="resource-form-datasource">Connection</label>
                            <div className="select-wrap">
                                <select
                                    id="resource-form-datasource"
                                    value={resourceFormState.datasourceId}
                                    onChange={(event) =>
                                        onResourceFormChange({
                                            ...resourceFormState,
                                            datasourceId: event.target.value
                                        })
                                    }
                                >
                                    <option value="">No fixed connection</option>
                                    {resourceDatasourceOptions.map((datasource) => (
                                        <option
                                            key={`resource-form-ds-${datasource.id}`}
                                            value={datasource.id}
                                        >
                                            {datasource.name}
                                        </option>
                                    ))}
                                </select>
                            </div>
                        </div>

                        <div className="filter-field resource-form-field">
                            <label htmlFor="resource-form-tags" className="label-with-help">
                                <span>Tags</span>
                                <InfoHint text={resourceTagHelpText} />
                            </label>
                            <div className="resource-tag-editor">
                                {committedTags.map((tag) => (
                                    <span key={`resource-tag-chip-${tag}`} className="status-pill">
                                        <span>{tag}</span>
                                        <button
                                            type="button"
                                            className="resource-tag-chip-remove"
                                            onClick={() => {
                                                setTagEditorError('');
                                                updateTagsInput(
                                                    committedTags.filter((entry) => entry !== tag),
                                                    tagDraft
                                                );
                                            }}
                                            aria-label={`Remove tag ${tag}`}
                                        >
                                            x
                                        </button>
                                    </span>
                                ))}
                                <input
                                    id="resource-form-tags"
                                    value={tagDraft}
                                    onChange={(event) => {
                                        setTagEditorError('');
                                        updateTagsInput(committedTags, event.target.value);
                                    }}
                                    onBlur={commitTagDraft}
                                    onKeyDown={(event) => {
                                        if (event.key === ',' || event.key === 'Enter') {
                                            event.preventDefault();
                                            commitTagDraft();
                                            return;
                                        }
                                        if (
                                            event.key === 'Backspace' &&
                                            !tagDraft &&
                                            committedTags.length > 0
                                        ) {
                                            event.preventDefault();
                                            setTagEditorError('');
                                            updateTagsInput(committedTags.slice(0, -1), '');
                                        }
                                    }}
                                    placeholder={
                                        committedTags.length === 0
                                            ? 'oncall, kafka, lag'
                                            : 'Add another tag'
                                    }
                                />
                            </div>
                            {tagEditorError ? (
                                <p className="form-error resource-tag-error">{tagEditorError}</p>
                            ) : null}
                        </div>
                    </div>

                    <label className="checkbox-inline resource-form-checkbox">
                        <input
                            type="checkbox"
                            checked={resourceFormState.allowGroupEdit}
                            onChange={(event) =>
                                onResourceFormChange({
                                    ...resourceFormState,
                                    allowGroupEdit: event.target.checked
                                })
                            }
                            disabled={resourceFormState.scope !== 'SHARED'}
                        />
                        <span>Allow group members to edit content</span>
                    </label>

                    <div className="stack-form">
                        <label htmlFor="resource-form-sql">SQL</label>
                        <textarea
                            id="resource-form-sql"
                            className="resource-sql-textarea"
                            value={resourceFormState.sql}
                            onChange={(event) =>
                                onResourceFormChange({
                                    ...resourceFormState,
                                    sql: event.target.value
                                })
                            }
                            placeholder="SELECT 1;"
                        />
                    </div>

                    <div className="row toolbar-actions">
                        <button type="button" onClick={onSaveResource} disabled={savingResource}>
                            {savingResource
                                ? 'Saving...'
                                : resourceEditorMode === 'create'
                                  ? 'Create Script'
                                  : 'Save Changes'}
                        </button>
                        <button type="button" className="chip" onClick={onCancelEdit}>
                            Cancel
                        </button>
                    </div>
                </div>
            ) : null}

            {resourceEditorMode === 'edit' ? (
                <div className="resource-history-panel stack-form">
                    <div className="resource-history-header">
                        <div>
                            <h3>Version history</h3>
                            <p>Restore a previous saved revision if a script needs to roll back.</p>
                        </div>
                        <IconButton
                            icon="refresh"
                            title={
                                loadingResourceVersions
                                    ? 'Refreshing version history...'
                                    : 'Refresh version history'
                            }
                            onClick={onRefreshVersions}
                            disabled={loadingResourceVersions}
                        />
                    </div>
                    <div className="history-table-wrap">
                        <table className="result-table history-table">
                            <thead>
                                <tr>
                                    <th>Revision</th>
                                    <th>Saved</th>
                                    <th>Actor</th>
                                    <th>Action</th>
                                    <th>Preview</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {resourceVersions.length === 0 ? (
                                    <tr>
                                        <td colSpan={6}>
                                            {loadingResourceVersions
                                                ? 'Loading version history...'
                                                : 'No saved revisions yet.'}
                                        </td>
                                    </tr>
                                ) : (
                                    resourceVersions.map((version) => (
                                        <tr key={`resource-version-${version.versionId}`}>
                                            <td>r{version.revision}</td>
                                            <td>{new Date(version.savedAt).toLocaleString()}</td>
                                            <td>{version.savedBy}</td>
                                            <td>
                                                <span className="status-pill">
                                                    {formatVersionActionLabel(version.action)}
                                                </span>
                                            </td>
                                            <td>
                                                <div className="resource-title-cell">
                                                    <strong>{version.title}</strong>
                                                    <span className="resource-sql-preview">
                                                        {version.sql || 'Empty script'}
                                                    </span>
                                                </div>
                                            </td>
                                            <td className="history-actions">
                                                <button
                                                    type="button"
                                                    className="chip"
                                                    onClick={() =>
                                                        onRestoreResourceVersion(version.versionId)
                                                    }
                                                    disabled={
                                                        loadingResourceVersions ||
                                                        restoringResourceVersionId ===
                                                            version.versionId
                                                    }
                                                >
                                                    {restoringResourceVersionId ===
                                                    version.versionId
                                                        ? 'Restoring...'
                                                        : 'Restore'}
                                                </button>
                                            </td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    </div>
                </div>
            ) : null}

            {resourceError ? (
                <p className="form-error" role="alert">
                    {resourceError}
                </p>
            ) : null}

            <div className="resource-manager-layout">
                <aside className="resource-folder-tree">
                    <div className="resource-folder-tree-header">
                        <h3>Browser</h3>
                    </div>
                    <button
                        type="button"
                        className={
                            selectedFolderPath
                                ? 'resource-folder-tree-button'
                                : 'resource-folder-tree-button is-active'
                        }
                        onClick={() => setSelectedFolderPath('')}
                    >
                        <span>All scripts</span>
                        <span className="muted-id">{resources.length}</span>
                    </button>
                    {unfiledResourcesCount > 0 ? (
                        <button
                            type="button"
                            className={
                                selectedFolderPath === '__unfiled__'
                                    ? 'resource-folder-tree-button is-active'
                                    : 'resource-folder-tree-button'
                            }
                            onClick={() =>
                                setSelectedFolderPath((current) =>
                                    current === '__unfiled__' ? '' : '__unfiled__'
                                )
                            }
                        >
                            <span>No folder</span>
                            <span className="muted-id">{unfiledResourcesCount}</span>
                        </button>
                    ) : null}
                    {folderTree.length > 0 ? (
                        renderFolderNodes(folderTree)
                    ) : (
                        <p className="resource-folder-tree-empty">
                            Add folder paths to scripts and they will show up here.
                        </p>
                    )}
                </aside>

                <div className="history-table-wrap">
                    <table className="result-table history-table">
                        <thead>
                            <tr>
                                <th>Updated</th>
                                <th>Space</th>
                                <th>Title</th>
                                <th>Revision</th>
                                <th>Folder</th>
                                <th>Tags</th>
                                <th>Connection</th>
                                <th>Owner</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {filteredResources.length === 0 ? (
                                <tr>
                                    <td colSpan={9}>No scripts found for the current filters.</td>
                                </tr>
                            ) : (
                                filteredResources.map((resource) => (
                                    <tr key={`resource-${resource.resourceId}`}>
                                        <td>{new Date(resource.updatedAt).toLocaleString()}</td>
                                        <td>
                                            <div className="resource-space-cell">
                                                <span
                                                    className={`status-pill ${formatScopeTone(resource.scope)}`}
                                                >
                                                    {formatScopeLabel(resource.scope)}
                                                </span>
                                                {resource.groupId ? (
                                                    <span className="muted-id">
                                                        {resource.groupId}
                                                    </span>
                                                ) : null}
                                            </div>
                                        </td>
                                        <td>
                                            <div className="resource-title-cell">
                                                <strong>{resource.title}</strong>
                                                <span className="resource-sql-preview">
                                                    {resource.sql || 'Empty script'}
                                                </span>
                                            </div>
                                        </td>
                                        <td>
                                            <div className="resource-space-cell">
                                                <strong>r{resource.currentRevision}</strong>
                                                <span className="muted-id">
                                                    {resource.versionCount} saved
                                                    {resource.versionCount === 1
                                                        ? ' revision'
                                                        : ' revisions'}
                                                </span>
                                            </div>
                                        </td>
                                        <td>{resource.folderPath || '-'}</td>
                                        <td>
                                            <div className="resource-tags-cell">
                                                {resource.tags.length === 0
                                                    ? '-'
                                                    : resource.tags.map((tag) => (
                                                          <span
                                                              key={`resource-tag-${resource.resourceId}-${tag}`}
                                                              className="status-pill"
                                                          >
                                                              {tag}
                                                          </span>
                                                      ))}
                                            </div>
                                        </td>
                                        <td>{resource.datasourceId || '-'}</td>
                                        <td>{resource.owner}</td>
                                        <td className="history-actions">
                                            <button
                                                type="button"
                                                className="chip"
                                                onClick={() => onOpenResource(resource, false)}
                                            >
                                                Open
                                            </button>
                                            <button
                                                type="button"
                                                onClick={() => onOpenResource(resource, true)}
                                            >
                                                Run
                                            </button>
                                            {resource.permissions.canShare ? (
                                                <button
                                                    type="button"
                                                    className="chip"
                                                    onClick={() => onEditResource(resource)}
                                                >
                                                    Edit
                                                </button>
                                            ) : null}
                                            <button
                                                type="button"
                                                className="chip"
                                                onClick={() => onDuplicateResource(resource)}
                                            >
                                                Duplicate
                                            </button>
                                            {resource.permissions.canDelete ? (
                                                <button
                                                    type="button"
                                                    className="danger-button"
                                                    onClick={() =>
                                                        onDeleteResource(resource.resourceId)
                                                    }
                                                >
                                                    Delete
                                                </button>
                                            ) : null}
                                        </td>
                                    </tr>
                                ))
                            )}
                        </tbody>
                    </table>
                </div>
            </div>
        </section>
    );
}
