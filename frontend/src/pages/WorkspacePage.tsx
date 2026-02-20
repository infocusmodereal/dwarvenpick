import Editor, { OnMount } from '@monaco-editor/react';
import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { editor as MonacoEditorNamespace } from 'monaco-editor';
import { useNavigate } from 'react-router-dom';
import AppShell from '../components/AppShell';

type CurrentUserResponse = {
    username: string;
    displayName: string;
    roles: string[];
};

type CatalogDatasourceResponse = {
    id: string;
    name: string;
    engine: string;
    credentialProfiles: string[];
};

type DatasourceEngine = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'TRINO' | 'STARROCKS' | 'VERTICA';

type TlsMode = 'DISABLE' | 'REQUIRE';

type DriverDescriptorResponse = {
    driverId: string;
    engine: DatasourceEngine;
    driverClass: string;
    source: string;
    available: boolean;
    description: string;
    message: string;
};

type PoolSettings = {
    maximumPoolSize: number;
    minimumIdle: number;
    connectionTimeoutMs: number;
    idleTimeoutMs: number;
};

type TlsSettings = {
    mode: TlsMode;
    verifyServerCertificate: boolean;
    allowSelfSigned: boolean;
};

type ManagedCredentialProfileResponse = {
    profileId: string;
    username: string;
    description?: string;
    encryptionKeyId: string;
    updatedAt: string;
};

type ManagedDatasourceResponse = {
    id: string;
    name: string;
    engine: DatasourceEngine;
    host: string;
    port: number;
    database?: string;
    driverId: string;
    driverClass: string;
    pool: PoolSettings;
    tls: TlsSettings;
    options: Record<string, string>;
    credentialProfiles: ManagedCredentialProfileResponse[];
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
    queryHash: string;
};

type QueryExecutionStatusResponse = {
    executionId: string;
    datasourceId: string;
    status: string;
    message: string;
    submittedAt: string;
    startedAt?: string;
    completedAt?: string;
    queryHash: string;
    errorSummary?: string;
    rowCount: number;
    columnCount: number;
    rowLimitReached: boolean;
    maxRowsPerQuery: number;
    maxRuntimeSeconds: number;
    credentialProfile: string;
};

type QueryResultColumn = {
    name: string;
    jdbcType: string;
};

type QueryResultsResponse = {
    executionId: string;
    status: string;
    columns: QueryResultColumn[];
    rows: Array<Array<string | null>>;
    pageSize: number;
    nextPageToken?: string;
    rowLimitReached: boolean;
};

type QueryStatusEventResponse = {
    eventId: string;
    executionId: string;
    datasourceId: string;
    status: string;
    message: string;
    occurredAt: string;
};

type PersistentWorkspaceTab = {
    id: string;
    title: string;
    datasourceId: string;
    schema: string;
    queryText: string;
};

type WorkspaceTab = PersistentWorkspaceTab & {
    isExecuting: boolean;
    statusMessage: string;
    errorMessage: string;
    executionId: string;
    executionStatus: string;
    queryHash: string;
    resultColumns: QueryResultColumn[];
    resultRows: Array<Array<string | null>>;
    nextPageToken: string;
    currentPageToken: string;
    previousPageTokens: string[];
    rowLimitReached: boolean;
};

type TestConnectionResponse = {
    success: boolean;
    datasourceId: string;
    credentialProfile: string;
    driverId: string;
    driverClass: string;
    message: string;
};

type ReencryptCredentialsResponse = {
    updatedProfiles: number;
    activeKeyId: string;
    message: string;
};

type ManagedDatasourceFormState = {
    name: string;
    engine: DatasourceEngine;
    host: string;
    port: string;
    database: string;
    driverId: string;
    maximumPoolSize: string;
    minimumIdle: string;
    connectionTimeoutMs: string;
    idleTimeoutMs: string;
    tlsMode: TlsMode;
    verifyServerCertificate: boolean;
    allowSelfSigned: boolean;
};

type ApiErrorResponse = {
    error?: string;
};

type CsrfTokenResponse = {
    token: string;
    headerName: string;
};

const defaultPoolSettings: PoolSettings = {
    maximumPoolSize: 5,
    minimumIdle: 1,
    connectionTimeoutMs: 30_000,
    idleTimeoutMs: 600_000
};

const defaultTlsSettings: TlsSettings = {
    mode: 'DISABLE',
    verifyServerCertificate: true,
    allowSelfSigned: false
};

const workspaceTabsStorageKey = 'badgermole.workspace.tabs.v1';
const queryStatusPollingIntervalMs = 500;
const queryStatusPollingMaxAttempts = 120;
const firstPageToken = '';

const isTerminalExecutionStatus = (status: string): boolean =>
    status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELED';

const defaultPortByEngine: Record<DatasourceEngine, number> = {
    POSTGRESQL: 5432,
    MYSQL: 3306,
    MARIADB: 3306,
    TRINO: 8088,
    STARROCKS: 9030,
    VERTICA: 5433
};

const buildBlankDatasourceForm = (
    engine: DatasourceEngine = 'POSTGRESQL'
): ManagedDatasourceFormState => ({
    name: '',
    engine,
    host: 'localhost',
    port: defaultPortByEngine[engine].toString(),
    database: '',
    driverId: '',
    maximumPoolSize: defaultPoolSettings.maximumPoolSize.toString(),
    minimumIdle: defaultPoolSettings.minimumIdle.toString(),
    connectionTimeoutMs: defaultPoolSettings.connectionTimeoutMs.toString(),
    idleTimeoutMs: defaultPoolSettings.idleTimeoutMs.toString(),
    tlsMode: defaultTlsSettings.mode,
    verifyServerCertificate: defaultTlsSettings.verifyServerCertificate,
    allowSelfSigned: defaultTlsSettings.allowSelfSigned
});

const buildDatasourceFormFromManaged = (
    datasource: ManagedDatasourceResponse
): ManagedDatasourceFormState => ({
    name: datasource.name,
    engine: datasource.engine,
    host: datasource.host,
    port: datasource.port.toString(),
    database: datasource.database ?? '',
    driverId: datasource.driverId,
    maximumPoolSize: datasource.pool.maximumPoolSize.toString(),
    minimumIdle: datasource.pool.minimumIdle.toString(),
    connectionTimeoutMs: datasource.pool.connectionTimeoutMs.toString(),
    idleTimeoutMs: datasource.pool.idleTimeoutMs.toString(),
    tlsMode: datasource.tls.mode,
    verifyServerCertificate: datasource.tls.verifyServerCertificate,
    allowSelfSigned: datasource.tls.allowSelfSigned
});

const createTabId = (): string => {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }

    return `tab-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const buildWorkspaceTab = (
    datasourceId: string,
    title: string,
    queryText = 'SELECT * FROM system.healthcheck;'
): WorkspaceTab => ({
    id: createTabId(),
    title,
    datasourceId,
    schema: '',
    queryText,
    isExecuting: false,
    statusMessage: '',
    errorMessage: '',
    executionId: '',
    executionStatus: '',
    queryHash: '',
    resultColumns: [],
    resultRows: [],
    nextPageToken: '',
    currentPageToken: '',
    previousPageTokens: [],
    rowLimitReached: false
});

const toPersistentTab = (tab: WorkspaceTab): PersistentWorkspaceTab => ({
    id: tab.id,
    title: tab.title,
    datasourceId: tab.datasourceId,
    schema: tab.schema,
    queryText: tab.queryText
});

export default function WorkspacePage() {
    const navigate = useNavigate();

    const [currentUser, setCurrentUser] = useState<CurrentUserResponse | null>(null);
    const [visibleDatasources, setVisibleDatasources] = useState<CatalogDatasourceResponse[]>([]);
    const [workspaceError, setWorkspaceError] = useState('');
    const [loadingWorkspace, setLoadingWorkspace] = useState(true);
    const [workspaceTabs, setWorkspaceTabs] = useState<WorkspaceTab[]>([]);
    const [activeTabId, setActiveTabId] = useState('');
    const [tabsHydrated, setTabsHydrated] = useState(false);
    const editorRef = useRef<MonacoEditorNamespace.IStandaloneCodeEditor | null>(null);
    const workspaceTabsRef = useRef<WorkspaceTab[]>([]);
    const queryStatusPollingTimersRef = useRef<
        Record<string, ReturnType<typeof setTimeout> | undefined>
    >({});
    const queryEventsRef = useRef<EventSource | null>(null);

    const [adminGroups, setAdminGroups] = useState<GroupResponse[]>([]);
    const [adminDatasourceCatalog, setAdminDatasourceCatalog] = useState<
        CatalogDatasourceResponse[]
    >([]);
    const [adminDatasourceAccess, setAdminDatasourceAccess] = useState<DatasourceAccessResponse[]>(
        []
    );
    const [adminManagedDatasources, setAdminManagedDatasources] = useState<
        ManagedDatasourceResponse[]
    >([]);
    const [adminDrivers, setAdminDrivers] = useState<DriverDescriptorResponse[]>([]);
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
    const [selectedManagedDatasourceId, setSelectedManagedDatasourceId] = useState('');
    const [managedDatasourceForm, setManagedDatasourceForm] = useState<ManagedDatasourceFormState>(
        buildBlankDatasourceForm()
    );
    const [savingDatasource, setSavingDatasource] = useState(false);
    const [deletingDatasource, setDeletingDatasource] = useState(false);
    const [credentialProfileIdInput, setCredentialProfileIdInput] = useState('');
    const [credentialUsernameInput, setCredentialUsernameInput] = useState('');
    const [credentialPasswordInput, setCredentialPasswordInput] = useState('');
    const [credentialDescriptionInput, setCredentialDescriptionInput] = useState('');
    const [savingCredentialProfile, setSavingCredentialProfile] = useState(false);
    const [reencryptingCredentials, setReencryptingCredentials] = useState(false);
    const [selectedCredentialProfileForTest, setSelectedCredentialProfileForTest] = useState('');
    const [validationQueryInput, setValidationQueryInput] = useState('SELECT 1');
    const [overrideTlsForTest, setOverrideTlsForTest] = useState(false);
    const [testTlsMode, setTestTlsMode] = useState<TlsMode>('DISABLE');
    const [testVerifyServerCertificate, setTestVerifyServerCertificate] = useState(true);
    const [testAllowSelfSigned, setTestAllowSelfSigned] = useState(false);
    const [testingConnection, setTestingConnection] = useState(false);
    const [testConnectionMessage, setTestConnectionMessage] = useState('');
    const [testConnectionOutcome, setTestConnectionOutcome] = useState<'success' | 'failure' | ''>(
        ''
    );

    const isSystemAdmin = currentUser?.roles.includes('SYSTEM_ADMIN') ?? false;

    const activeTab = useMemo(
        () => workspaceTabs.find((tab) => tab.id === activeTabId) ?? null,
        [activeTabId, workspaceTabs]
    );

    useEffect(() => {
        workspaceTabsRef.current = workspaceTabs;
    }, [workspaceTabs]);

    const selectedDatasource = useMemo(
        () =>
            visibleDatasources.find((datasource) => datasource.id === activeTab?.datasourceId) ??
            null,
        [activeTab?.datasourceId, visibleDatasources]
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

    const selectedManagedDatasource = useMemo(
        () =>
            adminManagedDatasources.find(
                (datasource) => datasource.id === selectedManagedDatasourceId
            ) ?? null,
        [adminManagedDatasources, selectedManagedDatasourceId]
    );

    const driversForFormEngine = useMemo(
        () => adminDrivers.filter((driver) => driver.engine === managedDatasourceForm.engine),
        [adminDrivers, managedDatasourceForm.engine]
    );

    const selectedDriverForForm = useMemo(
        () =>
            driversForFormEngine.find(
                (driver) => driver.driverId === managedDatasourceForm.driverId
            ) ?? null,
        [driversForFormEngine, managedDatasourceForm.driverId]
    );

    const hydrateWorkspaceTabs = useCallback(
        (datasources: CatalogDatasourceResponse[]) => {
            const allowedDatasourceIds = new Set(datasources.map((datasource) => datasource.id));
            const fallbackDatasourceId = datasources[0]?.id ?? '';

            type PersistedTabsPayload = {
                activeTabId: string;
                tabs: PersistentWorkspaceTab[];
            };

            const fromStorage = (): PersistedTabsPayload | null => {
                try {
                    const raw = localStorage.getItem(workspaceTabsStorageKey);
                    if (!raw) {
                        return null;
                    }

                    const parsed = JSON.parse(raw) as PersistedTabsPayload;
                    if (!Array.isArray(parsed.tabs)) {
                        return null;
                    }

                    return parsed;
                } catch {
                    return null;
                }
            };

            const stored = fromStorage();
            const hydratedTabs =
                stored?.tabs
                    .filter((tab) => typeof tab.id === 'string' && typeof tab.title === 'string')
                    .map<WorkspaceTab>((tab) => ({
                        id: tab.id,
                        title: tab.title.trim() || 'Query',
                        datasourceId: allowedDatasourceIds.has(tab.datasourceId)
                            ? tab.datasourceId
                            : fallbackDatasourceId,
                        schema: typeof tab.schema === 'string' ? tab.schema : '',
                        queryText:
                            typeof tab.queryText === 'string'
                                ? tab.queryText
                                : 'SELECT * FROM system.healthcheck;',
                        isExecuting: false,
                        statusMessage: '',
                        errorMessage: '',
                        executionId: '',
                        executionStatus: '',
                        queryHash: '',
                        resultColumns: [],
                        resultRows: [],
                        nextPageToken: '',
                        currentPageToken: '',
                        previousPageTokens: [],
                        rowLimitReached: false
                    })) ?? [];

            const tabsToUse =
                hydratedTabs.length > 0
                    ? hydratedTabs
                    : [buildWorkspaceTab(fallbackDatasourceId, 'Query 1')];
            const activeCandidate = stored?.activeTabId ?? '';
            const resolvedActiveTabId = tabsToUse.some((tab) => tab.id === activeCandidate)
                ? activeCandidate
                : tabsToUse[0].id;

            setWorkspaceTabs(tabsToUse);
            setActiveTabId(resolvedActiveTabId);
            setTabsHydrated(true);
        },
        [setActiveTabId, setTabsHydrated, setWorkspaceTabs]
    );

    const updateWorkspaceTab = useCallback(
        (tabId: string, updater: (tab: WorkspaceTab) => WorkspaceTab) => {
            setWorkspaceTabs((currentTabs) =>
                currentTabs.map((tab) => (tab.id === tabId ? updater(tab) : tab))
            );
        },
        []
    );

    useEffect(() => {
        if (!tabsHydrated) {
            return;
        }

        const payload = {
            activeTabId,
            tabs: workspaceTabs.map(toPersistentTab)
        };
        localStorage.setItem(workspaceTabsStorageKey, JSON.stringify(payload));
    }, [activeTabId, tabsHydrated, workspaceTabs]);

    useEffect(() => {
        if (workspaceTabs.length === 0) {
            return;
        }

        if (!workspaceTabs.some((tab) => tab.id === activeTabId)) {
            setActiveTabId(workspaceTabs[0].id);
        }
    }, [activeTabId, workspaceTabs]);

    useEffect(() => {
        if (!tabsHydrated) {
            return;
        }

        const permittedDatasourceIds = new Set(
            visibleDatasources.map((datasource) => datasource.id)
        );
        const fallbackDatasourceId = visibleDatasources[0]?.id ?? '';

        setWorkspaceTabs((currentTabs) =>
            currentTabs.map((tab) => {
                if (!tab.datasourceId || permittedDatasourceIds.has(tab.datasourceId)) {
                    return tab;
                }

                return {
                    ...tab,
                    datasourceId: fallbackDatasourceId,
                    errorMessage:
                        'Datasource access changed. Select a permitted datasource before running.',
                    statusMessage: ''
                };
            })
        );
    }, [tabsHydrated, visibleDatasources]);

    const loadAdminData = useCallback(async (active = true) => {
        const [
            groupsResponse,
            catalogResponse,
            accessResponse,
            managedDatasourceResponse,
            driversResponse
        ] = await Promise.all([
            fetch('/api/admin/groups', { method: 'GET', credentials: 'include' }),
            fetch('/api/admin/datasources', { method: 'GET', credentials: 'include' }),
            fetch('/api/admin/datasource-access', { method: 'GET', credentials: 'include' }),
            fetch('/api/admin/datasource-management', {
                method: 'GET',
                credentials: 'include'
            }),
            fetch('/api/admin/drivers', {
                method: 'GET',
                credentials: 'include'
            })
        ]);

        if (
            !groupsResponse.ok ||
            !catalogResponse.ok ||
            !accessResponse.ok ||
            !managedDatasourceResponse.ok ||
            !driversResponse.ok
        ) {
            throw new Error('Failed to load admin governance data.');
        }

        const groups = (await groupsResponse.json()) as GroupResponse[];
        const datasourceCatalog = (await catalogResponse.json()) as CatalogDatasourceResponse[];
        const datasourceAccess = (await accessResponse.json()) as DatasourceAccessResponse[];
        const managedDatasources =
            (await managedDatasourceResponse.json()) as ManagedDatasourceResponse[];
        const drivers = (await driversResponse.json()) as DriverDescriptorResponse[];

        if (!active) {
            return;
        }

        setAdminGroups(groups);
        setAdminDatasourceCatalog(datasourceCatalog);
        setAdminDatasourceAccess(datasourceAccess);
        setAdminManagedDatasources(managedDatasources);
        setAdminDrivers(drivers);
        setGroupDescriptionDrafts(
            groups.reduce<Record<string, string>>((drafts, group) => {
                drafts[group.id] = group.description ?? '';
                return drafts;
            }, {})
        );

        setSelectedGroupId((current) =>
            groups.some((group) => group.id === current) ? current : groups[0]?.id || ''
        );
        setSelectedDatasourceForAccess((current) =>
            datasourceCatalog.some((datasource) => datasource.id === current)
                ? current
                : datasourceCatalog[0]?.id || ''
        );
        setSelectedManagedDatasourceId((current) =>
            managedDatasources.some((datasource) => datasource.id === current)
                ? current
                : managedDatasources[0]?.id || ''
        );
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

                const datasources =
                    (await datasourceResponse.json()) as CatalogDatasourceResponse[];
                if (!active) {
                    return;
                }

                setCurrentUser(me);
                setVisibleDatasources(datasources);
                hydrateWorkspaceTabs(datasources);

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
    }, [hydrateWorkspaceTabs, loadAdminData, navigate]);

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

    useEffect(() => {
        if (!selectedManagedDatasource) {
            setManagedDatasourceForm((current) => {
                const blank = buildBlankDatasourceForm(current.engine);
                const matchingDrivers = adminDrivers.filter(
                    (driver) => driver.engine === blank.engine
                );
                const defaultDriver =
                    matchingDrivers.find((driver) => driver.available) ?? matchingDrivers[0];

                return {
                    ...blank,
                    driverId: defaultDriver?.driverId ?? ''
                };
            });
            setSelectedCredentialProfileForTest('');
            setCredentialProfileIdInput('');
            setCredentialUsernameInput('');
            setCredentialPasswordInput('');
            setCredentialDescriptionInput('');
            setTestConnectionMessage('');
            setTestConnectionOutcome('');
            return;
        }

        setManagedDatasourceForm(buildDatasourceFormFromManaged(selectedManagedDatasource));
        setSelectedCredentialProfileForTest(
            selectedManagedDatasource.credentialProfiles[0]?.profileId ?? ''
        );
        setValidationQueryInput('SELECT 1');
        setOverrideTlsForTest(false);
        setTestTlsMode(selectedManagedDatasource.tls.mode);
        setTestVerifyServerCertificate(selectedManagedDatasource.tls.verifyServerCertificate);
        setTestAllowSelfSigned(selectedManagedDatasource.tls.allowSelfSigned);
        setCredentialProfileIdInput(
            selectedManagedDatasource.credentialProfiles[0]?.profileId ?? ''
        );
        setCredentialUsernameInput(selectedManagedDatasource.credentialProfiles[0]?.username ?? '');
        setCredentialPasswordInput('');
        setCredentialDescriptionInput(
            selectedManagedDatasource.credentialProfiles[0]?.description ?? ''
        );
        setTestConnectionMessage('');
        setTestConnectionOutcome('');
    }, [adminDrivers, selectedManagedDatasource]);

    useEffect(() => {
        if (driversForFormEngine.length === 0) {
            setManagedDatasourceForm((current) => ({ ...current, driverId: '' }));
            return;
        }

        setManagedDatasourceForm((current) => {
            const driverExists = driversForFormEngine.some(
                (driver) => driver.driverId === current.driverId
            );
            if (driverExists) {
                return current;
            }

            const preferred = driversForFormEngine.find((driver) => driver.available);
            return {
                ...current,
                driverId: (preferred ?? driversForFormEngine[0]).driverId
            };
        });
    }, [driversForFormEngine]);

    useEffect(() => {
        if (!selectedManagedDatasource || !credentialProfileIdInput.trim()) {
            return;
        }

        const selectedProfile = selectedManagedDatasource.credentialProfiles.find(
            (profile) => profile.profileId === credentialProfileIdInput.trim()
        );
        if (!selectedProfile) {
            return;
        }

        setCredentialUsernameInput(selectedProfile.username);
        setCredentialDescriptionInput(selectedProfile.description ?? '');
        setCredentialPasswordInput('');
    }, [credentialProfileIdInput, selectedManagedDatasource]);

    const readFriendlyError = useCallback(async (response: Response): Promise<string> => {
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
    }, []);

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

    const handleOpenNewTab = useCallback(() => {
        const datasourceId = visibleDatasources[0]?.id ?? '';

        setWorkspaceTabs((currentTabs) => {
            const nextIndex = currentTabs.length + 1;
            const createdTab = buildWorkspaceTab(datasourceId, `Query ${nextIndex}`, 'SELECT 1;');
            setActiveTabId(createdTab.id);
            return [...currentTabs, createdTab];
        });
    }, [visibleDatasources]);

    const handleRenameTab = useCallback(
        (tabId: string) => {
            const tab = workspaceTabs.find((candidate) => candidate.id === tabId);
            if (!tab) {
                return;
            }

            const proposedName = window.prompt('Rename tab', tab.title);
            if (proposedName === null) {
                return;
            }

            const trimmedName = proposedName.trim();
            if (!trimmedName) {
                return;
            }

            updateWorkspaceTab(tabId, (currentTab) => ({
                ...currentTab,
                title: trimmedName
            }));
        },
        [updateWorkspaceTab, workspaceTabs]
    );

    const clearQueryStatusPolling = useCallback((tabId: string) => {
        const timer = queryStatusPollingTimersRef.current[tabId];
        if (timer) {
            clearTimeout(timer);
        }
        delete queryStatusPollingTimersRef.current[tabId];
    }, []);

    const fetchQueryResultsPage = useCallback(
        async (
            tabId: string,
            executionId: string,
            pageToken = firstPageToken,
            previousPageTokens?: string[]
        ) => {
            const queryParams = new URLSearchParams();
            queryParams.set('pageSize', '100');
            if (pageToken) {
                queryParams.set('pageToken', pageToken);
            }

            const response = await fetch(
                `/api/queries/${executionId}/results?${queryParams.toString()}`,
                {
                    method: 'GET',
                    credentials: 'include'
                }
            );
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as QueryResultsResponse;
            updateWorkspaceTab(tabId, (currentTab) => {
                if (currentTab.executionId !== executionId) {
                    return currentTab;
                }

                return {
                    ...currentTab,
                    resultColumns: payload.columns,
                    resultRows: payload.rows,
                    nextPageToken: payload.nextPageToken ?? '',
                    currentPageToken: pageToken,
                    previousPageTokens: previousPageTokens ?? currentTab.previousPageTokens,
                    rowLimitReached: payload.rowLimitReached,
                    errorMessage: ''
                };
            });
        },
        [readFriendlyError, updateWorkspaceTab]
    );

    const fetchExecutionStatus = useCallback(
        async (
            tabId: string,
            executionId: string,
            loadFirstResultsPageOnSuccess: boolean
        ): Promise<QueryExecutionStatusResponse> => {
            const response = await fetch(`/api/queries/${executionId}`, {
                method: 'GET',
                credentials: 'include'
            });
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as QueryExecutionStatusResponse;
            const terminal = isTerminalExecutionStatus(payload.status);
            updateWorkspaceTab(tabId, (currentTab) => {
                if (currentTab.executionId !== executionId) {
                    return currentTab;
                }

                return {
                    ...currentTab,
                    executionStatus: payload.status,
                    queryHash: payload.queryHash,
                    statusMessage: payload.message,
                    errorMessage:
                        payload.status === 'FAILED'
                            ? (payload.errorSummary ?? payload.message)
                            : '',
                    rowLimitReached: payload.rowLimitReached,
                    isExecuting: !terminal
                };
            });

            if (terminal) {
                clearQueryStatusPolling(tabId);
            }

            if (payload.status === 'SUCCEEDED' && loadFirstResultsPageOnSuccess) {
                await fetchQueryResultsPage(tabId, executionId, firstPageToken, []);
            }

            return payload;
        },
        [clearQueryStatusPolling, fetchQueryResultsPage, readFriendlyError, updateWorkspaceTab]
    );

    const startQueryStatusPolling = useCallback(
        (tabId: string, executionId: string) => {
            clearQueryStatusPolling(tabId);
            let attempts = 0;

            const poll = async () => {
                const trackedTab = workspaceTabsRef.current.find((tab) => tab.id === tabId);
                if (!trackedTab || trackedTab.executionId !== executionId) {
                    clearQueryStatusPolling(tabId);
                    return;
                }

                try {
                    const status = await fetchExecutionStatus(tabId, executionId, true);
                    if (isTerminalExecutionStatus(status.status)) {
                        clearQueryStatusPolling(tabId);
                        return;
                    }
                } catch {
                    // Continue polling up to the max attempt count.
                }

                attempts += 1;
                if (attempts >= queryStatusPollingMaxAttempts) {
                    clearQueryStatusPolling(tabId);
                    return;
                }

                queryStatusPollingTimersRef.current[tabId] = setTimeout(() => {
                    void poll();
                }, queryStatusPollingIntervalMs);
            };

            void poll();
        },
        [clearQueryStatusPolling, fetchExecutionStatus]
    );

    const handleCancelRun = useCallback(
        async (tabId: string, triggeredByShortcut = false) => {
            const tab = workspaceTabsRef.current.find((candidate) => candidate.id === tabId);
            if (!tab || !tab.executionId || isTerminalExecutionStatus(tab.executionStatus)) {
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    statusMessage: triggeredByShortcut
                        ? 'No query is running for this tab.'
                        : currentTab.statusMessage,
                    errorMessage: ''
                }));
                return;
            }

            try {
                const csrfToken = await fetchCsrfToken();
                const response = await fetch(`/api/queries/${tab.executionId}/cancel`, {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        [csrfToken.headerName]: csrfToken.token
                    }
                });
                if (!response.ok) {
                    throw new Error(await readFriendlyError(response));
                }

                clearQueryStatusPolling(tabId);
                await fetchExecutionStatus(tabId, tab.executionId, false);
            } catch (error) {
                const message =
                    error instanceof Error ? error.message : 'Failed to cancel query execution.';
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    errorMessage: message
                }));
            }
        },
        [clearQueryStatusPolling, fetchExecutionStatus, readFriendlyError, updateWorkspaceTab]
    );

    const handleCloseTab = useCallback(
        (tabId: string) => {
            const closingTab = workspaceTabs.find((tab) => tab.id === tabId);
            if (!closingTab) {
                return;
            }

            clearQueryStatusPolling(tabId);
            if (closingTab.isExecuting) {
                void handleCancelRun(tabId);
            }

            setWorkspaceTabs((currentTabs) => {
                if (currentTabs.length <= 1) {
                    const replacementTab = buildWorkspaceTab(
                        visibleDatasources[0]?.id ?? '',
                        'Query 1',
                        'SELECT 1;'
                    );
                    setActiveTabId(replacementTab.id);
                    return [replacementTab];
                }

                const closeIndex = currentTabs.findIndex((tab) => tab.id === tabId);
                const remainingTabs = currentTabs.filter((tab) => tab.id !== tabId);
                if (activeTabId === tabId) {
                    const nextTab = remainingTabs[Math.max(0, closeIndex - 1)] ?? remainingTabs[0];
                    setActiveTabId(nextTab.id);
                }

                return remainingTabs;
            });
        },
        [activeTabId, clearQueryStatusPolling, handleCancelRun, visibleDatasources, workspaceTabs]
    );

    const executeSqlForTab = useCallback(
        async (tabId: string, sqlText: string, modeLabel: 'selection' | 'all') => {
            const tab = workspaceTabs.find((candidate) => candidate.id === tabId);
            if (!tab) {
                return;
            }

            if (tab.isExecuting) {
                return;
            }

            const datasourceId = tab.datasourceId.trim();
            if (!datasourceId) {
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    errorMessage: 'Select a datasource before running a query.',
                    statusMessage: ''
                }));
                return;
            }

            if (!visibleDatasources.some((datasource) => datasource.id === datasourceId)) {
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    errorMessage:
                        'Selected datasource is no longer permitted for your account. Choose another datasource.',
                    statusMessage: ''
                }));
                return;
            }

            const normalizedSql = sqlText.trim();
            if (!normalizedSql) {
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    errorMessage:
                        modeLabel === 'selection'
                            ? 'Select SQL text first, or use Run All.'
                            : 'Query text is empty.',
                    statusMessage: ''
                }));
                return;
            }

            updateWorkspaceTab(tabId, (currentTab) => ({
                ...currentTab,
                isExecuting: true,
                executionId: '',
                executionStatus: '',
                queryHash: '',
                resultColumns: [],
                resultRows: [],
                nextPageToken: '',
                currentPageToken: firstPageToken,
                previousPageTokens: [],
                rowLimitReached: false,
                statusMessage:
                    modeLabel === 'selection'
                        ? 'Running selected SQL...'
                        : 'Running full tab SQL...',
                errorMessage: ''
            }));

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
                        datasourceId,
                        sql: normalizedSql
                    })
                });

                if (!response.ok) {
                    throw new Error(await readFriendlyError(response));
                }

                const payload = (await response.json()) as QueryExecutionResponse;
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    executionId: payload.executionId,
                    executionStatus: payload.status,
                    queryHash: payload.queryHash,
                    statusMessage: `Execution ${payload.executionId} queued on ${payload.datasourceId}.`,
                    errorMessage: ''
                }));
                startQueryStatusPolling(tabId, payload.executionId);
            } catch (error) {
                clearQueryStatusPolling(tabId);
                const message = error instanceof Error ? error.message : 'Failed to run query.';
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    isExecuting: false,
                    errorMessage: message,
                    statusMessage: ''
                }));
            }
        },
        [
            clearQueryStatusPolling,
            readFriendlyError,
            startQueryStatusPolling,
            updateWorkspaceTab,
            visibleDatasources,
            workspaceTabs
        ]
    );

    const handleRunAll = useCallback(() => {
        if (!activeTab) {
            return;
        }

        void executeSqlForTab(activeTab.id, activeTab.queryText, 'all');
    }, [activeTab, executeSqlForTab]);

    const handleRunSelection = useCallback(() => {
        if (!activeTab) {
            return;
        }

        const editor = editorRef.current;
        let selectedSql = '';
        if (editor) {
            const model = editor.getModel();
            const selection = editor.getSelection();
            if (model && selection && !selection.isEmpty()) {
                selectedSql = model.getValueInRange(selection);
            }
        }

        if (!selectedSql.trim()) {
            selectedSql = activeTab.queryText;
        }

        void executeSqlForTab(activeTab.id, selectedSql, 'selection');
    }, [activeTab, executeSqlForTab]);

    const handleLoadNextResults = useCallback(() => {
        if (!activeTab || !activeTab.executionId || !activeTab.nextPageToken) {
            return;
        }

        const updatedHistory = [...activeTab.previousPageTokens, activeTab.currentPageToken];
        void fetchQueryResultsPage(
            activeTab.id,
            activeTab.executionId,
            activeTab.nextPageToken,
            updatedHistory
        );
    }, [activeTab, fetchQueryResultsPage]);

    const handleLoadPreviousResults = useCallback(() => {
        if (!activeTab || !activeTab.executionId || activeTab.previousPageTokens.length === 0) {
            return;
        }

        const updatedHistory = [...activeTab.previousPageTokens];
        const previousToken = updatedHistory.pop() ?? firstPageToken;
        void fetchQueryResultsPage(
            activeTab.id,
            activeTab.executionId,
            previousToken,
            updatedHistory
        );
    }, [activeTab, fetchQueryResultsPage]);

    const handleEditorDidMount: OnMount = (editorInstance) => {
        editorRef.current = editorInstance;
        editorInstance.focus();
    };

    const handleDatasourceChangeForActiveTab = (nextDatasourceId: string) => {
        if (!activeTab) {
            return;
        }

        if (!visibleDatasources.some((datasource) => datasource.id === nextDatasourceId)) {
            updateWorkspaceTab(activeTab.id, (currentTab) => ({
                ...currentTab,
                errorMessage:
                    'Selected datasource is not permitted for this account. Choose a valid datasource.',
                statusMessage: ''
            }));
            return;
        }

        if (
            activeTab.datasourceId &&
            activeTab.datasourceId !== nextDatasourceId &&
            activeTab.queryText.trim()
        ) {
            const confirmed = window.confirm(
                'Switching datasource changes execution context for this tab. Continue?'
            );
            if (!confirmed) {
                return;
            }
        }

        updateWorkspaceTab(activeTab.id, (currentTab) => ({
            ...currentTab,
            datasourceId: nextDatasourceId,
            statusMessage: `Datasource context set to ${nextDatasourceId}.`,
            errorMessage: ''
        }));
    };

    useEffect(() => {
        const handleKeyboardShortcut = (event: KeyboardEvent) => {
            const activeElement = document.activeElement as HTMLElement | null;
            const focusedInEditor = activeElement?.closest('.monaco-editor');
            if (!focusedInEditor) {
                return;
            }

            if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                event.preventDefault();
                handleRunSelection();
                return;
            }

            if (event.key === 'Escape' && activeTab?.isExecuting) {
                event.preventDefault();
                void handleCancelRun(activeTab.id, true);
            }
        };

        window.addEventListener('keydown', handleKeyboardShortcut);
        return () => {
            window.removeEventListener('keydown', handleKeyboardShortcut);
        };
    }, [activeTab?.id, activeTab?.isExecuting, handleCancelRun, handleRunSelection]);

    useEffect(() => {
        if (!tabsHydrated || typeof EventSource === 'undefined') {
            return;
        }

        const eventSource = new EventSource('/api/queries/events', { withCredentials: true });
        queryEventsRef.current = eventSource;

        const handleStatusEvent = (event: Event) => {
            const messageEvent = event as MessageEvent<string>;
            if (!messageEvent.data) {
                return;
            }

            let payload: QueryStatusEventResponse;
            try {
                payload = JSON.parse(messageEvent.data) as QueryStatusEventResponse;
            } catch {
                return;
            }

            const tab = workspaceTabsRef.current.find(
                (candidate) => candidate.executionId === payload.executionId
            );
            if (!tab) {
                return;
            }

            const terminal = isTerminalExecutionStatus(payload.status);
            updateWorkspaceTab(tab.id, (currentTab) => {
                if (currentTab.executionId !== payload.executionId) {
                    return currentTab;
                }

                return {
                    ...currentTab,
                    executionStatus: payload.status,
                    statusMessage: payload.message,
                    isExecuting: !terminal,
                    errorMessage:
                        payload.status === 'FAILED'
                            ? currentTab.errorMessage || payload.message
                            : currentTab.errorMessage
                };
            });

            if (terminal) {
                clearQueryStatusPolling(tab.id);
                void fetchExecutionStatus(tab.id, payload.executionId, true);
            }
        };

        eventSource.addEventListener('query-status', handleStatusEvent as EventListener);

        return () => {
            eventSource.removeEventListener('query-status', handleStatusEvent as EventListener);
            eventSource.close();
            if (queryEventsRef.current === eventSource) {
                queryEventsRef.current = null;
            }
        };
    }, [clearQueryStatusPolling, fetchExecutionStatus, tabsHydrated, updateWorkspaceTab]);

    useEffect(
        () => () => {
            Object.values(queryStatusPollingTimersRef.current).forEach((timer) => {
                if (timer) {
                    clearTimeout(timer);
                }
            });
            queryStatusPollingTimersRef.current = {};
            queryEventsRef.current?.close();
            queryEventsRef.current = null;
        },
        []
    );

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

    const parsePositiveInteger = (value: string, fieldName: string): number => {
        const parsed = Number(value);
        if (!Number.isInteger(parsed) || parsed < 1) {
            throw new Error(`${fieldName} must be a positive whole number.`);
        }
        return parsed;
    };

    const handlePrepareNewDatasource = () => {
        const defaultEngine = managedDatasourceForm.engine;
        const empty = buildBlankDatasourceForm(defaultEngine);
        const candidates = adminDrivers.filter((driver) => driver.engine === defaultEngine);
        const preferredDriver = candidates.find((driver) => driver.available) ?? candidates[0];

        setSelectedManagedDatasourceId('');
        setManagedDatasourceForm({
            ...empty,
            driverId: preferredDriver?.driverId ?? ''
        });
        setCredentialProfileIdInput('');
        setCredentialUsernameInput('');
        setCredentialPasswordInput('');
        setCredentialDescriptionInput('');
        setSelectedCredentialProfileForTest('');
        setTestConnectionMessage('');
        setTestConnectionOutcome('');
        setAdminError('');
        setAdminSuccess('');
    };

    const handleSaveManagedDatasource = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setAdminSuccess('');

        if (!managedDatasourceForm.name.trim()) {
            setAdminError('Datasource name is required.');
            return;
        }

        if (!managedDatasourceForm.host.trim()) {
            setAdminError('Datasource host is required.');
            return;
        }

        if (!managedDatasourceForm.driverId.trim()) {
            setAdminError('Select a JDBC driver.');
            return;
        }

        setSavingDatasource(true);
        try {
            const port = parsePositiveInteger(managedDatasourceForm.port, 'Port');
            if (port > 65_535) {
                throw new Error('Port must be between 1 and 65535.');
            }

            const poolPayload = {
                maximumPoolSize: parsePositiveInteger(
                    managedDatasourceForm.maximumPoolSize,
                    'Maximum pool size'
                ),
                minimumIdle: parsePositiveInteger(
                    managedDatasourceForm.minimumIdle,
                    'Minimum idle'
                ),
                connectionTimeoutMs: parsePositiveInteger(
                    managedDatasourceForm.connectionTimeoutMs,
                    'Connection timeout'
                ),
                idleTimeoutMs: parsePositiveInteger(
                    managedDatasourceForm.idleTimeoutMs,
                    'Idle timeout'
                )
            };

            const commonPayload = {
                name: managedDatasourceForm.name.trim(),
                host: managedDatasourceForm.host.trim(),
                port,
                database: managedDatasourceForm.database.trim()
                    ? managedDatasourceForm.database.trim()
                    : null,
                driverId: managedDatasourceForm.driverId,
                pool: poolPayload,
                tls: {
                    mode: managedDatasourceForm.tlsMode,
                    verifyServerCertificate: managedDatasourceForm.verifyServerCertificate,
                    allowSelfSigned: managedDatasourceForm.allowSelfSigned
                }
            };

            const payload = selectedManagedDatasource
                ? commonPayload
                : {
                      ...commonPayload,
                      engine: managedDatasourceForm.engine
                  };

            const csrfToken = await fetchCsrfToken();
            const endpoint = selectedManagedDatasource
                ? `/api/admin/datasource-management/${selectedManagedDatasource.id}`
                : '/api/admin/datasource-management';
            const method = selectedManagedDatasource ? 'PATCH' : 'POST';

            const response = await fetch(endpoint, {
                method,
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfToken.headerName]: csrfToken.token
                },
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const savedDatasource = (await response.json()) as ManagedDatasourceResponse;
            setSelectedManagedDatasourceId(savedDatasource.id);
            await loadAdminData();
            setAdminSuccess(
                selectedManagedDatasource
                    ? `Datasource ${savedDatasource.id} updated.`
                    : `Datasource ${savedDatasource.id} created.`
            );
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to save datasource.');
            }
        } finally {
            setSavingDatasource(false);
        }
    };

    const handleDeleteManagedDatasource = async () => {
        if (!selectedManagedDatasource) {
            setAdminError('Select a datasource to delete.');
            return;
        }

        const confirmed = window.confirm(
            `Delete datasource "${selectedManagedDatasource.name}" (${selectedManagedDatasource.id})?`
        );
        if (!confirmed) {
            return;
        }

        setAdminError('');
        setAdminSuccess('');
        setDeletingDatasource(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch(
                `/api/admin/datasource-management/${selectedManagedDatasource.id}`,
                {
                    method: 'DELETE',
                    credentials: 'include',
                    headers: {
                        [csrfToken.headerName]: csrfToken.token
                    }
                }
            );

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            setSelectedManagedDatasourceId('');
            await loadAdminData();
            setAdminSuccess(`Datasource ${selectedManagedDatasource.id} deleted.`);
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to delete datasource.');
            }
        } finally {
            setDeletingDatasource(false);
        }
    };

    const handleSaveCredentialProfile = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setAdminSuccess('');

        if (!selectedManagedDatasource) {
            setAdminError('Select a datasource first.');
            return;
        }

        if (!credentialProfileIdInput.trim()) {
            setAdminError('Credential profile ID is required.');
            return;
        }

        if (!credentialUsernameInput.trim()) {
            setAdminError('Credential username is required.');
            return;
        }

        if (!credentialPasswordInput.trim()) {
            setAdminError('Credential password is required.');
            return;
        }

        setSavingCredentialProfile(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const profileId = credentialProfileIdInput.trim();
            const response = await fetch(
                `/api/admin/datasource-management/${selectedManagedDatasource.id}/credentials/${profileId}`,
                {
                    method: 'PUT',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrfToken.headerName]: csrfToken.token
                    },
                    body: JSON.stringify({
                        username: credentialUsernameInput.trim(),
                        password: credentialPasswordInput,
                        description: credentialDescriptionInput.trim()
                            ? credentialDescriptionInput.trim()
                            : null
                    })
                }
            );

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            setSelectedManagedDatasourceId(selectedManagedDatasource.id);
            setSelectedCredentialProfileForTest(profileId);
            setCredentialPasswordInput('');
            await loadAdminData();
            setAdminSuccess(`Credential profile ${profileId} saved.`);
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to save credential profile.');
            }
        } finally {
            setSavingCredentialProfile(false);
        }
    };

    const handleReencryptCredentials = async () => {
        setAdminError('');
        setAdminSuccess('');
        setReencryptingCredentials(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch('/api/admin/datasource-management/credentials/reencrypt', {
                method: 'POST',
                credentials: 'include',
                headers: {
                    [csrfToken.headerName]: csrfToken.token
                }
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as ReencryptCredentialsResponse;
            await loadAdminData();
            setAdminSuccess(
                `Re-encrypted ${payload.updatedProfiles} credential profile(s) with key ${payload.activeKeyId}.`
            );
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to rotate credential encryption key.');
            }
        } finally {
            setReencryptingCredentials(false);
        }
    };

    const handleTestConnection = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setTestConnectionMessage('');
        setTestConnectionOutcome('');

        if (!selectedManagedDatasource) {
            setAdminError('Select a datasource first.');
            return;
        }

        if (!selectedCredentialProfileForTest.trim()) {
            setAdminError('Select a credential profile for connection testing.');
            return;
        }

        setTestingConnection(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const body: Record<string, unknown> = {
                credentialProfile: selectedCredentialProfileForTest,
                validationQuery: validationQueryInput.trim() || 'SELECT 1'
            };

            if (overrideTlsForTest) {
                body.tls = {
                    mode: testTlsMode,
                    verifyServerCertificate: testVerifyServerCertificate,
                    allowSelfSigned: testAllowSelfSigned
                };
            }

            const response = await fetch(
                `/api/datasources/${selectedManagedDatasource.id}/test-connection`,
                {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrfToken.headerName]: csrfToken.token
                    },
                    body: JSON.stringify(body)
                }
            );

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as TestConnectionResponse;
            setTestConnectionOutcome(payload.success ? 'success' : 'failure');
            setTestConnectionMessage(payload.message);
        } catch (error) {
            const message =
                error instanceof Error ? error.message : 'Connection test failed unexpectedly.';
            setTestConnectionOutcome('failure');
            setTestConnectionMessage(message);
        } finally {
            setTestingConnection(false);
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
                    <p>Datasource visibility is scoped by RBAC and bound per tab.</p>
                    {visibleDatasources.length === 0 ? (
                        <p>No datasource access has been granted yet.</p>
                    ) : (
                        <ul>
                            {visibleDatasources.map((datasource) => (
                                <li key={datasource.id}>
                                    <button
                                        type="button"
                                        className={
                                            activeTab?.datasourceId === datasource.id
                                                ? 'datasource-button active'
                                                : 'datasource-button'
                                        }
                                        onClick={() =>
                                            handleDatasourceChangeForActiveTab(datasource.id)
                                        }
                                    >
                                        {datasource.name} ({datasource.engine})
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                    <button type="button" onClick={handleOpenNewTab}>
                        New Query Tab
                    </button>
                </aside>

                <section className="panel editor">
                    <h2>SQL Editor</h2>
                    <p>
                        Signed in as{' '}
                        <strong>{currentUser?.displayName ?? currentUser?.username}</strong>.
                    </p>
                    <div className="editor-tabs" role="tablist" aria-label="SQL tabs">
                        {workspaceTabs.map((tab) => (
                            <div
                                key={tab.id}
                                className={
                                    tab.id === activeTabId ? 'editor-tab active' : 'editor-tab'
                                }
                            >
                                <button
                                    type="button"
                                    role="tab"
                                    className="tab-select"
                                    aria-selected={tab.id === activeTabId}
                                    onClick={() => setActiveTabId(tab.id)}
                                >
                                    {tab.title}
                                    {tab.isExecuting ? ' (Running)' : ''}
                                </button>
                                <button
                                    type="button"
                                    className="chip"
                                    onClick={() => handleRenameTab(tab.id)}
                                >
                                    Rename
                                </button>
                                <button
                                    type="button"
                                    className="danger-button tab-close"
                                    onClick={() => handleCloseTab(tab.id)}
                                    aria-label={`Close ${tab.title}`}
                                >
                                    x
                                </button>
                            </div>
                        ))}
                    </div>

                    <div className="editor-context">
                        <label htmlFor="tab-datasource">Datasource</label>
                        <select
                            id="tab-datasource"
                            value={activeTab?.datasourceId ?? ''}
                            onChange={(event) =>
                                handleDatasourceChangeForActiveTab(event.target.value)
                            }
                            disabled={!activeTab || visibleDatasources.length === 0}
                        >
                            <option value="">Select datasource</option>
                            {visibleDatasources.map((datasource) => (
                                <option key={datasource.id} value={datasource.id}>
                                    {datasource.name} ({datasource.engine})
                                </option>
                            ))}
                        </select>

                        <label htmlFor="tab-schema">Schema (optional)</label>
                        <input
                            id="tab-schema"
                            value={activeTab?.schema ?? ''}
                            onChange={(event) => {
                                if (!activeTab) {
                                    return;
                                }

                                updateWorkspaceTab(activeTab.id, (currentTab) => ({
                                    ...currentTab,
                                    schema: event.target.value
                                }));
                            }}
                            placeholder="public"
                            disabled={!activeTab}
                        />
                    </div>

                    <div className="monaco-host">
                        <Editor
                            height="320px"
                            language="sql"
                            value={activeTab?.queryText ?? ''}
                            onMount={handleEditorDidMount}
                            onChange={(value) => {
                                if (!activeTab) {
                                    return;
                                }

                                updateWorkspaceTab(activeTab.id, (currentTab) => ({
                                    ...currentTab,
                                    queryText: value ?? '',
                                    errorMessage: currentTab.errorMessage,
                                    statusMessage: currentTab.statusMessage
                                }));
                            }}
                            options={{
                                automaticLayout: true,
                                minimap: { enabled: false },
                                lineNumbers: 'on',
                                bracketPairColorization: { enabled: true },
                                wordWrap: 'on',
                                scrollBeyondLastLine: false
                            }}
                        />
                    </div>

                    <div className="row">
                        <button
                            type="button"
                            disabled={!activeTab || activeTab.isExecuting || !selectedDatasource}
                            onClick={handleRunSelection}
                        >
                            {activeTab?.isExecuting ? 'Running...' : 'Run Selection'}
                        </button>
                        <button
                            type="button"
                            disabled={!activeTab || activeTab.isExecuting || !selectedDatasource}
                            onClick={handleRunAll}
                        >
                            Run All
                        </button>
                        <button
                            type="button"
                            disabled={!activeTab || !activeTab.isExecuting}
                            onClick={() => {
                                if (!activeTab) {
                                    return;
                                }

                                handleCancelRun(activeTab.id);
                            }}
                        >
                            Cancel
                        </button>
                        <button type="button" disabled>
                            Export CSV
                        </button>
                    </div>
                </section>

                <section className="panel results">
                    <h2>Results Grid</h2>
                    {activeTab?.executionId ? (
                        <div className="result-meta">
                            <p>
                                <strong>Execution:</strong> {activeTab.executionId}
                            </p>
                            <p>
                                <strong>Status:</strong>{' '}
                                {activeTab.executionStatus || 'PENDING_SUBMISSION'}
                            </p>
                            {activeTab.queryHash ? (
                                <p>
                                    <strong>Query Hash:</strong>{' '}
                                    <code className="result-hash">{activeTab.queryHash}</code>
                                </p>
                            ) : null}
                        </div>
                    ) : (
                        <p>Run a query to view execution status and results.</p>
                    )}
                    {activeTab?.statusMessage ? <p>{activeTab.statusMessage}</p> : null}
                    {activeTab?.errorMessage ? (
                        <p className="form-error" role="alert">
                            {activeTab.errorMessage}
                        </p>
                    ) : null}
                    {activeTab?.rowLimitReached ? (
                        <p className="form-error" role="alert">
                            Result row limit reached for this execution.
                        </p>
                    ) : null}

                    {activeTab?.resultColumns.length ? (
                        <>
                            <div className="result-actions row">
                                <button
                                    type="button"
                                    onClick={handleLoadPreviousResults}
                                    disabled={activeTab.previousPageTokens.length === 0}
                                >
                                    Previous Page
                                </button>
                                <button
                                    type="button"
                                    onClick={handleLoadNextResults}
                                    disabled={!activeTab.nextPageToken}
                                >
                                    Next Page
                                </button>
                            </div>
                            <div className="result-table-wrap">
                                <table className="result-table">
                                    <thead>
                                        <tr>
                                            {activeTab.resultColumns.map((column) => (
                                                <th key={`${column.name}-${column.jdbcType}`}>
                                                    {column.name}
                                                </th>
                                            ))}
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {activeTab.resultRows.map((row, rowIndex) => (
                                            <tr key={`row-${rowIndex}`}>
                                                {row.map((value, columnIndex) => (
                                                    <td key={`cell-${rowIndex}-${columnIndex}`}>
                                                        {value ?? 'NULL'}
                                                    </td>
                                                ))}
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </>
                    ) : null}
                    {activeTab?.executionStatus === 'SUCCEEDED' &&
                    activeTab.resultColumns.length === 0 &&
                    !activeTab.errorMessage ? (
                        <p>Query completed successfully and returned no rows.</p>
                    ) : null}
                    {!activeTab?.executionId &&
                    !activeTab?.statusMessage &&
                    !activeTab?.errorMessage ? (
                        <p>
                            Results populate after query submission. Use next/previous once paging
                            tokens are available.
                        </p>
                    ) : null}
                    <div className="shortcut-help">
                        <h3>Editor Shortcuts</h3>
                        <ul>
                            <li>
                                <kbd>Ctrl/Cmd + Enter</kbd>: Run selection (or full tab if no
                                selection)
                            </li>
                            <li>
                                <kbd>Esc</kbd>: Cancel currently running execution
                            </li>
                        </ul>
                    </div>
                </section>
            </div>

            {isSystemAdmin ? (
                <section className="panel admin-governance">
                    <h2>Admin Governance</h2>
                    <p>
                        Manage RBAC, datasource registry entries, credentials, and connection tests.
                    </p>

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

                        <section className="panel datasource-admin">
                            <h3>Datasource Management</h3>
                            <div className="row">
                                <button type="button" onClick={handlePrepareNewDatasource}>
                                    New Datasource
                                </button>
                                <button
                                    type="button"
                                    className="danger-button"
                                    disabled={deletingDatasource || !selectedManagedDatasource}
                                    onClick={() => void handleDeleteManagedDatasource()}
                                >
                                    {deletingDatasource ? 'Deleting...' : 'Delete Datasource'}
                                </button>
                                <button
                                    type="button"
                                    disabled={reencryptingCredentials}
                                    onClick={() => void handleReencryptCredentials()}
                                >
                                    {reencryptingCredentials
                                        ? 'Re-encrypting...'
                                        : 'Re-encrypt Credentials'}
                                </button>
                            </div>

                            <label htmlFor="managed-datasource-select">Select Datasource</label>
                            <select
                                id="managed-datasource-select"
                                value={selectedManagedDatasourceId}
                                onChange={(event) => {
                                    const nextDatasourceId = event.target.value;
                                    if (!nextDatasourceId) {
                                        handlePrepareNewDatasource();
                                        return;
                                    }

                                    setSelectedManagedDatasourceId(nextDatasourceId);
                                    setAdminError('');
                                    setAdminSuccess('');
                                }}
                            >
                                <option value="">Create new datasource</option>
                                {adminManagedDatasources.map((datasource) => (
                                    <option key={datasource.id} value={datasource.id}>
                                        {datasource.name} ({datasource.engine})
                                    </option>
                                ))}
                            </select>
                            <p className="muted-id">
                                {selectedManagedDatasource
                                    ? `Editing ${selectedManagedDatasource.id}`
                                    : 'Creating a new datasource entry.'}
                            </p>

                            <form className="stack-form" onSubmit={handleSaveManagedDatasource}>
                                <label htmlFor="managed-name">Name</label>
                                <input
                                    id="managed-name"
                                    value={managedDatasourceForm.name}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            name: event.target.value
                                        }))
                                    }
                                    required
                                />

                                <label htmlFor="managed-engine">Engine</label>
                                <select
                                    id="managed-engine"
                                    value={managedDatasourceForm.engine}
                                    disabled={Boolean(selectedManagedDatasource)}
                                    onChange={(event) => {
                                        const nextEngine = event.target.value as DatasourceEngine;
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            engine: nextEngine,
                                            port: defaultPortByEngine[nextEngine].toString()
                                        }));
                                    }}
                                >
                                    <option value="POSTGRESQL">PostgreSQL</option>
                                    <option value="MYSQL">MySQL</option>
                                    <option value="MARIADB">MariaDB</option>
                                    <option value="TRINO">Trino</option>
                                    <option value="STARROCKS">StarRocks</option>
                                    <option value="VERTICA">Vertica</option>
                                </select>

                                <label htmlFor="managed-host">Host</label>
                                <input
                                    id="managed-host"
                                    value={managedDatasourceForm.host}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            host: event.target.value
                                        }))
                                    }
                                    required
                                />

                                <label htmlFor="managed-port">Port</label>
                                <input
                                    id="managed-port"
                                    type="number"
                                    min={1}
                                    max={65535}
                                    value={managedDatasourceForm.port}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            port: event.target.value
                                        }))
                                    }
                                    required
                                />

                                <label htmlFor="managed-database">Database (optional)</label>
                                <input
                                    id="managed-database"
                                    value={managedDatasourceForm.database}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            database: event.target.value
                                        }))
                                    }
                                    placeholder="schema or database"
                                />

                                <label htmlFor="managed-driver">Driver</label>
                                <select
                                    id="managed-driver"
                                    value={managedDatasourceForm.driverId}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            driverId: event.target.value
                                        }))
                                    }
                                >
                                    <option value="">Select driver</option>
                                    {driversForFormEngine.map((driver) => (
                                        <option key={driver.driverId} value={driver.driverId}>
                                            {driver.driverId} ({driver.source}){' '}
                                            {driver.available ? '' : '[unavailable]'}
                                        </option>
                                    ))}
                                </select>
                                {selectedDriverForForm ? (
                                    <p
                                        className={
                                            selectedDriverForForm.available
                                                ? 'form-success'
                                                : 'form-error'
                                        }
                                    >
                                        {selectedDriverForForm.description}.{' '}
                                        {selectedDriverForForm.message}
                                    </p>
                                ) : null}

                                <h4>Pool Settings</h4>
                                <label htmlFor="managed-pool-max">Maximum Pool Size</label>
                                <input
                                    id="managed-pool-max"
                                    type="number"
                                    min={1}
                                    value={managedDatasourceForm.maximumPoolSize}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            maximumPoolSize: event.target.value
                                        }))
                                    }
                                    required
                                />

                                <label htmlFor="managed-pool-min">Minimum Idle</label>
                                <input
                                    id="managed-pool-min"
                                    type="number"
                                    min={1}
                                    value={managedDatasourceForm.minimumIdle}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            minimumIdle: event.target.value
                                        }))
                                    }
                                    required
                                />

                                <label htmlFor="managed-pool-connection-timeout">
                                    Connection Timeout (ms)
                                </label>
                                <input
                                    id="managed-pool-connection-timeout"
                                    type="number"
                                    min={1}
                                    value={managedDatasourceForm.connectionTimeoutMs}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            connectionTimeoutMs: event.target.value
                                        }))
                                    }
                                    required
                                />

                                <label htmlFor="managed-pool-idle-timeout">Idle Timeout (ms)</label>
                                <input
                                    id="managed-pool-idle-timeout"
                                    type="number"
                                    min={1}
                                    value={managedDatasourceForm.idleTimeoutMs}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            idleTimeoutMs: event.target.value
                                        }))
                                    }
                                    required
                                />

                                <h4>TLS Settings</h4>
                                <label htmlFor="managed-tls-mode">TLS Mode</label>
                                <select
                                    id="managed-tls-mode"
                                    value={managedDatasourceForm.tlsMode}
                                    onChange={(event) =>
                                        setManagedDatasourceForm((current) => ({
                                            ...current,
                                            tlsMode: event.target.value as TlsMode
                                        }))
                                    }
                                >
                                    <option value="DISABLE">Disable</option>
                                    <option value="REQUIRE">Require</option>
                                </select>
                                <label className="checkbox-row">
                                    <input
                                        type="checkbox"
                                        checked={managedDatasourceForm.verifyServerCertificate}
                                        onChange={(event) =>
                                            setManagedDatasourceForm((current) => ({
                                                ...current,
                                                verifyServerCertificate: event.target.checked
                                            }))
                                        }
                                    />
                                    <span>Verify server certificate</span>
                                </label>
                                <label className="checkbox-row">
                                    <input
                                        type="checkbox"
                                        checked={managedDatasourceForm.allowSelfSigned}
                                        onChange={(event) =>
                                            setManagedDatasourceForm((current) => ({
                                                ...current,
                                                allowSelfSigned: event.target.checked
                                            }))
                                        }
                                    />
                                    <span>Allow self-signed certificates</span>
                                </label>

                                <button type="submit" disabled={savingDatasource}>
                                    {savingDatasource
                                        ? 'Saving...'
                                        : selectedManagedDatasource
                                          ? 'Update Datasource'
                                          : 'Create Datasource'}
                                </button>
                            </form>

                            {selectedManagedDatasource ? (
                                <div className="managed-datasource-actions">
                                    <h4>Credential Profiles</h4>
                                    <ul className="credentials-list">
                                        {selectedManagedDatasource.credentialProfiles.map(
                                            (profile) => (
                                                <li
                                                    key={`${selectedManagedDatasource.id}-${profile.profileId}`}
                                                >
                                                    <strong>{profile.profileId}</strong> (
                                                    {profile.username}) key:{' '}
                                                    {profile.encryptionKeyId}
                                                </li>
                                            )
                                        )}
                                    </ul>

                                    <form
                                        className="stack-form"
                                        onSubmit={handleSaveCredentialProfile}
                                    >
                                        <label htmlFor="credential-existing">
                                            Existing Profile
                                        </label>
                                        <select
                                            id="credential-existing"
                                            value={
                                                selectedManagedDatasource.credentialProfiles.some(
                                                    (profile) =>
                                                        profile.profileId ===
                                                        credentialProfileIdInput.trim()
                                                )
                                                    ? credentialProfileIdInput
                                                    : ''
                                            }
                                            onChange={(event) => {
                                                const selectedProfileId = event.target.value;
                                                if (!selectedProfileId) {
                                                    return;
                                                }

                                                setCredentialProfileIdInput(selectedProfileId);
                                                setSelectedCredentialProfileForTest(
                                                    selectedProfileId
                                                );
                                            }}
                                        >
                                            <option value="">Select existing profile</option>
                                            {selectedManagedDatasource.credentialProfiles.map(
                                                (profile) => (
                                                    <option
                                                        key={profile.profileId}
                                                        value={profile.profileId}
                                                    >
                                                        {profile.profileId}
                                                    </option>
                                                )
                                            )}
                                        </select>

                                        <label htmlFor="credential-profile-id">Profile ID</label>
                                        <input
                                            id="credential-profile-id"
                                            value={credentialProfileIdInput}
                                            onChange={(event) =>
                                                setCredentialProfileIdInput(event.target.value)
                                            }
                                            placeholder="admin-ro"
                                            required
                                        />

                                        <label htmlFor="credential-username">Username</label>
                                        <input
                                            id="credential-username"
                                            value={credentialUsernameInput}
                                            onChange={(event) =>
                                                setCredentialUsernameInput(event.target.value)
                                            }
                                            required
                                        />

                                        <label htmlFor="credential-password">Password</label>
                                        <input
                                            id="credential-password"
                                            type="password"
                                            value={credentialPasswordInput}
                                            onChange={(event) =>
                                                setCredentialPasswordInput(event.target.value)
                                            }
                                            required
                                        />

                                        <label htmlFor="credential-description">Description</label>
                                        <input
                                            id="credential-description"
                                            value={credentialDescriptionInput}
                                            onChange={(event) =>
                                                setCredentialDescriptionInput(event.target.value)
                                            }
                                            placeholder="Readonly profile for analysts"
                                        />

                                        <button type="submit" disabled={savingCredentialProfile}>
                                            {savingCredentialProfile
                                                ? 'Saving...'
                                                : 'Save Credential Profile'}
                                        </button>
                                    </form>

                                    <h4>Test Connection</h4>
                                    <form className="stack-form" onSubmit={handleTestConnection}>
                                        <label htmlFor="test-credential-profile">
                                            Credential Profile
                                        </label>
                                        <select
                                            id="test-credential-profile"
                                            value={selectedCredentialProfileForTest}
                                            onChange={(event) =>
                                                setSelectedCredentialProfileForTest(
                                                    event.target.value
                                                )
                                            }
                                        >
                                            <option value="">Select credential profile</option>
                                            {selectedManagedDatasource.credentialProfiles.map(
                                                (profile) => (
                                                    <option
                                                        key={profile.profileId}
                                                        value={profile.profileId}
                                                    >
                                                        {profile.profileId}
                                                    </option>
                                                )
                                            )}
                                        </select>

                                        <label htmlFor="test-validation-query">
                                            Validation Query
                                        </label>
                                        <input
                                            id="test-validation-query"
                                            value={validationQueryInput}
                                            onChange={(event) =>
                                                setValidationQueryInput(event.target.value)
                                            }
                                            placeholder="SELECT 1"
                                        />

                                        <label className="checkbox-row">
                                            <input
                                                type="checkbox"
                                                checked={overrideTlsForTest}
                                                onChange={(event) =>
                                                    setOverrideTlsForTest(event.target.checked)
                                                }
                                            />
                                            <span>
                                                Override datasource TLS settings for this test
                                            </span>
                                        </label>

                                        {overrideTlsForTest ? (
                                            <>
                                                <label htmlFor="test-tls-mode">TLS Mode</label>
                                                <select
                                                    id="test-tls-mode"
                                                    value={testTlsMode}
                                                    onChange={(event) =>
                                                        setTestTlsMode(
                                                            event.target.value as TlsMode
                                                        )
                                                    }
                                                >
                                                    <option value="DISABLE">Disable</option>
                                                    <option value="REQUIRE">Require</option>
                                                </select>

                                                <label className="checkbox-row">
                                                    <input
                                                        type="checkbox"
                                                        checked={testVerifyServerCertificate}
                                                        onChange={(event) =>
                                                            setTestVerifyServerCertificate(
                                                                event.target.checked
                                                            )
                                                        }
                                                    />
                                                    <span>Verify server certificate</span>
                                                </label>

                                                <label className="checkbox-row">
                                                    <input
                                                        type="checkbox"
                                                        checked={testAllowSelfSigned}
                                                        onChange={(event) =>
                                                            setTestAllowSelfSigned(
                                                                event.target.checked
                                                            )
                                                        }
                                                    />
                                                    <span>Allow self-signed certificates</span>
                                                </label>
                                            </>
                                        ) : null}

                                        <button type="submit" disabled={testingConnection}>
                                            {testingConnection
                                                ? 'Testing...'
                                                : 'Run Test Connection'}
                                        </button>
                                    </form>

                                    {testConnectionMessage ? (
                                        <p
                                            className={
                                                testConnectionOutcome === 'success'
                                                    ? 'form-success'
                                                    : 'form-error'
                                            }
                                            role="alert"
                                        >
                                            {testConnectionMessage}
                                        </p>
                                    ) : null}
                                </div>
                            ) : (
                                <p className="muted-id">
                                    Save a datasource first to manage credential profiles and run
                                    connection tests.
                                </p>
                            )}
                        </section>
                    </div>
                </section>
            ) : null}
        </AppShell>
    );
}
