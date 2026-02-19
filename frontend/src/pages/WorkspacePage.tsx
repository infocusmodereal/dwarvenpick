import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AppShell from '../components/AppShell';

type CurrentUserResponse = {
    username: string;
    displayName: string;
    roles: string[];
};

type DatasourceResponse = {
    id: string;
    name: string;
    engine: string;
    credentialProfiles: string[];
};

type GroupResponse = {
    id: string;
    name: string;
    description?: string;
    members: string[];
};

type DatasourceAccessResponse = {
    groupId: string;
    datasourceId: string;
    canQuery: boolean;
    canExport: boolean;
    maxRowsPerQuery?: number;
    maxRuntimeSeconds?: number;
    concurrencyLimit?: number;
    credentialProfile: string;
};

type QueryExecutionResponse = {
    executionId: string;
    datasourceId: string;
    status: string;
    message: string;
};

type ApiErrorResponse = {
    error?: string;
};

type CsrfTokenResponse = {
    token: string;
    headerName: string;
};

export default function WorkspacePage() {
    const navigate = useNavigate();

    const [currentUser, setCurrentUser] = useState<CurrentUserResponse | null>(null);
    const [visibleDatasources, setVisibleDatasources] = useState<DatasourceResponse[]>([]);
    const [workspaceError, setWorkspaceError] = useState('');
    const [loadingWorkspace, setLoadingWorkspace] = useState(true);

    const [sqlText, setSqlText] = useState('SELECT * FROM system.healthcheck;');
    const [selectedDatasourceId, setSelectedDatasourceId] = useState('');
    const [queryStatusMessage, setQueryStatusMessage] = useState('');
    const [queryErrorMessage, setQueryErrorMessage] = useState('');
    const [runningQuery, setRunningQuery] = useState(false);

    const [adminGroups, setAdminGroups] = useState<GroupResponse[]>([]);
    const [adminDatasourceCatalog, setAdminDatasourceCatalog] = useState<DatasourceResponse[]>([]);
    const [adminDatasourceAccess, setAdminDatasourceAccess] = useState<DatasourceAccessResponse[]>(
        []
    );
    const [adminError, setAdminError] = useState('');
    const [adminSuccess, setAdminSuccess] = useState('');

    const [groupNameInput, setGroupNameInput] = useState('');
    const [groupDescriptionInput, setGroupDescriptionInput] = useState('');
    const [groupDescriptionDrafts, setGroupDescriptionDrafts] = useState<Record<string, string>>(
        {}
    );
    const [memberDrafts, setMemberDrafts] = useState<Record<string, string>>({});

    const [selectedGroupId, setSelectedGroupId] = useState('');
    const [selectedDatasourceForAccess, setSelectedDatasourceForAccess] = useState('');
    const [canQuery, setCanQuery] = useState(true);
    const [canExport, setCanExport] = useState(false);
    const [credentialProfile, setCredentialProfile] = useState('');
    const [maxRowsPerQuery, setMaxRowsPerQuery] = useState('');
    const [maxRuntimeSeconds, setMaxRuntimeSeconds] = useState('');
    const [concurrencyLimit, setConcurrencyLimit] = useState('');
    const [savingAccess, setSavingAccess] = useState(false);

    const isSystemAdmin = currentUser?.roles.includes('SYSTEM_ADMIN') ?? false;

    const selectedDatasource = useMemo(
        () =>
            visibleDatasources.find((datasource) => datasource.id === selectedDatasourceId) ?? null,
        [selectedDatasourceId, visibleDatasources]
    );

    const selectedAdminDatasource = useMemo(
        () =>
            adminDatasourceCatalog.find(
                (datasource) => datasource.id === selectedDatasourceForAccess
            ) ?? null,
        [adminDatasourceCatalog, selectedDatasourceForAccess]
    );

    const selectedAccessRule = useMemo(
        () =>
            adminDatasourceAccess.find(
                (rule) =>
                    rule.groupId === selectedGroupId &&
                    rule.datasourceId === selectedDatasourceForAccess
            ) ?? null,
        [adminDatasourceAccess, selectedDatasourceForAccess, selectedGroupId]
    );

    const loadAdminData = useCallback(async (active = true) => {
        const [groupsResponse, catalogResponse, accessResponse] = await Promise.all([
            fetch('/api/admin/groups', { method: 'GET', credentials: 'include' }),
            fetch('/api/admin/datasources', { method: 'GET', credentials: 'include' }),
            fetch('/api/admin/datasource-access', { method: 'GET', credentials: 'include' })
        ]);

        if (!groupsResponse.ok || !catalogResponse.ok || !accessResponse.ok) {
            throw new Error('Failed to load admin governance data.');
        }

        const groups = (await groupsResponse.json()) as GroupResponse[];
        const datasourceCatalog = (await catalogResponse.json()) as DatasourceResponse[];
        const datasourceAccess = (await accessResponse.json()) as DatasourceAccessResponse[];

        if (!active) {
            return;
        }

        setAdminGroups(groups);
        setAdminDatasourceCatalog(datasourceCatalog);
        setAdminDatasourceAccess(datasourceAccess);
        setGroupDescriptionDrafts(
            groups.reduce<Record<string, string>>((drafts, group) => {
                drafts[group.id] = group.description ?? '';
                return drafts;
            }, {})
        );

        setSelectedGroupId((current) => current || groups[0]?.id || '');
        setSelectedDatasourceForAccess((current) => current || datasourceCatalog[0]?.id || '');
    }, []);

    useEffect(() => {
        let active = true;

        const loadWorkspace = async () => {
            try {
                setLoadingWorkspace(true);
                setWorkspaceError('');

                const meResponse = await fetch('/api/auth/me', {
                    method: 'GET',
                    credentials: 'include'
                });

                if (meResponse.status === 401) {
                    navigate('/login', { replace: true });
                    return;
                }

                if (!meResponse.ok) {
                    throw new Error('Failed to load current user profile.');
                }

                const me = (await meResponse.json()) as CurrentUserResponse;
                const datasourceResponse = await fetch('/api/datasources', {
                    method: 'GET',
                    credentials: 'include'
                });

                if (!datasourceResponse.ok) {
                    throw new Error('Failed to load datasource list.');
                }

                const datasources = (await datasourceResponse.json()) as DatasourceResponse[];
                if (!active) {
                    return;
                }

                setCurrentUser(me);
                setVisibleDatasources(datasources);
                setSelectedDatasourceId((current) => current || datasources[0]?.id || '');

                if (me.roles.includes('SYSTEM_ADMIN')) {
                    await loadAdminData(active);
                }
            } catch (error) {
                if (!active) {
                    return;
                }

                if (error instanceof Error) {
                    setWorkspaceError(error.message);
                } else {
                    setWorkspaceError('Failed to load workspace data.');
                }
            } finally {
                if (active) {
                    setLoadingWorkspace(false);
                }
            }
        };

        void loadWorkspace();

        return () => {
            active = false;
        };
    }, [loadAdminData, navigate]);

    useEffect(() => {
        const matchedDatasource = selectedAdminDatasource;
        if (!matchedDatasource) {
            return;
        }

        if (selectedAccessRule) {
            setCanQuery(selectedAccessRule.canQuery);
            setCanExport(selectedAccessRule.canExport);
            setCredentialProfile(selectedAccessRule.credentialProfile);
            setMaxRowsPerQuery(
                selectedAccessRule.maxRowsPerQuery
                    ? selectedAccessRule.maxRowsPerQuery.toString()
                    : ''
            );
            setMaxRuntimeSeconds(
                selectedAccessRule.maxRuntimeSeconds
                    ? selectedAccessRule.maxRuntimeSeconds.toString()
                    : ''
            );
            setConcurrencyLimit(
                selectedAccessRule.concurrencyLimit
                    ? selectedAccessRule.concurrencyLimit.toString()
                    : ''
            );
            return;
        }

        setCanQuery(true);
        setCanExport(false);
        setCredentialProfile(matchedDatasource.credentialProfiles[0] ?? '');
        setMaxRowsPerQuery('');
        setMaxRuntimeSeconds('');
        setConcurrencyLimit('');
    }, [selectedAccessRule, selectedAdminDatasource]);

    const readFriendlyError = async (response: Response): Promise<string> => {
        try {
            const payload = (await response.json()) as ApiErrorResponse;
            if (payload.error?.trim()) {
                return payload.error;
            }
        } catch {
            // Use fallback friendly messages below when payload parsing fails.
        }

        if (response.status === 401) {
            return 'Authentication is required. Please sign in again.';
        }

        if (response.status === 403) {
            return 'You do not have permission for this action.';
        }

        return 'Request failed. Please try again.';
    };

    const fetchCsrfToken = async (): Promise<CsrfTokenResponse> => {
        const response = await fetch('/api/auth/csrf', {
            method: 'GET',
            credentials: 'include'
        });

        if (!response.ok) {
            throw new Error('Unable to acquire a CSRF token for this request.');
        }

        return (await response.json()) as CsrfTokenResponse;
    };

    const handleRunQuery = async () => {
        if (!selectedDatasourceId) {
            setQueryErrorMessage('Select a datasource before running a query.');
            return;
        }

        setRunningQuery(true);
        setQueryErrorMessage('');
        setQueryStatusMessage('');

        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch('/api/queries', {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfToken.headerName]: csrfToken.token
                },
                body: JSON.stringify({
                    datasourceId: selectedDatasourceId,
                    sql: sqlText
                })
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as QueryExecutionResponse;
            setQueryStatusMessage(
                `Execution ${payload.executionId} queued on ${payload.datasourceId} (${payload.status}).`
            );
        } catch (error) {
            if (error instanceof Error) {
                setQueryErrorMessage(error.message);
            } else {
                setQueryErrorMessage('Failed to run query.');
            }
        } finally {
            setRunningQuery(false);
        }
    };

    const handleCreateGroup = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setAdminSuccess('');

        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch('/api/admin/groups', {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfToken.headerName]: csrfToken.token
                },
                body: JSON.stringify({
                    name: groupNameInput,
                    description: groupDescriptionInput
                })
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            setGroupNameInput('');
            setGroupDescriptionInput('');
            await loadAdminData();
            setAdminSuccess('Group created successfully.');
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to create group.');
            }
        }
    };

    const handleUpdateGroupDescription = async (groupId: string) => {
        setAdminError('');
        setAdminSuccess('');

        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch(`/api/admin/groups/${groupId}`, {
                method: 'PATCH',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfToken.headerName]: csrfToken.token
                },
                body: JSON.stringify({
                    description: groupDescriptionDrafts[groupId] ?? ''
                })
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            await loadAdminData();
            setAdminSuccess(`Updated group ${groupId}.`);
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to update group.');
            }
        }
    };

    const handleAddMember = async (groupId: string) => {
        const username = memberDrafts[groupId]?.trim() ?? '';
        if (!username) {
            setAdminError('Member username is required.');
            return;
        }

        setAdminError('');
        setAdminSuccess('');
        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch(`/api/admin/groups/${groupId}/members`, {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfToken.headerName]: csrfToken.token
                },
                body: JSON.stringify({
                    username
                })
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            setMemberDrafts((drafts) => ({ ...drafts, [groupId]: '' }));
            await loadAdminData();
            setAdminSuccess(`Added ${username} to ${groupId}.`);
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to add group member.');
            }
        }
    };

    const handleRemoveMember = async (groupId: string, username: string) => {
        setAdminError('');
        setAdminSuccess('');

        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch(`/api/admin/groups/${groupId}/members/${username}`, {
                method: 'DELETE',
                credentials: 'include',
                headers: {
                    [csrfToken.headerName]: csrfToken.token
                }
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            await loadAdminData();
            setAdminSuccess(`Removed ${username} from ${groupId}.`);
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to remove group member.');
            }
        }
    };

    const handleSaveDatasourceAccess = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setAdminSuccess('');

        if (!selectedGroupId || !selectedDatasourceForAccess) {
            setAdminError('Select both group and datasource.');
            return;
        }

        if (!credentialProfile.trim()) {
            setAdminError('Credential profile is required.');
            return;
        }

        setSavingAccess(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch(
                `/api/admin/datasource-access/${selectedGroupId}/${selectedDatasourceForAccess}`,
                {
                    method: 'PUT',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrfToken.headerName]: csrfToken.token
                    },
                    body: JSON.stringify({
                        canQuery,
                        canExport,
                        maxRowsPerQuery: maxRowsPerQuery.trim() ? Number(maxRowsPerQuery) : null,
                        maxRuntimeSeconds: maxRuntimeSeconds.trim()
                            ? Number(maxRuntimeSeconds)
                            : null,
                        concurrencyLimit: concurrencyLimit.trim() ? Number(concurrencyLimit) : null,
                        credentialProfile
                    })
                }
            );

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            await loadAdminData();
            setAdminSuccess('Datasource access mapping saved.');
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to save datasource mapping.');
            }
        } finally {
            setSavingAccess(false);
        }
    };

    if (loadingWorkspace) {
        return (
            <AppShell title="badgermole Workspace">
                <section className="panel">
                    <p>Loading workspace...</p>
                </section>
            </AppShell>
        );
    }

    return (
        <AppShell title="badgermole Workspace">
            {workspaceError ? (
                <section className="panel">
                    <p className="form-error">{workspaceError}</p>
                </section>
            ) : null}

            <div className="workspace-grid">
                <aside className="panel sidebar">
                    <h2>Connections</h2>
                    {visibleDatasources.length === 0 ? (
                        <p>No datasource access has been granted yet.</p>
                    ) : (
                        <ul>
                            {visibleDatasources.map((datasource) => (
                                <li key={datasource.id}>
                                    <button
                                        type="button"
                                        className={
                                            selectedDatasourceId === datasource.id
                                                ? 'datasource-button active'
                                                : 'datasource-button'
                                        }
                                        onClick={() => setSelectedDatasourceId(datasource.id)}
                                    >
                                        {datasource.name} ({datasource.engine})
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                </aside>

                <section className="panel editor">
                    <h2>SQL Editor</h2>
                    <p>
                        Signed in as{' '}
                        <strong>{currentUser?.displayName ?? currentUser?.username}</strong>.
                    </p>
                    <textarea
                        className="sql-editor"
                        value={sqlText}
                        onChange={(event) => setSqlText(event.target.value)}
                    />
                    <div className="row">
                        <button
                            type="button"
                            disabled={runningQuery || !selectedDatasource}
                            onClick={handleRunQuery}
                        >
                            {runningQuery ? 'Running...' : 'Run'}
                        </button>
                        <button type="button" disabled>
                            Run Selection
                        </button>
                        <button type="button" disabled>
                            Cancel
                        </button>
                        <button type="button" disabled>
                            Export CSV
                        </button>
                    </div>
                </section>

                <section className="panel results">
                    <h2>Results Grid</h2>
                    {queryStatusMessage ? <p>{queryStatusMessage}</p> : null}
                    {queryErrorMessage ? (
                        <p className="form-error" role="alert">
                            {queryErrorMessage}
                        </p>
                    ) : null}
                    {!queryStatusMessage && !queryErrorMessage ? (
                        <p>
                            Server-side pagination and virtualization will be implemented in
                            Milestones 6-7.
                        </p>
                    ) : null}
                </section>
            </div>

            {isSystemAdmin ? (
                <section className="panel admin-governance">
                    <h2>Admin Governance</h2>
                    <p>Manage RBAC groups and datasource access mappings.</p>

                    {adminError ? (
                        <p className="form-error" role="alert">
                            {adminError}
                        </p>
                    ) : null}
                    {adminSuccess ? <p className="form-success">{adminSuccess}</p> : null}

                    <div className="admin-grid">
                        <section className="panel">
                            <h3>Groups</h3>
                            <form onSubmit={handleCreateGroup} className="stack-form">
                                <label htmlFor="new-group-name">New Group</label>
                                <input
                                    id="new-group-name"
                                    value={groupNameInput}
                                    onChange={(event) => setGroupNameInput(event.target.value)}
                                    placeholder="Incident Responders"
                                    required
                                />
                                <label htmlFor="new-group-description">Description</label>
                                <input
                                    id="new-group-description"
                                    value={groupDescriptionInput}
                                    onChange={(event) =>
                                        setGroupDescriptionInput(event.target.value)
                                    }
                                    placeholder="Optional description"
                                />
                                <button type="submit">Create Group</button>
                            </form>

                            <div className="group-list">
                                {adminGroups.map((group) => (
                                    <article key={group.id} className="group-card">
                                        <h4>{group.name}</h4>
                                        <p className="muted-id">{group.id}</p>
                                        <label htmlFor={`group-description-${group.id}`}>
                                            Description
                                        </label>
                                        <input
                                            id={`group-description-${group.id}`}
                                            value={groupDescriptionDrafts[group.id] ?? ''}
                                            onChange={(event) =>
                                                setGroupDescriptionDrafts((drafts) => ({
                                                    ...drafts,
                                                    [group.id]: event.target.value
                                                }))
                                            }
                                        />
                                        <button
                                            type="button"
                                            onClick={() =>
                                                void handleUpdateGroupDescription(group.id)
                                            }
                                        >
                                            Save Description
                                        </button>

                                        <div className="member-row">
                                            <input
                                                value={memberDrafts[group.id] ?? ''}
                                                onChange={(event) =>
                                                    setMemberDrafts((drafts) => ({
                                                        ...drafts,
                                                        [group.id]: event.target.value
                                                    }))
                                                }
                                                placeholder="username"
                                            />
                                            <button
                                                type="button"
                                                onClick={() => void handleAddMember(group.id)}
                                            >
                                                Add Member
                                            </button>
                                        </div>

                                        <ul className="members-list">
                                            {group.members.length === 0 ? (
                                                <li>No members</li>
                                            ) : (
                                                group.members.map((member) => (
                                                    <li key={`${group.id}-${member}`}>
                                                        <span>{member}</span>
                                                        <button
                                                            type="button"
                                                            className="danger-button"
                                                            onClick={() =>
                                                                void handleRemoveMember(
                                                                    group.id,
                                                                    member
                                                                )
                                                            }
                                                        >
                                                            Remove
                                                        </button>
                                                    </li>
                                                ))
                                            )}
                                        </ul>
                                    </article>
                                ))}
                            </div>
                        </section>

                        <section className="panel">
                            <h3>Datasource Access Mapping</h3>
                            <form className="stack-form" onSubmit={handleSaveDatasourceAccess}>
                                <label htmlFor="access-group">Group</label>
                                <select
                                    id="access-group"
                                    value={selectedGroupId}
                                    onChange={(event) => setSelectedGroupId(event.target.value)}
                                >
                                    <option value="">Select group</option>
                                    {adminGroups.map((group) => (
                                        <option key={group.id} value={group.id}>
                                            {group.name} ({group.id})
                                        </option>
                                    ))}
                                </select>

                                <label htmlFor="access-datasource">Datasource</label>
                                <select
                                    id="access-datasource"
                                    value={selectedDatasourceForAccess}
                                    onChange={(event) =>
                                        setSelectedDatasourceForAccess(event.target.value)
                                    }
                                >
                                    <option value="">Select datasource</option>
                                    {adminDatasourceCatalog.map((datasource) => (
                                        <option key={datasource.id} value={datasource.id}>
                                            {datasource.name} ({datasource.engine})
                                        </option>
                                    ))}
                                </select>

                                <div className="row">
                                    <label className="checkbox-row">
                                        <input
                                            type="checkbox"
                                            checked={canQuery}
                                            onChange={(event) => setCanQuery(event.target.checked)}
                                        />
                                        <span>Can Query</span>
                                    </label>
                                    <label className="checkbox-row">
                                        <input
                                            type="checkbox"
                                            checked={canExport}
                                            onChange={(event) => setCanExport(event.target.checked)}
                                        />
                                        <span>Can Export</span>
                                    </label>
                                </div>

                                <label htmlFor="credential-profile">Credential Profile</label>
                                <select
                                    id="credential-profile"
                                    value={credentialProfile}
                                    onChange={(event) => setCredentialProfile(event.target.value)}
                                >
                                    <option value="">Select credential profile</option>
                                    {(selectedAdminDatasource?.credentialProfiles ?? []).map(
                                        (profile) => (
                                            <option key={profile} value={profile}>
                                                {profile}
                                            </option>
                                        )
                                    )}
                                </select>

                                <label htmlFor="max-rows">Max Rows Per Query</label>
                                <input
                                    id="max-rows"
                                    type="number"
                                    min={1}
                                    value={maxRowsPerQuery}
                                    onChange={(event) => setMaxRowsPerQuery(event.target.value)}
                                />

                                <label htmlFor="max-runtime">Max Runtime Seconds</label>
                                <input
                                    id="max-runtime"
                                    type="number"
                                    min={1}
                                    value={maxRuntimeSeconds}
                                    onChange={(event) => setMaxRuntimeSeconds(event.target.value)}
                                />

                                <label htmlFor="concurrency-limit">Concurrency Limit</label>
                                <input
                                    id="concurrency-limit"
                                    type="number"
                                    min={1}
                                    value={concurrencyLimit}
                                    onChange={(event) => setConcurrencyLimit(event.target.value)}
                                />

                                <button
                                    type="submit"
                                    disabled={
                                        savingAccess ||
                                        !selectedGroupId ||
                                        !selectedDatasourceForAccess ||
                                        !credentialProfile.trim()
                                    }
                                >
                                    {savingAccess ? 'Saving...' : 'Save Mapping'}
                                </button>
                            </form>

                            <div className="mapping-list">
                                <h4>Current Access Rules</h4>
                                <ul>
                                    {adminDatasourceAccess.map((rule) => (
                                        <li key={`${rule.groupId}-${rule.datasourceId}`}>
                                            <strong>{rule.groupId}</strong> →{' '}
                                            <strong>{rule.datasourceId}</strong> | query:{' '}
                                            {rule.canQuery ? 'yes' : 'no'} | export:{' '}
                                            {rule.canExport ? 'yes' : 'no'} | profile:{' '}
                                            {rule.credentialProfile}
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        </section>
                    </div>
                </section>
            ) : null}
        </AppShell>
    );
}
