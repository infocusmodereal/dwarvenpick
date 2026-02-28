import Editor, { BeforeMount, loader, OnMount } from '@monaco-editor/react';
import {
    FormEvent,
    type CSSProperties,
    type KeyboardEvent as ReactKeyboardEvent,
    type PointerEvent as ReactPointerEvent,
    type ReactNode,
    useCallback,
    useEffect,
    useMemo,
    useRef,
    useState
} from 'react';
import * as MonacoModule from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/editor/contrib/suggest/browser/suggestController';
import 'monaco-editor/esm/vs/editor/contrib/snippet/browser/snippetController2';
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution';
import { format as formatSql } from 'sql-formatter';
import type { editor as MonacoEditorNamespace } from 'monaco-editor';
import { useNavigate } from 'react-router-dom';
import AppShell from '../components/AppShell';
import { MoonIcon, SunIcon } from '../components/ThemeIcons';
import { statementAtCursor } from '../sql/statementSplitter';
import { useTheme } from '../theme/ThemeContext';
import type {
    AccessAdminMode,
    AdminSubsection,
    AdminUserResponse,
    ApiErrorResponse,
    AuditEventResponse,
    AuthMethodsResponse,
    AutocompleteDiagnostics,
    CatalogDatasourceResponse,
    ConnectionAuthentication,
    ConnectionEditorMode,
    ConnectionType,
    CsrfTokenResponse,
    CurrentUserResponse,
    DatasourceAccessResponse,
    DatasourceEngine,
    DatasourceHealthState,
    DatasourceSchemaBrowserResponse,
    DriverDescriptorResponse,
    EditorCursorLegend,
    GroupAdminMode,
    GroupResponse,
    ManagedDatasourceFormState,
    ManagedCredentialProfileResponse,
    ManagedDatasourceResponse,
    MavenDriverPreset,
    PersistentWorkspaceTab,
    QueryExecutionResponse,
    QueryExecutionStatusResponse,
    QueryHistoryEntryResponse,
    QueryResultsResponse,
    QueryRunMode,
    QueryStatusEventResponse,
    ReencryptCredentialsResponse,
    ResultSortDirection,
    ResultSortState,
    SnippetResponse,
    SystemHealthResponse,
    TestConnectionResponse,
    TlsMode,
    UserAdminMode,
    VersionResponse,
    WorkspaceSection,
    WorkspaceTab
} from '../workbench/types';
import {
    builtInAuditActions,
    builtInAuditOutcomes,
    defaultPoolSettings,
    defaultPortByEngine,
    defaultTlsSettings,
    firstPageToken,
    protectedGroupIds,
    queryStatusPollingIntervalMs,
    queryStatusPollingMaxAttempts,
    resultRowHeightPx,
    resultViewportHeightPx,
    sqlKeywordSuggestions,
    workspaceTabsStorageKey
} from '../workbench/constants';
import {
    adminIdentifierPattern,
    compareResultValues,
    formatExecutionDuration,
    formatExecutionTimestamp,
    isTerminalExecutionStatus,
    isValidEmailAddress,
    normalizeAdminIdentifier
} from '../workbench/utils';
import {
    buildJdbcUrlPreview,
    optionsToInput,
    parseOptionsInput
} from '../workbench/connectionUtils';
import {
    EditorTabCloseIcon,
    EditorTabMenuIcon,
    ExplorerIcon,
    ExplorerInsertIcon,
    ExplorerRefreshIcon,
    IconButton,
    IconGlyph,
    InfoHint,
    RailIcon
} from '../workbench/components/WorkbenchIcons';
import AuditEventsSection from '../workbench/sections/AuditEventsSection';
import QueryHistorySection from '../workbench/sections/QueryHistorySection';
import SnippetsSection from '../workbench/sections/SnippetsSection';
import SystemHealthSection from '../workbench/sections/SystemHealthSection';
import {
    chevronDownIcon,
    chevronRightIcon,
    resolveDatasourceIcon,
    sortDownIcon,
    sortNeutralIcon,
    sortUpIcon
} from '../workbench/icons';

loader.config({ monaco: MonacoModule });

const workbenchResultsSizeStorageKey = 'dwarvenpick.workbench.resultsSizePx';

const parsePxValue = (rawValue: string, fallback: number): number => {
    const trimmed = rawValue.trim();
    if (!trimmed) {
        return fallback;
    }

    const match = trimmed.match(/^([0-9.]+)px$/i);
    const parsed = Number(match ? match[1] : trimmed);
    return Number.isFinite(parsed) ? parsed : fallback;
};

const buildInitialAutocompleteDiagnostics = (): AutocompleteDiagnostics => ({
    enabled: false,
    monacoReady: false,
    editorMounted: false,
    modelLanguageId: '',
    availableLanguageIds: [],
    registeredLanguageIds: [],
    suggestionSeedCount: 0,
    triggerCount: 0,
    providerInvocationCount: 0,
    lastSuggestionCount: 0,
    lastTriggerSource: '',
    lastTriggerAt: '',
    lastInvocationAt: '',
    lastError: ''
});

const buildBlankDatasourceForm = (
    engine: DatasourceEngine = 'POSTGRESQL'
): ManagedDatasourceFormState => ({
    name: '',
    engine,
    connectionType: 'HOST_PORT',
    host: 'localhost',
    port: defaultPortByEngine[engine].toString(),
    database: '',
    jdbcUrl: '',
    optionsInput: '',
    authentication: 'USER_PASSWORD',
    credentialProfileId: 'admin-ro',
    credentialUsername: '',
    credentialPassword: '',
    credentialDescription: '',
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
    connectionType: datasource.options.jdbcUrl ? 'JDBC_URL' : 'HOST_PORT',
    host: datasource.host,
    port: datasource.port.toString(),
    database: datasource.database ?? '',
    jdbcUrl: datasource.options.jdbcUrl ?? '',
    optionsInput: optionsToInput(datasource.options),
    authentication: datasource.credentialProfiles[0]?.username ? 'USER_PASSWORD' : 'NO_AUTH',
    credentialProfileId: datasource.credentialProfiles[0]?.profileId ?? 'admin-ro',
    credentialUsername: datasource.credentialProfiles[0]?.username ?? '',
    credentialPassword: '',
    credentialDescription: datasource.credentialProfiles[0]?.description ?? '',
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
    queryText = 'SELECT 1;'
): WorkspaceTab => ({
    id: createTabId(),
    title,
    datasourceId,
    schema: '',
    requestedCredentialProfile: '',
    queryText,
    isExecuting: false,
    statusMessage: '',
    errorMessage: '',
    lastRunKind: 'query',
    executionId: '',
    executionStatus: '',
    queryHash: '',
    resultColumns: [],
    resultRows: [],
    nextPageToken: '',
    currentPageToken: '',
    previousPageTokens: [],
    rowLimitReached: false,
    submittedAt: '',
    startedAt: '',
    completedAt: '',
    rowCount: 0,
    columnCount: 0,
    maxRowsPerQuery: 0,
    maxRuntimeSeconds: 0,
    credentialProfile: ''
});

const toPersistentTab = (tab: WorkspaceTab): PersistentWorkspaceTab => ({
    id: tab.id,
    title: tab.title,
    datasourceId: tab.datasourceId,
    schema: tab.schema,
    queryText: tab.queryText
});

const DetailsSummary = ({ children }: { children: ReactNode }) => (
    <summary className="managed-advanced-summary">
        <span className="managed-advanced-summary-icon" aria-hidden>
            <span
                className="managed-advanced-summary-icon-collapsed"
                dangerouslySetInnerHTML={{ __html: chevronRightIcon }}
            />
            <span
                className="managed-advanced-summary-icon-expanded"
                dangerouslySetInnerHTML={{ __html: chevronDownIcon }}
            />
        </span>
        <span className="managed-advanced-summary-label">{children}</span>
    </summary>
);

const ResultSortIcon = ({ direction }: { direction: ResultSortDirection | null }) => (
    <span
        className={`result-sort-icon ${direction ? `is-${direction}` : 'is-none'}`}
        aria-hidden
        dangerouslySetInnerHTML={{
            __html:
                direction === 'asc'
                    ? sortUpIcon
                    : direction === 'desc'
                      ? sortDownIcon
                      : sortNeutralIcon
        }}
    />
);

const ChevronIcon = ({ expanded }: { expanded: boolean }) => (
    <span
        className="explorer-icon-glyph"
        aria-hidden
        dangerouslySetInnerHTML={{
            __html: expanded ? chevronDownIcon : chevronRightIcon
        }}
    />
);

const mavenDriverPresetDetails: Record<
    MavenDriverPreset,
    { groupId: string; artifactId: string; driverClass: string }
> = {
    POSTGRESQL: {
        groupId: 'org.postgresql',
        artifactId: 'postgresql',
        driverClass: 'org.postgresql.Driver'
    },
    MYSQL: {
        groupId: 'com.mysql',
        artifactId: 'mysql-connector-j',
        driverClass: 'com.mysql.cj.jdbc.Driver'
    },
    MARIADB: {
        groupId: 'org.mariadb.jdbc',
        artifactId: 'mariadb-java-client',
        driverClass: 'org.mariadb.jdbc.Driver'
    },
    TRINO: {
        groupId: 'io.trino',
        artifactId: 'trino-jdbc',
        driverClass: 'io.trino.jdbc.TrinoDriver'
    },
    STARROCKS_MYSQL: {
        groupId: 'com.mysql',
        artifactId: 'mysql-connector-j',
        driverClass: 'com.mysql.cj.jdbc.Driver'
    },
    STARROCKS_MARIADB: {
        groupId: 'org.mariadb.jdbc',
        artifactId: 'mariadb-java-client',
        driverClass: 'org.mariadb.jdbc.Driver'
    }
};

export default function WorkspacePage() {
    const navigate = useNavigate();
    const { theme, toggleTheme } = useTheme();

    const [currentUser, setCurrentUser] = useState<CurrentUserResponse | null>(null);
    const [appVersion, setAppVersion] = useState('unknown');
    const [authMethods, setAuthMethods] = useState<string[]>([]);
    const [visibleDatasources, setVisibleDatasources] = useState<CatalogDatasourceResponse[]>([]);
    const [workspaceError, setWorkspaceError] = useState('');
    const [loadingWorkspace, setLoadingWorkspace] = useState(true);
    const [workspaceTabs, setWorkspaceTabs] = useState<WorkspaceTab[]>([]);
    const [activeTabId, setActiveTabId] = useState('');
    const [tabsHydrated, setTabsHydrated] = useState(false);
    const editorRef = useRef<MonacoEditorNamespace.IStandaloneCodeEditor | null>(null);
    const monacoRef = useRef<typeof import('monaco-editor') | null>(null);
    const completionProviderRef = useRef<{ dispose: () => void } | null>(null);
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
    const [adminUsers, setAdminUsers] = useState<AdminUserResponse[]>([]);
    const [adminError, setAdminError] = useState('');
    const [adminSuccess, setAdminSuccess] = useState('');
    const [creatingLocalUser, setCreatingLocalUser] = useState(false);
    const [resettingLocalUser, setResettingLocalUser] = useState<string | null>(null);
    const [editingUserDisplayName, setEditingUserDisplayName] = useState<string | null>(null);
    const [editingUserDisplayNameDraft, setEditingUserDisplayNameDraft] = useState('');
    const [savingUserDisplayName, setSavingUserDisplayName] = useState<string | null>(null);
    const [uploadDriverClassInput, setUploadDriverClassInput] = useState('');
    const [uploadDriverDescriptionInput, setUploadDriverDescriptionInput] = useState('');
    const [uploadDriverJarFile, setUploadDriverJarFile] = useState<File | null>(null);
    const [uploadingDriver, setUploadingDriver] = useState(false);
    const [mavenDriverPreset, setMavenDriverPreset] = useState<MavenDriverPreset>('POSTGRESQL');
    const [mavenDriverVersionInput, setMavenDriverVersionInput] = useState('');
    const [mavenDriverVersions, setMavenDriverVersions] = useState<string[]>([]);
    const [loadingMavenDriverVersions, setLoadingMavenDriverVersions] = useState(false);
    const [mavenDriverVersionsError, setMavenDriverVersionsError] = useState('');
    const [installingMavenDriver, setInstallingMavenDriver] = useState(false);

    const [groupNameInput, setGroupNameInput] = useState('');
    const [groupDescriptionInput, setGroupDescriptionInput] = useState('');
    const [groupDescriptionDrafts, setGroupDescriptionDrafts] = useState<Record<string, string>>(
        {}
    );
    const [memberDrafts, setMemberDrafts] = useState<Record<string, string>>({});
    const [localUserUsernameInput, setLocalUserUsernameInput] = useState('');
    const [localUserDisplayNameInput, setLocalUserDisplayNameInput] = useState('');
    const [localUserEmailInput, setLocalUserEmailInput] = useState('');
    const [localUserPasswordInput, setLocalUserPasswordInput] = useState('');
    const [localUserTemporaryPassword, setLocalUserTemporaryPassword] = useState(true);
    const [localUserSystemAdmin, setLocalUserSystemAdmin] = useState(false);

    const [selectedGroupId, setSelectedGroupId] = useState('');
    const [selectedDatasourceForAccess, setSelectedDatasourceForAccess] = useState('');
    const [canQuery, setCanQuery] = useState(true);
    const [canExport, setCanExport] = useState(false);
    const [readOnly, setReadOnly] = useState(true);
    const [credentialProfile, setCredentialProfile] = useState('');
    const [maxRowsPerQuery, setMaxRowsPerQuery] = useState('');
    const [maxRuntimeSeconds, setMaxRuntimeSeconds] = useState('');
    const [concurrencyLimit, setConcurrencyLimit] = useState('');
    const [savingAccess, setSavingAccess] = useState(false);
    const [selectedManagedDatasourceId, setSelectedManagedDatasourceId] = useState('');
    const [connectionEditorMode, setConnectionEditorMode] = useState<ConnectionEditorMode>('list');
    const [managedDatasourceForm, setManagedDatasourceForm] = useState<ManagedDatasourceFormState>(
        buildBlankDatasourceForm()
    );
    const [savingDatasource, setSavingDatasource] = useState(false);
    const [deletingDatasource, setDeletingDatasource] = useState(false);
    const [credentialProfileIdInput, setCredentialProfileIdInput] = useState('admin-ro');
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
    const [tlsCaCertificatePemInput, setTlsCaCertificatePemInput] = useState<string | null>(null);
    const [tlsCaCertificateFileName, setTlsCaCertificateFileName] = useState('');
    const [tlsClientCertificatePemInput, setTlsClientCertificatePemInput] = useState<string | null>(
        null
    );
    const [tlsClientCertificateFileName, setTlsClientCertificateFileName] = useState('');
    const [tlsClientKeyPemInput, setTlsClientKeyPemInput] = useState<string | null>(null);
    const [tlsClientKeyFileName, setTlsClientKeyFileName] = useState('');
    const [testingConnection, setTestingConnection] = useState(false);
    const [testConnectionMessage, setTestConnectionMessage] = useState('');
    const [testConnectionOutcome, setTestConnectionOutcome] = useState<'success' | 'failure' | ''>(
        ''
    );
    const [exportIncludeHeaders, setExportIncludeHeaders] = useState(true);
    const [exportingCsv, setExportingCsv] = useState(false);
    const [showExportMenu, setShowExportMenu] = useState(false);
    const [copyFeedback, setCopyFeedback] = useState('');
    const [resultsPageSize, setResultsPageSize] = useState(500);
    const [resultSortState, setResultSortState] = useState<ResultSortState>(null);
    const [resultGridScrollTop, setResultGridScrollTop] = useState(0);
    const [workbenchResultsSizePx, setWorkbenchResultsSizePx] = useState<number | null>(() => {
        try {
            const raw = window.localStorage.getItem(workbenchResultsSizeStorageKey);
            if (!raw) {
                return null;
            }

            const parsed = Number(raw);
            return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
        } catch {
            return null;
        }
    });
    const workbenchGridRef = useRef<HTMLDivElement | null>(null);
    const resultsSectionRef = useRef<HTMLElement | null>(null);
    const resultsResizeStateRef = useRef<{
        pointerId: number;
        startY: number;
        startHeight: number;
        editorMinHeight: number;
        resultsMinHeight: number;
    } | null>(null);

    const [queryHistoryEntries, setQueryHistoryEntries] = useState<QueryHistoryEntryResponse[]>([]);
    const [loadingQueryHistory, setLoadingQueryHistory] = useState(false);
    const [historyDatasourceFilter, setHistoryDatasourceFilter] = useState('');
    const [historyStatusFilter, setHistoryStatusFilter] = useState('');
    const [historyFromFilter, setHistoryFromFilter] = useState('');
    const [historyToFilter, setHistoryToFilter] = useState('');
    const [historySortOrder, setHistorySortOrder] = useState<'newest' | 'oldest'>('newest');

    const [auditEvents, setAuditEvents] = useState<AuditEventResponse[]>([]);
    const [loadingAuditEvents, setLoadingAuditEvents] = useState(false);
    const [auditTypeFilter, setAuditTypeFilter] = useState('');
    const [auditActorFilter, setAuditActorFilter] = useState('');
    const [auditOutcomeFilter, setAuditOutcomeFilter] = useState('');
    const [auditFromFilter, setAuditFromFilter] = useState('');
    const [auditToFilter, setAuditToFilter] = useState('');
    const [auditSortOrder, setAuditSortOrder] = useState<'newest' | 'oldest'>('newest');

    const [systemHealthDatasourceId, setSystemHealthDatasourceId] = useState('');
    const [systemHealthCredentialProfile, setSystemHealthCredentialProfile] = useState('');
    const [systemHealthResponse, setSystemHealthResponse] = useState<SystemHealthResponse | null>(
        null
    );
    const [loadingSystemHealth, setLoadingSystemHealth] = useState(false);
    const [systemHealthError, setSystemHealthError] = useState('');

    const [schemaBrowser, setSchemaBrowser] = useState<DatasourceSchemaBrowserResponse | null>(
        null
    );
    const [loadingSchemaBrowser, setLoadingSchemaBrowser] = useState(false);
    const [schemaBrowserError, setSchemaBrowserError] = useState('');

    const [snippets, setSnippets] = useState<SnippetResponse[]>([]);
    const [loadingSnippets, setLoadingSnippets] = useState(false);
    const [snippetScope, setSnippetScope] = useState<'all' | 'personal' | 'group'>('all');
    const [snippetTitleInput, setSnippetTitleInput] = useState('');
    const [snippetGroupInput, setSnippetGroupInput] = useState('');
    const [savingSnippet, setSavingSnippet] = useState(false);
    const [snippetError, setSnippetError] = useState('');
    const [activeSection, setActiveSection] = useState<WorkspaceSection>('workbench');
    const [activeAdminSubsection, setActiveAdminSubsection] = useState<AdminSubsection>('groups');
    const [groupAdminMode, setGroupAdminMode] = useState<GroupAdminMode>('list');
    const [selectedGroupForEdit, setSelectedGroupForEdit] = useState('');
    const [userAdminMode, setUserAdminMode] = useState<UserAdminMode>('list');
    const [accessAdminMode, setAccessAdminMode] = useState<AccessAdminMode>('list');
    const [showSchemaBrowser, setShowSchemaBrowser] = useState(true);
    const [leftRailCollapsed, setLeftRailCollapsed] = useState(false);
    const [showVersionInfo, setShowVersionInfo] = useState(false);
    const [collapsedAdminSubmenuOpen, setCollapsedAdminSubmenuOpen] = useState(false);
    const [leftRailUserMenuOpen, setLeftRailUserMenuOpen] = useState(false);
    const leftRailUserMenuRef = useRef<HTMLDivElement | null>(null);
    const versionInfoRef = useRef<HTMLDivElement | null>(null);
    const collapsedAdminSubmenuRef = useRef<HTMLDivElement | null>(null);
    const collapsedAdminAnchorRef = useRef<HTMLDivElement | null>(null);
    const [activeTabMenuOpen, setActiveTabMenuOpen] = useState(false);
    const activeTabMenuRef = useRef<HTMLDivElement | null>(null);
    const activeTabMenuAnchorRef = useRef<HTMLDivElement | null>(null);
    const [activeTabMenuPosition, setActiveTabMenuPosition] = useState<{
        top: number;
        left: number;
    } | null>(null);
    const editorShortcutsRef = useRef<HTMLDivElement | null>(null);
    const exportMenuRef = useRef<HTMLDivElement | null>(null);
    const [expandedExplorerDatasources, setExpandedExplorerDatasources] = useState<
        Record<string, boolean>
    >({});
    const [expandedExplorerSchemas, setExpandedExplorerSchemas] = useState<Record<string, boolean>>(
        {}
    );
    const [expandedExplorerTables, setExpandedExplorerTables] = useState<Record<string, boolean>>(
        {}
    );
    const [selectedExplorerNode, setSelectedExplorerNode] = useState('');
    const [monacoReady, setMonacoReady] = useState(false);
    const [monacoLoadTimedOut, setMonacoLoadTimedOut] = useState(false);
    const [, setAutocompleteDiagnostics] = useState<AutocompleteDiagnostics>(() =>
        buildInitialAutocompleteDiagnostics()
    );
    const [editorRenderKey, setEditorRenderKey] = useState(0);
    const [showEditorShortcuts, setShowEditorShortcuts] = useState(false);
    const [datasourceHealthById, setDatasourceHealthById] = useState<
        Record<string, DatasourceHealthState>
    >({});
    const [editorCursorLegend, setEditorCursorLegend] = useState<EditorCursorLegend>({
        line: 1,
        column: 1,
        position: 1,
        selectedChars: 0,
        selectedLines: 0
    });

    const isSystemAdmin = currentUser?.roles.includes('SYSTEM_ADMIN') ?? false;
    const localAuthEnabled = authMethods.includes('local');

    useEffect(() => {
        try {
            if (workbenchResultsSizePx === null) {
                window.localStorage.removeItem(workbenchResultsSizeStorageKey);
                return;
            }

            window.localStorage.setItem(
                workbenchResultsSizeStorageKey,
                workbenchResultsSizePx.toString()
            );
        } catch {
            // Ignore persistence failures.
        }
    }, [workbenchResultsSizePx]);

    const handleResultsResizerPointerDown = useCallback(
        (event: ReactPointerEvent<HTMLDivElement>) => {
            if (event.button !== 0) {
                return;
            }

            const grid = workbenchGridRef.current;
            const results = resultsSectionRef.current;
            if (!grid || !results) {
                return;
            }

            const computed = window.getComputedStyle(grid);
            const editorMinHeight = parsePxValue(
                computed.getPropertyValue('--workbench-editor-min-height'),
                340
            );
            const resultsMinHeight = parsePxValue(
                computed.getPropertyValue('--workbench-results-min-height'),
                220
            );
            const startHeight = results.getBoundingClientRect().height;

            resultsResizeStateRef.current = {
                pointerId: event.pointerId,
                startY: event.clientY,
                startHeight,
                editorMinHeight,
                resultsMinHeight
            };

            event.currentTarget.setPointerCapture(event.pointerId);
            document.body.style.userSelect = 'none';
        },
        []
    );

    const handleResultsResizerPointerMove = useCallback(
        (event: ReactPointerEvent<HTMLDivElement>) => {
            const state = resultsResizeStateRef.current;
            if (!state || state.pointerId !== event.pointerId) {
                return;
            }

            const grid = workbenchGridRef.current;
            if (!grid) {
                return;
            }

            const containerHeight = grid.getBoundingClientRect().height;
            const maxHeight = Math.max(
                state.resultsMinHeight,
                Math.floor(containerHeight - state.editorMinHeight)
            );

            const deltaY = event.clientY - state.startY;
            const nextHeight = Math.round(state.startHeight - deltaY);
            const clamped = Math.min(maxHeight, Math.max(state.resultsMinHeight, nextHeight));
            setWorkbenchResultsSizePx(clamped);
        },
        []
    );

    const stopResultsResize = useCallback((pointerId: number) => {
        const state = resultsResizeStateRef.current;
        if (!state || state.pointerId !== pointerId) {
            return;
        }

        resultsResizeStateRef.current = null;
        document.body.style.userSelect = '';
    }, []);

    const handleResultsResizerPointerUp = useCallback(
        (event: ReactPointerEvent<HTMLDivElement>) => {
            stopResultsResize(event.pointerId);
        },
        [stopResultsResize]
    );

    const handleResultsResizerPointerCancel = useCallback(
        (event: ReactPointerEvent<HTMLDivElement>) => {
            stopResultsResize(event.pointerId);
        },
        [stopResultsResize]
    );

    const handleResultsResizerReset = useCallback(() => {
        setWorkbenchResultsSizePx(null);
    }, []);

    const handleResultsResizerKeyDown = useCallback(
        (event: ReactKeyboardEvent<HTMLDivElement>) => {
            if (
                event.key !== 'ArrowUp' &&
                event.key !== 'ArrowDown' &&
                event.key !== 'Home' &&
                event.key !== 'End'
            ) {
                return;
            }

            const grid = workbenchGridRef.current;
            const results = resultsSectionRef.current;
            if (!grid || !results) {
                return;
            }

            const computed = window.getComputedStyle(grid);
            const editorMinHeight = parsePxValue(
                computed.getPropertyValue('--workbench-editor-min-height'),
                340
            );
            const resultsMinHeight = parsePxValue(
                computed.getPropertyValue('--workbench-results-min-height'),
                220
            );
            const containerHeight = grid.getBoundingClientRect().height;
            const maxHeight = Math.max(
                resultsMinHeight,
                Math.floor(containerHeight - editorMinHeight)
            );

            const currentHeight = workbenchResultsSizePx ?? results.getBoundingClientRect().height;
            const step = 24;
            let nextHeight = currentHeight;
            if (event.key === 'ArrowUp') {
                nextHeight = currentHeight + step;
            } else if (event.key === 'ArrowDown') {
                nextHeight = currentHeight - step;
            } else if (event.key === 'Home') {
                nextHeight = resultsMinHeight;
            } else if (event.key === 'End') {
                nextHeight = maxHeight;
            }

            const clamped = Math.min(maxHeight, Math.max(resultsMinHeight, Math.round(nextHeight)));
            setWorkbenchResultsSizePx(clamped);
            event.preventDefault();
        },
        [workbenchResultsSizePx]
    );

    const triggerAutocompleteSuggest = useCallback(
        (
            source: 'mount' | 'model-content' | 'key-up' | 'manual' | 'manual-retry' | 'unknown',
            editorInstance?: MonacoEditorNamespace.IStandaloneCodeEditor | null
        ) => {
            const targetEditor = editorInstance ?? editorRef.current;
            const occurredAt = new Date().toISOString();
            setAutocompleteDiagnostics((current) => ({
                ...current,
                lastTriggerSource: source,
                lastTriggerAt: occurredAt,
                triggerCount: current.triggerCount + 1
            }));

            if (!targetEditor) {
                setAutocompleteDiagnostics((current) => ({
                    ...current,
                    lastError: 'Suggest trigger skipped because editor instance is unavailable.'
                }));
                return;
            }

            try {
                targetEditor.trigger('keyboard', 'editor.action.triggerSuggest', {});
            } catch (error) {
                const message =
                    error instanceof Error ? error.message : 'Unknown Monaco trigger error.';
                setAutocompleteDiagnostics((current) => ({
                    ...current,
                    lastError: `Suggest trigger failed: ${message}`
                }));
            }
        },
        []
    );

    useEffect(() => {
        if (
            !isSystemAdmin &&
            (activeSection === 'audit' ||
                activeSection === 'health' ||
                activeSection === 'admin' ||
                activeSection === 'connections')
        ) {
            setActiveSection('workbench');
        }
    }, [activeSection, isSystemAdmin]);

    useEffect(() => {
        if (!localAuthEnabled && activeAdminSubsection === 'users') {
            setActiveAdminSubsection('groups');
        }
    }, [activeAdminSubsection, localAuthEnabled]);

    useEffect(() => {
        if (activeSection !== 'connections') {
            return;
        }

        setConnectionEditorMode('list');
    }, [activeSection]);

    useEffect(() => {
        if (activeSection === 'admin') {
            return;
        }

        setGroupAdminMode('list');
        setUserAdminMode('list');
        setAccessAdminMode('list');
        setSelectedGroupForEdit('');
    }, [activeSection]);

    useEffect(() => {
        if (activeSection !== 'admin') {
            return;
        }

        if (activeAdminSubsection !== 'groups') {
            setGroupAdminMode('list');
            setSelectedGroupForEdit('');
        }
        if (activeAdminSubsection !== 'users') {
            setUserAdminMode('list');
        }
        if (activeAdminSubsection !== 'access') {
            setAccessAdminMode('list');
        }
    }, [activeAdminSubsection, activeSection]);

    useEffect(() => {
        if (!leftRailCollapsed) {
            setCollapsedAdminSubmenuOpen(false);
            return;
        }

        setShowVersionInfo(false);
        setLeftRailUserMenuOpen(false);
        setCollapsedAdminSubmenuOpen(false);
    }, [leftRailCollapsed]);

    useEffect(() => {
        if (connectionEditorMode !== 'edit') {
            return;
        }
        const canEditSelection = adminManagedDatasources.some(
            (datasource) => datasource.id === selectedManagedDatasourceId
        );
        if (canEditSelection) {
            return;
        }

        setConnectionEditorMode('list');
    }, [adminManagedDatasources, connectionEditorMode, selectedManagedDatasourceId]);

    useEffect(() => {
        if (groupAdminMode !== 'edit') {
            return;
        }
        const hasSelectedGroup = adminGroups.some((group) => group.id === selectedGroupForEdit);
        if (hasSelectedGroup) {
            return;
        }

        setGroupAdminMode('list');
        setSelectedGroupForEdit('');
    }, [adminGroups, groupAdminMode, selectedGroupForEdit]);

    useEffect(() => {
        const handleOutsideClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (!target || !leftRailUserMenuRef.current?.contains(target)) {
                setLeftRailUserMenuOpen(false);
            }
        };

        if (leftRailUserMenuOpen) {
            document.addEventListener('mousedown', handleOutsideClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick);
        };
    }, [leftRailUserMenuOpen]);

    useEffect(() => {
        const handleOutsideClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (!target || !versionInfoRef.current?.contains(target)) {
                setShowVersionInfo(false);
            }
        };

        if (showVersionInfo) {
            document.addEventListener('mousedown', handleOutsideClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick);
        };
    }, [showVersionInfo]);

    useEffect(() => {
        const handleOutsideClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (
                !target ||
                (!collapsedAdminSubmenuRef.current?.contains(target) &&
                    !collapsedAdminAnchorRef.current?.contains(target))
            ) {
                setCollapsedAdminSubmenuOpen(false);
            }
        };

        if (collapsedAdminSubmenuOpen) {
            document.addEventListener('mousedown', handleOutsideClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick);
        };
    }, [collapsedAdminSubmenuOpen]);

    useEffect(() => {
        const handleOutsideClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (
                !target ||
                (!activeTabMenuRef.current?.contains(target) &&
                    !activeTabMenuAnchorRef.current?.contains(target))
            ) {
                setActiveTabMenuOpen(false);
                setActiveTabMenuPosition(null);
            }
        };

        if (activeTabMenuOpen) {
            document.addEventListener('mousedown', handleOutsideClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick);
        };
    }, [activeTabMenuOpen]);

    useEffect(() => {
        const handleOutsideClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (!target || !editorShortcutsRef.current?.contains(target)) {
                setShowEditorShortcuts(false);
            }
        };

        if (showEditorShortcuts) {
            document.addEventListener('mousedown', handleOutsideClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick);
        };
    }, [showEditorShortcuts]);

    useEffect(() => {
        const handleOutsideClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (!target || !exportMenuRef.current?.contains(target)) {
                setShowExportMenu(false);
            }
        };

        if (showExportMenu) {
            document.addEventListener('mousedown', handleOutsideClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick);
        };
    }, [showExportMenu]);

    useEffect(() => {
        setActiveTabMenuOpen(false);
        setActiveTabMenuPosition(null);
        setShowExportMenu(false);
    }, [activeTabId]);

    useEffect(() => {
        if (!activeTabMenuOpen) {
            return;
        }

        const handleViewportChange = () => {
            setActiveTabMenuOpen(false);
            setActiveTabMenuPosition(null);
        };

        window.addEventListener('resize', handleViewportChange);
        window.addEventListener('scroll', handleViewportChange, true);
        return () => {
            window.removeEventListener('resize', handleViewportChange);
            window.removeEventListener('scroll', handleViewportChange, true);
        };
    }, [activeTabMenuOpen]);

    useEffect(() => {
        if (activeSection !== 'workbench' || monacoReady) {
            return;
        }

        setMonacoLoadTimedOut(false);
        const timeout = window.setTimeout(() => {
            setMonacoLoadTimedOut(true);
        }, 5000);

        return () => {
            window.clearTimeout(timeout);
        };
    }, [activeSection, editorRenderKey, monacoReady]);

    const activeTab = useMemo(
        () => workspaceTabs.find((tab) => tab.id === activeTabId) ?? null,
        [activeTabId, workspaceTabs]
    );

    useEffect(() => {
        workspaceTabsRef.current = workspaceTabs;
    }, [workspaceTabs]);

    useEffect(() => {
        setResultGridScrollTop(0);
        setResultSortState(null);
    }, [activeTabId]);

    const sortedResultRows = useMemo(() => {
        const rows = activeTab?.resultRows ?? [];
        if (!resultSortState) {
            return rows;
        }

        const sortedRows = [...rows];
        sortedRows.sort((left, right) =>
            compareResultValues(
                left[resultSortState.columnIndex] ?? null,
                right[resultSortState.columnIndex] ?? null,
                resultSortState.direction
            )
        );
        return sortedRows;
    }, [activeTab?.resultRows, resultSortState]);

    const visibleResultRows = useMemo(() => {
        const rows = sortedResultRows;
        if (rows.length === 0) {
            return {
                start: 0,
                end: 0,
                topSpacerPx: 0,
                bottomSpacerPx: 0,
                rows: [] as Array<Array<string | null>>
            };
        }

        const viewportRows = Math.ceil(resultViewportHeightPx / resultRowHeightPx);
        const overscanRows = 8;
        const start = Math.max(
            0,
            Math.floor(resultGridScrollTop / resultRowHeightPx) - overscanRows
        );
        const end = Math.min(rows.length, start + viewportRows + overscanRows * 2);

        return {
            start,
            end,
            topSpacerPx: start * resultRowHeightPx,
            bottomSpacerPx: Math.max(0, (rows.length - end) * resultRowHeightPx),
            rows: rows.slice(start, end)
        };
    }, [resultGridScrollTop, sortedResultRows]);

    const selectedDatasource = useMemo(
        () =>
            visibleDatasources.find((datasource) => datasource.id === activeTab?.datasourceId) ??
            null,
        [activeTab?.datasourceId, visibleDatasources]
    );

    const explainPlanText = useMemo(() => {
        if (
            !activeTab ||
            activeTab.lastRunKind !== 'explain' ||
            activeTab.resultRows.length === 0
        ) {
            return '';
        }

        return activeTab.resultRows
            .map((row) => row.filter((cell) => cell !== null).join(' | '))
            .join('\n');
    }, [activeTab]);

    const executionDurationLabel = useMemo(() => {
        if (!activeTab?.startedAt || !activeTab?.completedAt) {
            return '-';
        }
        return formatExecutionDuration(activeTab.startedAt, activeTab.completedAt);
    }, [activeTab?.completedAt, activeTab?.startedAt]);

    const executionSubmittedAtLabel = useMemo(
        () => formatExecutionTimestamp(activeTab?.submittedAt ?? ''),
        [activeTab?.submittedAt]
    );

    const executionCompletedAtLabel = useMemo(
        () => formatExecutionTimestamp(activeTab?.completedAt ?? ''),
        [activeTab?.completedAt]
    );

    const hideRedundantResultStatusMessage = useMemo(() => {
        if (!activeTab?.executionId || !activeTab.statusMessage) {
            return false;
        }

        const normalized = activeTab.statusMessage.trim().toLowerCase();
        if (activeTab.executionStatus === 'SUCCEEDED' && normalized === 'query succeeded.') {
            return true;
        }
        if (activeTab.executionStatus === 'SUCCEEDED' && normalized === 'query succeeded') {
            return true;
        }

        return false;
    }, [activeTab?.executionId, activeTab?.executionStatus, activeTab?.statusMessage]);

    const selectedDatasourceIcon = useMemo(
        () => resolveDatasourceIcon(selectedDatasource?.engine),
        [selectedDatasource?.engine]
    );
    const selectedDatasourceHealth = useMemo<DatasourceHealthState>(() => {
        const datasourceId = activeTab?.datasourceId ?? '';
        if (!datasourceId) {
            return 'unknown';
        }
        return datasourceHealthById[datasourceId] ?? 'unknown';
    }, [activeTab?.datasourceId, datasourceHealthById]);
    const selectedDatasourceHealthLabel = useMemo(() => {
        if (selectedDatasourceHealth === 'active') {
            return 'Active';
        }
        if (selectedDatasourceHealth === 'inactive') {
            return 'Inactive';
        }
        return 'Unknown';
    }, [selectedDatasourceHealth]);
    const availableSchemaNames = useMemo(() => {
        if (!schemaBrowser) {
            return [] as string[];
        }

        const uniqueSchemas = new Set<string>();
        schemaBrowser.schemas.forEach((schemaEntry) => {
            const normalized = schemaEntry.schema.trim();
            if (normalized) {
                uniqueSchemas.add(normalized);
            }
        });

        return Array.from(uniqueSchemas).sort((left, right) => left.localeCompare(right));
    }, [schemaBrowser]);
    const appVersionLabel = useMemo(() => {
        const normalized = appVersion.trim();
        if (!normalized || normalized === 'unknown') {
            return 'v-dev';
        }
        return normalized.startsWith('v') ? normalized : `v${normalized}`;
    }, [appVersion]);

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
    const selectedGroupRecord = useMemo(
        () => adminGroups.find((group) => group.id === selectedGroupForEdit) ?? null,
        [adminGroups, selectedGroupForEdit]
    );
    const selectedGroupProtected = useMemo(
        () => (selectedGroupRecord ? protectedGroupIds.has(selectedGroupRecord.id) : false),
        [selectedGroupRecord]
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

    const mavenPresetsForFormEngine = useMemo<MavenDriverPreset[]>(() => {
        switch (managedDatasourceForm.engine) {
            case 'POSTGRESQL':
                return ['POSTGRESQL'];
            case 'MYSQL':
                return ['MYSQL'];
            case 'MARIADB':
                return ['MARIADB'];
            case 'TRINO':
                return ['TRINO'];
            case 'STARROCKS':
                return ['STARROCKS_MYSQL', 'STARROCKS_MARIADB'];
            default:
                return [];
        }
    }, [managedDatasourceForm.engine]);

    const selectedDriverForForm = useMemo(
        () =>
            driversForFormEngine.find(
                (driver) => driver.driverId === managedDatasourceForm.driverId
            ) ?? null,
        [driversForFormEngine, managedDatasourceForm.driverId]
    );

    useEffect(() => {
        if (mavenPresetsForFormEngine.length === 0) {
            return;
        }

        if (!mavenPresetsForFormEngine.includes(mavenDriverPreset)) {
            setMavenDriverPreset(mavenPresetsForFormEngine[0]);
        }
    }, [mavenDriverPreset, mavenPresetsForFormEngine]);

    useEffect(() => {
        if (mavenPresetsForFormEngine.length === 0) {
            setMavenDriverVersions([]);
            setMavenDriverVersionsError('');
            setLoadingMavenDriverVersions(false);
            return;
        }

        if (!mavenPresetsForFormEngine.includes(mavenDriverPreset)) {
            return;
        }

        const controller = new AbortController();
        setLoadingMavenDriverVersions(true);
        setMavenDriverVersionsError('');
        setMavenDriverVersions([]);

        void (async () => {
            try {
                const response = await fetch(
                    `/api/admin/drivers/maven/versions?preset=${encodeURIComponent(mavenDriverPreset)}&limit=60`,
                    {
                        signal: controller.signal,
                        headers: { Accept: 'application/json' }
                    }
                );

                if (!response.ok) {
                    const text = await response.text();
                    throw new Error(text || `Failed to load versions (HTTP ${response.status}).`);
                }

                const payload: unknown = await response.json();
                const versions = Array.isArray(payload)
                    ? payload.filter((value): value is string => typeof value === 'string')
                    : [];

                setMavenDriverVersions(versions);
                setMavenDriverVersionInput((current) => {
                    const trimmed = current.trim();
                    if (!trimmed) {
                        return versions[0] ?? '';
                    }
                    if (versions.length > 0 && !versions.includes(trimmed)) {
                        return versions[0] ?? '';
                    }
                    return trimmed;
                });
            } catch (error) {
                if (controller.signal.aborted) {
                    return;
                }
                setMavenDriverVersions([]);
                setMavenDriverVersionsError(
                    error instanceof Error ? error.message : 'Unable to load versions.'
                );
            } finally {
                if (!controller.signal.aborted) {
                    setLoadingMavenDriverVersions(false);
                }
            }
        })();

        return () => controller.abort();
    }, [mavenDriverPreset, mavenPresetsForFormEngine]);

    const snippetGroupOptions = useMemo(() => {
        const options = new Set<string>();
        (currentUser?.groups ?? []).forEach((groupId) => {
            if (groupId.trim()) {
                options.add(groupId.trim());
            }
        });
        adminGroups.forEach((group) => {
            if (group.id.trim()) {
                options.add(group.id.trim());
            }
        });
        snippets.forEach((snippet) => {
            if (snippet.groupId?.trim()) {
                options.add(snippet.groupId.trim());
            }
        });

        return Array.from(options).sort((left, right) => left.localeCompare(right));
    }, [adminGroups, currentUser?.groups, snippets]);

    const auditActionOptions = useMemo(() => {
        const options = new Set<string>(builtInAuditActions);
        auditEvents.forEach((event) => {
            if (event.type.trim()) {
                options.add(event.type.trim());
            }
        });
        return Array.from(options).sort((left, right) => left.localeCompare(right));
    }, [auditEvents]);

    const auditOutcomeOptions = useMemo(() => {
        const options = new Set<string>(builtInAuditOutcomes);
        auditEvents.forEach((event) => {
            if (event.outcome.trim()) {
                options.add(event.outcome.trim());
            }
        });
        return Array.from(options).sort((left, right) => left.localeCompare(right));
    }, [auditEvents]);

    const sortedQueryHistoryEntries = useMemo(() => {
        const rows = [...queryHistoryEntries];
        rows.sort((left, right) => {
            const leftTimestamp = new Date(left.submittedAt).getTime();
            const rightTimestamp = new Date(right.submittedAt).getTime();
            const safeLeft = Number.isFinite(leftTimestamp) ? leftTimestamp : 0;
            const safeRight = Number.isFinite(rightTimestamp) ? rightTimestamp : 0;
            return historySortOrder === 'newest' ? safeRight - safeLeft : safeLeft - safeRight;
        });
        return rows;
    }, [historySortOrder, queryHistoryEntries]);

    const sortedAuditEvents = useMemo(() => {
        const rows = [...auditEvents];
        rows.sort((left, right) => {
            const leftTimestamp = new Date(left.timestamp).getTime();
            const rightTimestamp = new Date(right.timestamp).getTime();
            const safeLeft = Number.isFinite(leftTimestamp) ? leftTimestamp : 0;
            const safeRight = Number.isFinite(rightTimestamp) ? rightTimestamp : 0;
            return auditSortOrder === 'newest' ? safeRight - safeLeft : safeLeft - safeRight;
        });
        return rows;
    }, [auditEvents, auditSortOrder]);

    const managedDatasourcesByEngine = useMemo(() => {
        const rows = [...adminManagedDatasources];
        rows.sort((left, right) => {
            const engineOrder = left.engine.localeCompare(right.engine);
            if (engineOrder !== 0) {
                return engineOrder;
            }
            return left.name.localeCompare(right.name);
        });
        return rows;
    }, [adminManagedDatasources]);

    const managedFormOptions = useMemo(
        () => parseOptionsInput(managedDatasourceForm.optionsInput),
        [managedDatasourceForm.optionsInput]
    );

    const managedFormJdbcPreview = useMemo(() => {
        const options =
            managedDatasourceForm.connectionType === 'JDBC_URL' &&
            managedDatasourceForm.jdbcUrl.trim()
                ? { ...managedFormOptions, jdbcUrl: managedDatasourceForm.jdbcUrl.trim() }
                : managedFormOptions;

        return buildJdbcUrlPreview(
            managedDatasourceForm.engine,
            managedDatasourceForm.host,
            managedDatasourceForm.port,
            managedDatasourceForm.database,
            managedDatasourceForm.tlsMode,
            managedDatasourceForm.verifyServerCertificate,
            options
        );
    }, [
        managedDatasourceForm.connectionType,
        managedDatasourceForm.database,
        managedDatasourceForm.engine,
        managedDatasourceForm.host,
        managedDatasourceForm.jdbcUrl,
        managedDatasourceForm.port,
        managedDatasourceForm.tlsMode,
        managedDatasourceForm.verifyServerCertificate,
        managedFormOptions
    ]);

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
                        requestedCredentialProfile: '',
                        queryText: typeof tab.queryText === 'string' ? tab.queryText : 'SELECT 1;',
                        isExecuting: false,
                        statusMessage: '',
                        errorMessage: '',
                        lastRunKind: 'query',
                        executionId: '',
                        executionStatus: '',
                        queryHash: '',
                        resultColumns: [],
                        resultRows: [],
                        nextPageToken: '',
                        currentPageToken: '',
                        previousPageTokens: [],
                        rowLimitReached: false,
                        submittedAt: '',
                        startedAt: '',
                        completedAt: '',
                        rowCount: 0,
                        columnCount: 0,
                        maxRowsPerQuery: 0,
                        maxRuntimeSeconds: 0,
                        credentialProfile: ''
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
                        'Connection access changed. Select a permitted connection before running.',
                    statusMessage: ''
                };
            })
        );
    }, [tabsHydrated, visibleDatasources]);

    useEffect(() => {
        setDatasourceHealthById((current) => {
            const next = { ...current };
            let changed = false;
            visibleDatasources.forEach((datasource) => {
                if (next[datasource.id] === undefined) {
                    next[datasource.id] = 'unknown';
                    changed = true;
                }
            });
            return changed ? next : current;
        });
    }, [visibleDatasources]);

    const loadAdminData = useCallback(
        async (active = true, includeLocalUsers = localAuthEnabled) => {
            const [
                groupsResponse,
                catalogResponse,
                accessResponse,
                managedDatasourceResponse,
                driversResponse,
                usersResponse
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
                }),
                includeLocalUsers
                    ? fetch('/api/auth/admin/users', {
                          method: 'GET',
                          credentials: 'include'
                      })
                    : Promise.resolve(null)
            ]);

            if (
                !groupsResponse.ok ||
                !catalogResponse.ok ||
                !accessResponse.ok ||
                !managedDatasourceResponse.ok ||
                !driversResponse.ok ||
                (usersResponse !== null && !usersResponse.ok)
            ) {
                throw new Error('Failed to load admin governance data.');
            }

            const groups = (await groupsResponse.json()) as GroupResponse[];
            const datasourceCatalog = (await catalogResponse.json()) as CatalogDatasourceResponse[];
            const datasourceAccess = (await accessResponse.json()) as DatasourceAccessResponse[];
            const managedDatasources =
                (await managedDatasourceResponse.json()) as ManagedDatasourceResponse[];
            const drivers = (await driversResponse.json()) as DriverDescriptorResponse[];
            const users =
                usersResponse === null ? [] : ((await usersResponse.json()) as AdminUserResponse[]);

            if (!active) {
                return;
            }

            setAdminGroups(groups);
            setAdminDatasourceCatalog(datasourceCatalog);
            setAdminDatasourceAccess(datasourceAccess);
            setAdminManagedDatasources(managedDatasources);
            setAdminDrivers(drivers);
            setAdminUsers(users);
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
                current && managedDatasources.some((datasource) => datasource.id === current)
                    ? current
                    : ''
            );
        },
        [localAuthEnabled]
    );

    useEffect(() => {
        let active = true;

        const loadWorkspace = async () => {
            try {
                setLoadingWorkspace(true);
                setWorkspaceError('');

                const methodsResponse = await fetch('/api/auth/methods', {
                    method: 'GET',
                    credentials: 'include'
                });
                if (!methodsResponse.ok) {
                    throw new Error('Failed to load authentication methods.');
                }
                const methodsPayload = (await methodsResponse.json()) as AuthMethodsResponse;
                const enabledMethods =
                    methodsPayload.methods?.map((method) => method.toLowerCase()) ?? [];

                const versionResponsePromise = fetch('/api/version', {
                    method: 'GET',
                    credentials: 'include'
                });
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
                    throw new Error('Failed to load connection list.');
                }

                const datasources =
                    (await datasourceResponse.json()) as CatalogDatasourceResponse[];
                const versionPayload = await versionResponsePromise
                    .then(async (response) => {
                        if (!response.ok) {
                            return null;
                        }
                        return (await response.json()) as VersionResponse;
                    })
                    .catch(() => null);
                if (!active) {
                    return;
                }

                setCurrentUser(me);
                setAppVersion(versionPayload?.version ?? 'unknown');
                setAuthMethods(enabledMethods);
                setVisibleDatasources(datasources);
                hydrateWorkspaceTabs(datasources);

                if (me.roles.includes('SYSTEM_ADMIN')) {
                    await loadAdminData(active, enabledMethods.includes('local'));
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
            setReadOnly(selectedAccessRule.readOnly);
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
        setReadOnly(true);
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
            setCredentialProfileIdInput('admin-ro');
            setCredentialUsernameInput('');
            setCredentialPasswordInput('');
            setCredentialDescriptionInput('');
            setTestConnectionMessage('');
            setTestConnectionOutcome('');
            setTlsCaCertificatePemInput(null);
            setTlsCaCertificateFileName('');
            setTlsClientCertificatePemInput(null);
            setTlsClientCertificateFileName('');
            setTlsClientKeyPemInput(null);
            setTlsClientKeyFileName('');
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
        setTlsCaCertificatePemInput(null);
        setTlsCaCertificateFileName('');
        setTlsClientCertificatePemInput(null);
        setTlsClientCertificateFileName('');
        setTlsClientKeyPemInput(null);
        setTlsClientKeyFileName('');
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
        if (!selectedDriverForForm) {
            return;
        }

        setUploadDriverClassInput((current) =>
            current.trim() ? current : selectedDriverForForm.driverClass
        );
    }, [selectedDriverForForm]);

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

    const fetchCsrfToken = useCallback(async (): Promise<CsrfTokenResponse> => {
        const response = await fetch('/api/auth/csrf', {
            method: 'GET',
            credentials: 'include'
        });

        if (!response.ok) {
            throw new Error('Unable to acquire a CSRF token for this request.');
        }

        return (await response.json()) as CsrfTokenResponse;
    }, []);

    const handleLogout = useCallback(async () => {
        try {
            const csrfToken = await fetchCsrfToken();
            await fetch('/api/auth/logout', {
                method: 'POST',
                credentials: 'include',
                headers: {
                    [csrfToken.headerName]: csrfToken.token
                }
            });
        } catch {
            // Best effort logout. Client redirect still clears app session state.
        } finally {
            navigate('/login', { replace: true });
        }
    }, [fetchCsrfToken, navigate]);

    const toIsoTimestamp = (value: string): string | null => {
        const trimmed = value.trim();
        if (!trimmed) {
            return null;
        }

        const parsed = new Date(trimmed);
        if (Number.isNaN(parsed.getTime())) {
            return null;
        }

        return parsed.toISOString();
    };

    const loadSchemaBrowser = useCallback(
        async (datasourceId: string, refresh = false) => {
            const normalizedDatasourceId = datasourceId.trim();
            if (!normalizedDatasourceId) {
                setSchemaBrowser(null);
                setDatasourceHealthById((current) => {
                    if (!datasourceId) {
                        return current;
                    }
                    return {
                        ...current,
                        [datasourceId]: 'unknown'
                    };
                });
                return;
            }

            setLoadingSchemaBrowser(true);
            setSchemaBrowserError('');
            try {
                const queryParams = new URLSearchParams();
                if (refresh) {
                    queryParams.set('refresh', 'true');
                }

                const response = await fetch(
                    `/api/datasources/${normalizedDatasourceId}/schema-browser${
                        queryParams.toString() ? `?${queryParams.toString()}` : ''
                    }`,
                    {
                        method: 'GET',
                        credentials: 'include'
                    }
                );
                if (!response.ok) {
                    throw new Error(await readFriendlyError(response));
                }

                const payload = (await response.json()) as DatasourceSchemaBrowserResponse;
                setSchemaBrowser(payload);
                setDatasourceHealthById((current) => ({
                    ...current,
                    [normalizedDatasourceId]: 'active'
                }));
            } catch (error) {
                const message =
                    error instanceof Error ? error.message : 'Failed to load schema browser.';
                setSchemaBrowserError(message);
                setDatasourceHealthById((current) => ({
                    ...current,
                    [normalizedDatasourceId]: 'inactive'
                }));
            } finally {
                setLoadingSchemaBrowser(false);
            }
        },
        [readFriendlyError]
    );

    const loadSnippets = useCallback(async () => {
        if (!currentUser) {
            return;
        }

        setLoadingSnippets(true);
        setSnippetError('');
        try {
            const queryParams = new URLSearchParams();
            queryParams.set('scope', snippetScope);
            if (snippetTitleInput.trim()) {
                queryParams.set('title', snippetTitleInput.trim());
            }
            if (snippetGroupInput.trim()) {
                queryParams.set('groupId', snippetGroupInput.trim());
            }
            const response = await fetch(`/api/snippets?${queryParams.toString()}`, {
                method: 'GET',
                credentials: 'include'
            });
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as SnippetResponse[];
            setSnippets(Array.isArray(payload) ? payload : []);
        } catch (error) {
            const message = error instanceof Error ? error.message : 'Failed to load snippets.';
            setSnippetError(message);
        } finally {
            setLoadingSnippets(false);
        }
    }, [currentUser, readFriendlyError, snippetGroupInput, snippetScope, snippetTitleInput]);

    const loadQueryHistory = useCallback(async () => {
        if (!currentUser) {
            return;
        }

        setLoadingQueryHistory(true);
        try {
            const queryParams = new URLSearchParams();
            if (historyDatasourceFilter.trim()) {
                queryParams.set('datasourceId', historyDatasourceFilter.trim());
            }
            if (historyStatusFilter.trim()) {
                queryParams.set('status', historyStatusFilter.trim());
            }

            const fromIso = toIsoTimestamp(historyFromFilter);
            if (fromIso) {
                queryParams.set('from', fromIso);
            }
            const toIso = toIsoTimestamp(historyToFilter);
            if (toIso) {
                queryParams.set('to', toIso);
            }
            queryParams.set('limit', '200');

            const response = await fetch(`/api/queries/history?${queryParams.toString()}`, {
                method: 'GET',
                credentials: 'include'
            });
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as QueryHistoryEntryResponse[];
            setQueryHistoryEntries(Array.isArray(payload) ? payload : []);
        } catch (error) {
            const message =
                error instanceof Error ? error.message : 'Failed to load query history.';
            setWorkspaceError(message);
        } finally {
            setLoadingQueryHistory(false);
        }
    }, [
        currentUser,
        historyDatasourceFilter,
        historyFromFilter,
        historyStatusFilter,
        historyToFilter,
        readFriendlyError
    ]);

    const loadAuditEvents = useCallback(async () => {
        if (!isSystemAdmin) {
            return;
        }

        setLoadingAuditEvents(true);
        try {
            const queryParams = new URLSearchParams();
            if (auditTypeFilter.trim()) {
                queryParams.set('type', auditTypeFilter.trim());
            }
            if (auditActorFilter.trim()) {
                queryParams.set('actor', auditActorFilter.trim());
            }
            if (auditOutcomeFilter.trim()) {
                queryParams.set('outcome', auditOutcomeFilter.trim());
            }

            const fromIso = toIsoTimestamp(auditFromFilter);
            if (fromIso) {
                queryParams.set('from', fromIso);
            }
            const toIso = toIsoTimestamp(auditToFilter);
            if (toIso) {
                queryParams.set('to', toIso);
            }
            queryParams.set('limit', '200');

            const response = await fetch(`/api/admin/audit-events?${queryParams.toString()}`, {
                method: 'GET',
                credentials: 'include'
            });
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as AuditEventResponse[];
            setAuditEvents(Array.isArray(payload) ? payload : []);
        } catch (error) {
            const message = error instanceof Error ? error.message : 'Failed to load audit events.';
            setAdminError(message);
        } finally {
            setLoadingAuditEvents(false);
        }
    }, [
        auditActorFilter,
        auditFromFilter,
        auditOutcomeFilter,
        auditToFilter,
        auditTypeFilter,
        isSystemAdmin,
        readFriendlyError
    ]);

    const loadSystemHealth = useCallback(async () => {
        if (!isSystemAdmin) {
            return;
        }

        const resolvedDatasourceId = systemHealthDatasourceId.trim();
        const resolvedCredentialProfile = systemHealthCredentialProfile.trim();
        if (!resolvedDatasourceId || !resolvedCredentialProfile) {
            setSystemHealthResponse(null);
            return;
        }

        setLoadingSystemHealth(true);
        setSystemHealthError('');
        try {
            const queryParams = new URLSearchParams();
            queryParams.set('datasourceId', resolvedDatasourceId);
            queryParams.set('credentialProfile', resolvedCredentialProfile);

            const response = await fetch(`/api/admin/system-health?${queryParams.toString()}`, {
                method: 'GET',
                credentials: 'include'
            });
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const payload = (await response.json()) as SystemHealthResponse;
            setSystemHealthResponse(payload);
        } catch (error) {
            const message =
                error instanceof Error ? error.message : 'Failed to load system health.';
            setSystemHealthError(message);
        } finally {
            setLoadingSystemHealth(false);
        }
    }, [isSystemAdmin, readFriendlyError, systemHealthCredentialProfile, systemHealthDatasourceId]);

    useEffect(() => {
        if (!currentUser) {
            return;
        }

        void loadQueryHistory();
    }, [currentUser, loadQueryHistory]);

    useEffect(() => {
        if (!isSystemAdmin) {
            return;
        }

        void loadAuditEvents();
    }, [isSystemAdmin, loadAuditEvents]);

    useEffect(() => {
        if (!isSystemAdmin || activeSection !== 'health') {
            return;
        }

        void loadSystemHealth();
    }, [activeSection, isSystemAdmin, loadSystemHealth]);

    useEffect(() => {
        if (!isSystemAdmin || !adminSuccess) {
            return;
        }

        void loadAuditEvents();
    }, [adminSuccess, isSystemAdmin, loadAuditEvents]);

    useEffect(() => {
        const datasourceId = activeTab?.datasourceId ?? '';
        if (!datasourceId) {
            setSchemaBrowser(null);
            setSchemaBrowserError('');
            return;
        }

        void loadSchemaBrowser(datasourceId, false);
    }, [activeTab?.datasourceId, loadSchemaBrowser]);

    useEffect(() => {
        if (!schemaBrowser) {
            return;
        }

        const datasourceKey = `datasource:${schemaBrowser.datasourceId}`;
        setExpandedExplorerDatasources((current) =>
            current[datasourceKey] === undefined
                ? {
                      ...current,
                      [datasourceKey]: true
                  }
                : current
        );

        setExpandedExplorerSchemas((current) => {
            const next = { ...current };
            schemaBrowser.schemas.forEach((schemaEntry, index) => {
                const schemaKey = `${datasourceKey}:schema:${schemaEntry.schema}`;
                if (next[schemaKey] === undefined) {
                    next[schemaKey] = index === 0;
                }
            });
            return next;
        });

        setExpandedExplorerTables((current) => {
            const next = { ...current };
            schemaBrowser.schemas.forEach((schemaEntry) => {
                const schemaKey = `${datasourceKey}:schema:${schemaEntry.schema}`;
                schemaEntry.tables.forEach((tableEntry) => {
                    const tableKey = `${schemaKey}:table:${tableEntry.table}`;
                    if (next[tableKey] === undefined) {
                        next[tableKey] = false;
                    }
                });
            });
            return next;
        });

        setSelectedExplorerNode((current) =>
            current ? current : `datasource:${schemaBrowser.datasourceId}`
        );
    }, [schemaBrowser]);

    useEffect(() => {
        if (!currentUser) {
            return;
        }

        void loadSnippets();
    }, [currentUser, loadSnippets]);

    useEffect(() => {
        completionProviderRef.current?.dispose();
        completionProviderRef.current = null;

        const monaco = monacoRef.current;
        const activeModelLanguageId = editorRef.current?.getModel()?.getLanguageId() ?? '';
        if (!monaco || !monacoReady) {
            setAutocompleteDiagnostics((current) => ({
                ...current,
                enabled: false,
                monacoReady,
                editorMounted: Boolean(editorRef.current),
                modelLanguageId: activeModelLanguageId,
                availableLanguageIds: [],
                registeredLanguageIds: [],
                suggestionSeedCount: 0,
                lastSuggestionCount: 0,
                lastError: monacoReady
                    ? 'Monaco API is not initialized for autocomplete registration.'
                    : 'Monaco editor is still loading.'
            }));
            return;
        }

        type CompletionSeed = Omit<MonacoModule.languages.CompletionItem, 'range'>;
        const suggestions = new Map<string, CompletionSeed>();
        const putSuggestion = (item: CompletionSeed) => {
            const normalizedInsertText =
                typeof item.insertText === 'string' ? item.insertText : String(item.label);
            const key = `${String(item.label)}::${item.kind}::${normalizedInsertText}`;
            if (!suggestions.has(key)) {
                suggestions.set(key, item);
            }
        };

        sqlKeywordSuggestions.forEach((keyword) => {
            putSuggestion({
                label: keyword,
                insertText: keyword,
                kind: monaco.languages.CompletionItemKind.Keyword,
                detail: 'SQL keyword',
                sortText: `0-${keyword}`
            });
        });

        visibleDatasources.forEach((datasource) => {
            putSuggestion({
                label: datasource.name,
                insertText: datasource.id,
                kind: monaco.languages.CompletionItemKind.Module,
                detail: `Connection (${datasource.engine})`,
                sortText: `3-${datasource.name.toLowerCase()}`
            });
        });

        if (schemaBrowser && schemaBrowser.datasourceId === activeTab?.datasourceId) {
            schemaBrowser.schemas.forEach((schemaEntry) => {
                putSuggestion({
                    label: schemaEntry.schema,
                    insertText: schemaEntry.schema,
                    kind: monaco.languages.CompletionItemKind.Module,
                    detail: 'Schema',
                    sortText: `1-${schemaEntry.schema.toLowerCase()}`
                });

                schemaEntry.tables.forEach((tableEntry) => {
                    const qualifiedTableName = `${schemaEntry.schema}.${tableEntry.table}`;
                    putSuggestion({
                        label: qualifiedTableName,
                        insertText: qualifiedTableName,
                        kind: monaco.languages.CompletionItemKind.Class,
                        detail: `${tableEntry.type} table`,
                        sortText: `1-${qualifiedTableName.toLowerCase()}`
                    });
                    putSuggestion({
                        label: tableEntry.table,
                        insertText: tableEntry.table,
                        kind: monaco.languages.CompletionItemKind.Class,
                        detail: `${tableEntry.type} table in ${schemaEntry.schema}`,
                        sortText: `1-${tableEntry.table.toLowerCase()}`
                    });

                    tableEntry.columns.forEach((columnEntry) => {
                        const tableColumnName = `${tableEntry.table}.${columnEntry.name}`;
                        const qualifiedColumnName = `${qualifiedTableName}.${columnEntry.name}`;
                        putSuggestion({
                            label: columnEntry.name,
                            insertText: columnEntry.name,
                            kind: monaco.languages.CompletionItemKind.Field,
                            detail: `${schemaEntry.schema}.${tableEntry.table} (${columnEntry.jdbcType})`,
                            sortText: `2-${columnEntry.name.toLowerCase()}`
                        });
                        putSuggestion({
                            label: tableColumnName,
                            insertText: tableColumnName,
                            kind: monaco.languages.CompletionItemKind.Field,
                            detail: `${tableEntry.type} column`,
                            sortText: `2-${tableColumnName.toLowerCase()}`
                        });
                        putSuggestion({
                            label: qualifiedColumnName,
                            insertText: qualifiedColumnName,
                            kind: monaco.languages.CompletionItemKind.Field,
                            detail: columnEntry.jdbcType,
                            sortText: `2-${qualifiedColumnName.toLowerCase()}`
                        });
                    });
                });
            });
        }

        const completionItems = Array.from(suggestions.values());
        const availableLanguageIds = new Set(
            monaco.languages.getLanguages().map((language) => language.id)
        );
        const preferredLanguageIds = [
            activeModelLanguageId,
            'sql',
            'plaintext',
            'mysql',
            'mariadb',
            'pgsql',
            'postgres',
            'postgresql',
            'trino',
            'starrocks',
            'vertica'
        ].filter((languageId): languageId is string => Boolean(languageId));
        const completionLanguageIds = Array.from(new Set(preferredLanguageIds));
        if (completionLanguageIds.length === 0) {
            completionLanguageIds.push(activeModelLanguageId || 'sql');
        }

        const providerErrors: string[] = [];
        const registeredLanguageIds: string[] = [];
        const providerDisposables = completionLanguageIds
            .map((languageId) => {
                try {
                    const provider = monaco.languages.registerCompletionItemProvider(languageId, {
                        triggerCharacters: ['.', '(', ',', '_'],
                        provideCompletionItems(model, position) {
                            const word = model.getWordUntilPosition(position);
                            const range = {
                                startLineNumber: position.lineNumber,
                                endLineNumber: position.lineNumber,
                                startColumn: word.startColumn,
                                endColumn: word.endColumn
                            };
                            const normalizedWord = word.word.trim().toLowerCase();
                            const filteredSuggestions = (
                                normalizedWord
                                    ? completionItems.filter((candidate) => {
                                          const label =
                                              typeof candidate.label === 'string'
                                                  ? candidate.label
                                                  : candidate.label.label;
                                          return label.toLowerCase().includes(normalizedWord);
                                      })
                                    : completionItems
                            ).slice(0, 250);
                            const occurredAt = new Date().toISOString();
                            setAutocompleteDiagnostics((current) => ({
                                ...current,
                                providerInvocationCount: current.providerInvocationCount + 1,
                                lastInvocationAt: occurredAt,
                                lastSuggestionCount: filteredSuggestions.length,
                                lastError: ''
                            }));

                            return {
                                suggestions: filteredSuggestions.map((suggestion) => ({
                                    ...suggestion,
                                    range
                                }))
                            };
                        }
                    });
                    registeredLanguageIds.push(languageId);
                    return provider;
                } catch (error) {
                    const message =
                        error instanceof Error ? error.message : 'Unknown Monaco provider error.';
                    providerErrors.push(
                        `Provider registration failed for language "${languageId}": ${message}`
                    );
                    return null;
                }
            })
            .filter(
                (
                    provider
                ): provider is {
                    dispose: () => void;
                } => provider !== null
            );

        completionProviderRef.current = {
            dispose: () => {
                providerDisposables.forEach((provider) => provider.dispose());
            }
        };

        const providerErrorMessage = providerErrors.join(' ');
        setAutocompleteDiagnostics((current) => ({
            ...current,
            enabled: providerDisposables.length > 0,
            monacoReady,
            editorMounted: Boolean(editorRef.current),
            modelLanguageId: activeModelLanguageId,
            availableLanguageIds: Array.from(availableLanguageIds).sort(),
            registeredLanguageIds,
            suggestionSeedCount: completionItems.length,
            lastSuggestionCount: completionItems.length,
            lastError:
                providerErrorMessage ||
                (providerDisposables.length === 0
                    ? 'No autocomplete providers were registered.'
                    : '')
        }));

        return () => {
            completionProviderRef.current?.dispose();
            completionProviderRef.current = null;
        };
    }, [activeTab?.datasourceId, monacoReady, schemaBrowser, visibleDatasources]);

    const handleOpenNewTabForDatasource = useCallback(
        (datasourceId: string, initialSql = 'SELECT 1;') => {
            const fallbackDatasourceId = visibleDatasources[0]?.id ?? '';
            const resolvedDatasourceId =
                datasourceId &&
                visibleDatasources.some((datasource) => datasource.id === datasourceId)
                    ? datasourceId
                    : fallbackDatasourceId;

            setWorkspaceTabs((currentTabs) => {
                const nextIndex = currentTabs.length + 1;
                const createdTab = buildWorkspaceTab(
                    resolvedDatasourceId,
                    `Query ${nextIndex}`,
                    initialSql
                );
                setActiveTabId(createdTab.id);
                return [...currentTabs, createdTab];
            });
            setActiveSection('workbench');
        },
        [visibleDatasources]
    );

    const handleOpenNewTab = useCallback(() => {
        const preferredDatasourceId = activeTab?.datasourceId || visibleDatasources[0]?.id || '';
        handleOpenNewTabForDatasource(preferredDatasourceId);
    }, [activeTab?.datasourceId, handleOpenNewTabForDatasource, visibleDatasources]);

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

    const handleDuplicateTab = useCallback((tabId: string) => {
        const tab = workspaceTabsRef.current.find((candidate) => candidate.id === tabId);
        if (!tab) {
            return;
        }

        const duplicateTab: WorkspaceTab = {
            ...buildWorkspaceTab(tab.datasourceId, `${tab.title} Copy`, tab.queryText),
            schema: tab.schema
        };

        setWorkspaceTabs((currentTabs) => [...currentTabs, duplicateTab]);
        setActiveTabId(duplicateTab.id);
    }, []);

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
            queryParams.set('pageSize', resultsPageSize.toString());
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
                if (currentTab.executionId && currentTab.executionId !== executionId) {
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
        [readFriendlyError, resultsPageSize, updateWorkspaceTab]
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
                if (currentTab.executionId && currentTab.executionId !== executionId) {
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
                    submittedAt: payload.submittedAt ?? currentTab.submittedAt,
                    startedAt: payload.startedAt ?? '',
                    completedAt: payload.completedAt ?? '',
                    rowCount: payload.rowCount,
                    columnCount: payload.columnCount,
                    maxRowsPerQuery: payload.maxRowsPerQuery,
                    maxRuntimeSeconds: payload.maxRuntimeSeconds,
                    credentialProfile: payload.credentialProfile,
                    isExecuting: !terminal
                };
            });

            if (terminal) {
                clearQueryStatusPolling(tabId);
                void loadQueryHistory();
            }

            if (payload.status === 'SUCCEEDED' && loadFirstResultsPageOnSuccess) {
                await fetchQueryResultsPage(tabId, executionId, firstPageToken, []);
            }

            return payload;
        },
        [
            clearQueryStatusPolling,
            fetchQueryResultsPage,
            loadQueryHistory,
            readFriendlyError,
            updateWorkspaceTab
        ]
    );

    const startQueryStatusPolling = useCallback(
        (tabId: string, executionId: string) => {
            clearQueryStatusPolling(tabId);
            let attempts = 0;
            let consecutiveFailures = 0;

            const poll = async () => {
                const trackedTab = workspaceTabsRef.current.find((tab) => tab.id === tabId);
                if (
                    !trackedTab ||
                    (trackedTab.executionId && trackedTab.executionId !== executionId)
                ) {
                    clearQueryStatusPolling(tabId);
                    return;
                }

                try {
                    const status = await fetchExecutionStatus(tabId, executionId, true);
                    consecutiveFailures = 0;
                    if (isTerminalExecutionStatus(status.status)) {
                        clearQueryStatusPolling(tabId);
                        return;
                    }
                } catch (error) {
                    consecutiveFailures += 1;
                    if (consecutiveFailures >= 3) {
                        const message =
                            error instanceof Error
                                ? error.message
                                : 'Execution status polling failed.';
                        updateWorkspaceTab(tabId, (currentTab) => {
                            if (currentTab.executionId && currentTab.executionId !== executionId) {
                                return currentTab;
                            }

                            return {
                                ...currentTab,
                                isExecuting: false,
                                executionStatus: 'FAILED',
                                statusMessage: 'Execution status unavailable.',
                                errorMessage: message
                            };
                        });
                        clearQueryStatusPolling(tabId);
                        void loadQueryHistory();
                        return;
                    }
                }

                attempts += 1;
                if (attempts >= queryStatusPollingMaxAttempts) {
                    updateWorkspaceTab(tabId, (currentTab) => {
                        if (currentTab.executionId && currentTab.executionId !== executionId) {
                            return currentTab;
                        }

                        return {
                            ...currentTab,
                            isExecuting: false,
                            executionStatus: 'FAILED',
                            statusMessage: 'Execution polling timed out.',
                            errorMessage:
                                'Status polling timed out before the server returned a terminal state.'
                        };
                    });
                    clearQueryStatusPolling(tabId);
                    void loadQueryHistory();
                    return;
                }

                queryStatusPollingTimersRef.current[tabId] = setTimeout(() => {
                    void poll();
                }, queryStatusPollingIntervalMs);
            };

            void poll();
        },
        [clearQueryStatusPolling, fetchExecutionStatus, loadQueryHistory, updateWorkspaceTab]
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
                await fetchExecutionStatus(tabId, tab.executionId, true);
            } catch (error) {
                const message =
                    error instanceof Error ? error.message : 'Failed to cancel query execution.';
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    errorMessage: message
                }));
            }
        },
        [
            clearQueryStatusPolling,
            fetchCsrfToken,
            fetchExecutionStatus,
            readFriendlyError,
            updateWorkspaceTab
        ]
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
        async (
            tabId: string,
            sqlText: string,
            modeLabel: QueryRunMode,
            runKind: 'query' | 'explain' = 'query'
        ) => {
            const tab = workspaceTabsRef.current.find((candidate) => candidate.id === tabId);
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
                    errorMessage: 'Select a connection before running a query.',
                    statusMessage: ''
                }));
                return;
            }

            if (!visibleDatasources.some((datasource) => datasource.id === datasourceId)) {
                updateWorkspaceTab(tabId, (currentTab) => ({
                    ...currentTab,
                    errorMessage:
                        'Selected connection is no longer permitted for your account. Choose another connection.',
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
                lastRunKind: runKind,
                resultColumns: [],
                resultRows: [],
                nextPageToken: '',
                currentPageToken: firstPageToken,
                previousPageTokens: [],
                rowLimitReached: false,
                submittedAt: '',
                startedAt: '',
                completedAt: '',
                rowCount: 0,
                columnCount: 0,
                maxRowsPerQuery: 0,
                maxRuntimeSeconds: 0,
                credentialProfile: '',
                statusMessage:
                    modeLabel === 'selection'
                        ? 'Running selected SQL...'
                        : modeLabel === 'statement'
                          ? 'Running statement at cursor...'
                          : modeLabel === 'explain'
                            ? 'Running EXPLAIN...'
                            : 'Running full tab SQL...',
                errorMessage: ''
            }));

            try {
                const csrfToken = await fetchCsrfToken();
                const requestPayload: Record<string, unknown> = {
                    datasourceId,
                    sql: normalizedSql
                };
                const requestedCredentialProfile = tab.requestedCredentialProfile.trim();
                if (isSystemAdmin && requestedCredentialProfile) {
                    requestPayload.credentialProfile = requestedCredentialProfile;
                }
                const response = await fetch('/api/queries', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrfToken.headerName]: csrfToken.token
                    },
                    body: JSON.stringify(requestPayload)
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
                    statusMessage: `Query queued on ${payload.datasourceId}.`,
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
            fetchCsrfToken,
            isSystemAdmin,
            readFriendlyError,
            startQueryStatusPolling,
            updateWorkspaceTab,
            visibleDatasources
        ]
    );

    const resolveRunnableSqlForTab = useCallback((tab: WorkspaceTab) => {
        const editor = editorRef.current;
        const model = editor?.getModel();
        const selection = editor?.getSelection();

        if (model && selection && !selection.isEmpty()) {
            return {
                sql: model.getValueInRange(selection),
                mode: 'selection' as const
            };
        }

        if (model) {
            const position = editor?.getPosition();
            if (position) {
                const cursorOffset = model.getOffsetAt(position);
                const statement = statementAtCursor(tab.queryText, cursorOffset);
                if (statement?.sql.trim()) {
                    return {
                        sql: statement.sql,
                        mode: 'statement' as const
                    };
                }
            }
        }

        return {
            sql: tab.queryText,
            mode: 'all' as const
        };
    }, []);

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

        const resolvedSql = resolveRunnableSqlForTab(activeTab);
        void executeSqlForTab(activeTab.id, resolvedSql.sql, resolvedSql.mode);
    }, [activeTab, executeSqlForTab, resolveRunnableSqlForTab]);

    const handleExplain = useCallback(() => {
        if (!activeTab) {
            return;
        }

        const resolvedSql = resolveRunnableSqlForTab(activeTab);
        const sqlToExplain = resolvedSql.sql.trim();
        if (!sqlToExplain) {
            setCopyFeedback('Select SQL text first, or use Run All.');
            return;
        }

        const explainSql = /^explain\b/i.test(sqlToExplain)
            ? sqlToExplain
            : `EXPLAIN ${sqlToExplain}`;
        void executeSqlForTab(activeTab.id, explainSql, 'explain', 'explain');
    }, [activeTab, executeSqlForTab, resolveRunnableSqlForTab]);

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

    useEffect(() => {
        if (!activeTab?.executionId || activeTab.executionStatus !== 'SUCCEEDED') {
            return;
        }

        setResultGridScrollTop(0);
        void fetchQueryResultsPage(activeTab.id, activeTab.executionId, firstPageToken, []);
    }, [
        activeTab?.executionId,
        activeTab?.executionStatus,
        activeTab?.id,
        fetchQueryResultsPage,
        resultsPageSize
    ]);

    const handleToggleResultSort = useCallback((columnIndex: number) => {
        setResultSortState((current) => {
            if (!current || current.columnIndex !== columnIndex) {
                return { columnIndex, direction: 'asc' };
            }
            if (current.direction === 'asc') {
                return { columnIndex, direction: 'desc' };
            }
            return null;
        });
    }, []);

    const writeTextToClipboard = useCallback(async (value: string) => {
        if (navigator.clipboard?.writeText) {
            await navigator.clipboard.writeText(value);
            return;
        }

        const textarea = document.createElement('textarea');
        textarea.value = value;
        textarea.setAttribute('readonly', 'true');
        textarea.style.position = 'absolute';
        textarea.style.left = '-9999px';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
    }, []);

    const handleCopyCell = useCallback(
        async (value: string | null) => {
            try {
                await writeTextToClipboard(value ?? '');
                setCopyFeedback('Copied cell value to clipboard.');
            } catch {
                setCopyFeedback('Unable to copy cell value.');
            }
        },
        [writeTextToClipboard]
    );

    useEffect(() => {
        if (!copyFeedback) {
            return;
        }

        const timeout = window.setTimeout(() => {
            setCopyFeedback('');
        }, 3000);

        return () => {
            window.clearTimeout(timeout);
        };
    }, [copyFeedback]);

    const handleExportCsv = useCallback(async () => {
        if (!activeTab?.executionId) {
            setCopyFeedback('Run a query first to export CSV.');
            return;
        }

        setExportingCsv(true);
        try {
            const queryParams = new URLSearchParams();
            queryParams.set('headers', exportIncludeHeaders ? 'true' : 'false');

            const response = await fetch(
                `/api/queries/${activeTab.executionId}/export.csv?${queryParams.toString()}`,
                {
                    method: 'GET',
                    credentials: 'include'
                }
            );
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const blob = await response.blob();
            const fallbackName = `query-${activeTab.executionId}.csv`;
            const disposition = response.headers.get('Content-Disposition') ?? '';
            const fileNameMatch = disposition.match(/filename="?([^";]+)"?/i);
            const fileName = fileNameMatch?.[1] ?? fallbackName;

            const objectUrl = window.URL.createObjectURL(blob);
            const anchor = document.createElement('a');
            anchor.href = objectUrl;
            anchor.download = fileName;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            window.URL.revokeObjectURL(objectUrl);
            setCopyFeedback(`CSV export downloaded: ${fileName}`);
            setShowExportMenu(false);
        } catch (error) {
            const message = error instanceof Error ? error.message : 'CSV export failed.';
            setCopyFeedback(message);
        } finally {
            setExportingCsv(false);
        }
    }, [activeTab, exportIncludeHeaders, readFriendlyError]);

    const handleOpenHistoryEntry = useCallback(
        (entry: QueryHistoryEntryResponse, runImmediately: boolean) => {
            const sqlText = entry.queryText?.trim();
            if (!sqlText) {
                setWorkspaceError('This history entry has no reusable SQL text.');
                return;
            }

            const resolvedDatasourceId = visibleDatasources.some((d) => d.id === entry.datasourceId)
                ? entry.datasourceId
                : (visibleDatasources[0]?.id ?? '');
            if (!resolvedDatasourceId) {
                setWorkspaceError('No permitted connection is available for rerun.');
                return;
            }

            const createdTab = buildWorkspaceTab(
                resolvedDatasourceId,
                `History ${workspaceTabsRef.current.length + 1}`,
                sqlText
            );
            setWorkspaceTabs((currentTabs) => [...currentTabs, createdTab]);
            setActiveTabId(createdTab.id);
            setActiveSection('workbench');

            if (runImmediately) {
                window.setTimeout(() => {
                    void executeSqlForTab(createdTab.id, sqlText, 'all');
                }, 0);
            }
        },
        [executeSqlForTab, visibleDatasources]
    );

    const handleInsertTextIntoActiveQuery = useCallback(
        (text: string) => {
            if (!activeTab) {
                return;
            }

            const editor = editorRef.current;
            const model = editor?.getModel();
            const selection = editor?.getSelection();
            if (model && selection) {
                const startOffset = model.getOffsetAt(selection.getStartPosition());
                const endOffset = model.getOffsetAt(selection.getEndPosition());
                const nextQuery =
                    activeTab.queryText.slice(0, startOffset) +
                    text +
                    activeTab.queryText.slice(endOffset);
                updateWorkspaceTab(activeTab.id, (currentTab) => ({
                    ...currentTab,
                    queryText: nextQuery
                }));
                return;
            }

            const separator = activeTab.queryText.trim().endsWith(';') ? '\n' : ' ';
            const nextQuery = `${activeTab.queryText}${separator}${text}`.trim();
            updateWorkspaceTab(activeTab.id, (currentTab) => ({
                ...currentTab,
                queryText: nextQuery
            }));
        },
        [activeTab, updateWorkspaceTab]
    );

    const handleFormatSql = useCallback(() => {
        if (!activeTab) {
            return;
        }

        const sourceSql = activeTab.queryText;
        if (!sourceSql.trim()) {
            setCopyFeedback('Nothing to format.');
            return;
        }

        try {
            const editor = editorRef.current;
            const model = editor?.getModel();
            const selection = editor?.getSelection();

            if (model && selection && !selection.isEmpty()) {
                const selectedSql = model.getValueInRange(selection);
                const formattedSelection = formatSql(selectedSql, { language: 'postgresql' });
                const startOffset = model.getOffsetAt(selection.getStartPosition());
                const endOffset = model.getOffsetAt(selection.getEndPosition());
                const nextSql =
                    sourceSql.slice(0, startOffset) +
                    formattedSelection +
                    sourceSql.slice(endOffset);
                updateWorkspaceTab(activeTab.id, (currentTab) => ({
                    ...currentTab,
                    queryText: nextSql
                }));
            } else {
                const formattedSql = formatSql(sourceSql, { language: 'postgresql' });
                updateWorkspaceTab(activeTab.id, (currentTab) => ({
                    ...currentTab,
                    queryText: formattedSql
                }));
            }

            setCopyFeedback('SQL formatted successfully.');
        } catch (error) {
            const message = error instanceof Error ? error.message : 'SQL formatting failed.';
            setCopyFeedback(message);
        }
    }, [activeTab, updateWorkspaceTab]);

    const persistSnippet = useCallback(
        async (title: string, sqlText: string, groupId: string | null) => {
            if (!title.trim()) {
                setSnippetError('Snippet title is required.');
                return false;
            }
            if (!sqlText.trim()) {
                setSnippetError('Cannot save an empty snippet.');
                return false;
            }

            setSavingSnippet(true);
            setSnippetError('');
            try {
                const csrfToken = await fetchCsrfToken();
                const response = await fetch('/api/snippets', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrfToken.headerName]: csrfToken.token
                    },
                    body: JSON.stringify({
                        title: title.trim(),
                        sql: sqlText,
                        groupId
                    })
                });
                if (!response.ok) {
                    throw new Error(await readFriendlyError(response));
                }

                await loadSnippets();
                setCopyFeedback('Snippet saved.');
                return true;
            } catch (error) {
                const message = error instanceof Error ? error.message : 'Failed to save snippet.';
                setSnippetError(message);
                return false;
            } finally {
                setSavingSnippet(false);
            }
        },
        [fetchCsrfToken, loadSnippets, readFriendlyError]
    );

    const handleSaveSnippetFromEditor = useCallback(async () => {
        if (!activeTab) {
            return;
        }
        if (!activeTab.queryText.trim()) {
            setSnippetError('Cannot save an empty snippet.');
            return;
        }

        const proposedTitle = window.prompt('Snippet title', activeTab.title);
        if (proposedTitle === null) {
            return;
        }
        const title = proposedTitle.trim();
        if (!title) {
            setSnippetError('Snippet title is required.');
            return;
        }

        const defaultGroupId = snippetGroupInput.trim() || currentUser?.groups?.[0] || '';
        const proposedGroupId = window.prompt(
            'Group ID (optional, leave blank for personal snippet)',
            defaultGroupId
        );
        if (proposedGroupId === null) {
            return;
        }

        const normalizedGroupId = proposedGroupId.trim() ? proposedGroupId.trim() : null;
        const saved = await persistSnippet(title, activeTab.queryText, normalizedGroupId);
        if (saved) {
            setSnippetGroupInput(normalizedGroupId ?? '');
        }
    }, [activeTab, currentUser?.groups, persistSnippet, snippetGroupInput]);

    const handleOpenSnippet = useCallback(
        (snippet: SnippetResponse, runImmediately: boolean) => {
            const resolvedDatasourceId = activeTab?.datasourceId ?? visibleDatasources[0]?.id ?? '';
            if (!resolvedDatasourceId) {
                setSnippetError('No permitted connection is available to open this snippet.');
                return;
            }

            const createdTab = buildWorkspaceTab(
                resolvedDatasourceId,
                `Snippet ${workspaceTabsRef.current.length + 1}`,
                snippet.sql
            );
            setWorkspaceTabs((currentTabs) => [...currentTabs, createdTab]);
            setActiveTabId(createdTab.id);
            setActiveSection('workbench');

            if (runImmediately) {
                window.setTimeout(() => {
                    void executeSqlForTab(createdTab.id, snippet.sql, 'all');
                }, 0);
            }
        },
        [activeTab?.datasourceId, executeSqlForTab, visibleDatasources]
    );

    const handleDeleteSnippet = useCallback(
        async (snippetId: string) => {
            setSnippetError('');
            try {
                const csrfToken = await fetchCsrfToken();
                const response = await fetch(`/api/snippets/${snippetId}`, {
                    method: 'DELETE',
                    credentials: 'include',
                    headers: {
                        [csrfToken.headerName]: csrfToken.token
                    }
                });
                if (!response.ok) {
                    throw new Error(await readFriendlyError(response));
                }

                await loadSnippets();
            } catch (error) {
                const message =
                    error instanceof Error ? error.message : 'Failed to delete snippet.';
                setSnippetError(message);
            }
        },
        [fetchCsrfToken, loadSnippets, readFriendlyError]
    );

    const updateEditorCursorLegend = useCallback(
        (editorInstance: MonacoEditorNamespace.IStandaloneCodeEditor | null) => {
            const model = editorInstance?.getModel();
            if (!editorInstance || !model) {
                setEditorCursorLegend({
                    line: 1,
                    column: 1,
                    position: 1,
                    selectedChars: 0,
                    selectedLines: 0
                });
                return;
            }

            const position = editorInstance.getPosition() ?? {
                lineNumber: 1,
                column: 1
            };
            const absolutePosition = model.getOffsetAt(position) + 1;
            const selection = editorInstance.getSelection();
            let selectedChars = 0;
            let selectedLines = 0;

            if (selection && !selection.isEmpty()) {
                const startOffset = model.getOffsetAt(selection.getStartPosition());
                const endOffset = model.getOffsetAt(selection.getEndPosition());
                selectedChars = Math.max(0, endOffset - startOffset);
                selectedLines = Math.abs(selection.endLineNumber - selection.startLineNumber) + 1;
            }

            setEditorCursorLegend({
                line: position.lineNumber,
                column: position.column,
                position: absolutePosition,
                selectedChars,
                selectedLines
            });
        },
        []
    );

    const handleEditorWillMount: BeforeMount = (monacoInstance) => {
        monacoInstance.editor.defineTheme('dwarvenpick-sql', {
            base: 'vs',
            inherit: true,
            rules: [
                { token: 'keyword', foreground: '0b5cad', fontStyle: 'bold' },
                { token: 'number', foreground: '8b3da6' },
                { token: 'string', foreground: '1f7a45' },
                { token: 'comment', foreground: '758596', fontStyle: 'italic' },
                { token: 'delimiter', foreground: '2a3b4c' }
            ],
            colors: {
                'editor.background': '#f2e9dc',
                'editorLineNumber.foreground': '#8ea2b1',
                'editorLineNumber.activeForeground': '#304759',
                'editorCursor.foreground': '#0b5cad',
                'editor.selectionBackground': '#e3d2b3'
            }
        });

        monacoInstance.editor.defineTheme('dwarvenpick-sql-dark', {
            base: 'vs-dark',
            inherit: true,
            rules: [
                { token: 'keyword', foreground: 'd6a057', fontStyle: 'bold' },
                { token: 'number', foreground: 'b48ead' },
                { token: 'string', foreground: '90c57a' },
                { token: 'comment', foreground: '7f6a55', fontStyle: 'italic' },
                { token: 'delimiter', foreground: 'd0c4b4' }
            ],
            colors: {
                'editor.background': '#15110d',
                'editorLineNumber.foreground': '#7f6a55',
                'editorLineNumber.activeForeground': '#f3e8d9',
                'editorCursor.foreground': '#d6a057',
                'editor.selectionBackground': '#3a2b1f'
            }
        });
    };

    const handleEditorDidMount: OnMount = (editorInstance, monacoInstance) => {
        editorRef.current = editorInstance;
        monacoRef.current = monacoInstance;
        const model = editorInstance.getModel();
        if (model && model.getLanguageId() !== 'sql') {
            monacoInstance.editor.setModelLanguage(model, 'sql');
        }
        setMonacoReady(true);
        setMonacoLoadTimedOut(false);
        setAutocompleteDiagnostics((current) => ({
            ...current,
            monacoReady: true,
            editorMounted: true,
            modelLanguageId: editorInstance.getModel()?.getLanguageId() ?? ''
        }));
        editorInstance.focus();
        updateEditorCursorLegend(editorInstance);

        const disposables = [
            editorInstance.onDidChangeCursorPosition(() =>
                updateEditorCursorLegend(editorInstance)
            ),
            editorInstance.onDidChangeCursorSelection(() =>
                updateEditorCursorLegend(editorInstance)
            ),
            editorInstance.onDidChangeModelContent((event) => {
                updateEditorCursorLegend(editorInstance);

                const shouldTriggerSuggest = event.changes.some(
                    (change) =>
                        change.rangeLength === 0 &&
                        change.text.length > 0 &&
                        change.text.length <= 2 &&
                        /[A-Za-z0-9]/.test(change.text)
                );
                if (!shouldTriggerSuggest || !editorInstance.hasTextFocus()) {
                    return;
                }

                window.requestAnimationFrame(() => {
                    if (editorRef.current !== editorInstance) {
                        return;
                    }
                    triggerAutocompleteSuggest('model-content', editorInstance);
                });
            }),
            editorInstance.onKeyUp((event) => {
                const key = event.browserEvent.key;
                if (!/^[A-Za-z0-9]$/.test(key)) {
                    return;
                }

                window.requestAnimationFrame(() => {
                    if (editorRef.current !== editorInstance) {
                        return;
                    }
                    triggerAutocompleteSuggest('key-up', editorInstance);
                });
            }),
            editorInstance.onDidChangeModelLanguage(() => {
                setAutocompleteDiagnostics((current) => ({
                    ...current,
                    modelLanguageId: editorInstance.getModel()?.getLanguageId() ?? ''
                }));
            }),
            editorInstance.onDidPaste(() => {
                window.requestAnimationFrame(() => {
                    if (editorRef.current !== editorInstance) {
                        return;
                    }
                    triggerAutocompleteSuggest('manual-retry', editorInstance);
                });
            })
        ];

        editorInstance.onDidDispose(() => {
            disposables.forEach((disposable) => disposable.dispose());
        });
    };

    useEffect(() => {
        updateEditorCursorLegend(editorRef.current);
    }, [activeTabId, updateEditorCursorLegend]);

    const handleDatasourceChangeForActiveTab = (nextDatasourceId: string) => {
        if (!activeTab) {
            return;
        }

        if (!visibleDatasources.some((datasource) => datasource.id === nextDatasourceId)) {
            updateWorkspaceTab(activeTab.id, (currentTab) => ({
                ...currentTab,
                errorMessage:
                    'Selected connection is not permitted for this account. Choose a valid connection.',
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
                'Switching connection changes execution context for this tab. Continue?'
            );
            if (!confirmed) {
                return;
            }
        }

        updateWorkspaceTab(activeTab.id, (currentTab) => ({
            ...currentTab,
            datasourceId: nextDatasourceId,
            schema: '',
            requestedCredentialProfile: '',
            statusMessage: `Connection context set to ${nextDatasourceId}.`,
            errorMessage: ''
        }));
    };

    const handleRetryEditorLoad = useCallback(() => {
        setMonacoReady(false);
        setMonacoLoadTimedOut(false);
        setEditorRenderKey((current) => current + 1);
    }, []);

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
            completionProviderRef.current?.dispose();
            completionProviderRef.current = null;
            monacoRef.current = null;
        },
        []
    );

    const handleCreateLocalUser = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setAdminSuccess('');

        if (!localAuthEnabled) {
            setAdminError('Local authentication is disabled. User creation is unavailable.');
            return;
        }

        const username = normalizeAdminIdentifier(localUserUsernameInput);
        const email = localUserEmailInput.trim();
        const password = localUserPasswordInput;
        if (!username) {
            setAdminError('Username is required.');
            return;
        }
        if (!adminIdentifierPattern.test(username)) {
            setAdminError(
                "Username must start with a letter and contain only lowercase letters, numbers, '.' and '-'."
            );
            return;
        }
        if (email && !isValidEmailAddress(email)) {
            setAdminError('Email must be a valid email address.');
            return;
        }
        if (!password.trim()) {
            setAdminError('Password is required.');
            return;
        }

        setCreatingLocalUser(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch('/api/auth/admin/users', {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfToken.headerName]: csrfToken.token
                },
                body: JSON.stringify({
                    username,
                    displayName: localUserDisplayNameInput.trim() || null,
                    email: email || null,
                    password,
                    temporaryPassword: localUserTemporaryPassword,
                    systemAdmin: localUserSystemAdmin
                })
            });
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            setLocalUserUsernameInput('');
            setLocalUserDisplayNameInput('');
            setLocalUserEmailInput('');
            setLocalUserPasswordInput('');
            setLocalUserTemporaryPassword(true);
            setLocalUserSystemAdmin(false);
            await loadAdminData(true, true);
            setAdminSuccess(`User '${username}' created.`);
            setUserAdminMode('list');
        } catch (error) {
            setAdminError(error instanceof Error ? error.message : 'Failed to create user.');
        } finally {
            setCreatingLocalUser(false);
        }
    };

    const handleResetLocalPassword = async (username: string) => {
        setAdminError('');
        setAdminSuccess('');

        if (!localAuthEnabled) {
            setAdminError('Local authentication is disabled. Password reset is unavailable.');
            return;
        }

        const newPassword = window.prompt(`Enter a new password for ${username}`);
        if (newPassword === null) {
            return;
        }
        if (!newPassword.trim()) {
            setAdminError('Password reset canceled: password cannot be empty.');
            return;
        }

        const temporary = window.confirm(
            'Should this password be temporary and require a reset on next login?'
        );

        setResettingLocalUser(username);
        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch(
                `/api/auth/admin/users/${encodeURIComponent(username)}/reset-password`,
                {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrfToken.headerName]: csrfToken.token
                    },
                    body: JSON.stringify({
                        newPassword,
                        temporary
                    })
                }
            );
            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            await loadAdminData(true, true);
            setAdminSuccess(
                temporary
                    ? `Temporary password reset for '${username}' completed.`
                    : `Password reset for '${username}' completed.`
            );
        } catch (error) {
            setAdminError(error instanceof Error ? error.message : 'Failed to reset password.');
        } finally {
            setResettingLocalUser(null);
        }
    };

    const handleStartEditUserDisplayName = useCallback(
        (user: AdminUserResponse) => {
            setAdminError('');
            setAdminSuccess('');

            if (!localAuthEnabled) {
                setAdminError('Local authentication is disabled. User edits are unavailable.');
                return;
            }
            if (user.provider !== 'local') {
                setAdminError('LDAP managed users cannot be edited here.');
                return;
            }

            setEditingUserDisplayName(user.username);
            setEditingUserDisplayNameDraft(user.displayName);
        },
        [localAuthEnabled]
    );

    const handleCancelEditUserDisplayName = useCallback(() => {
        setEditingUserDisplayName(null);
        setEditingUserDisplayNameDraft('');
        setSavingUserDisplayName(null);
    }, []);

    const handleSaveUserDisplayName = useCallback(
        async (username: string) => {
            setAdminError('');
            setAdminSuccess('');

            if (!localAuthEnabled) {
                setAdminError('Local authentication is disabled. User edits are unavailable.');
                return;
            }

            setSavingUserDisplayName(username);
            try {
                const csrfToken = await fetchCsrfToken();
                const response = await fetch(
                    `/api/auth/admin/users/${encodeURIComponent(username)}`,
                    {
                        method: 'PATCH',
                        credentials: 'include',
                        headers: {
                            'Content-Type': 'application/json',
                            [csrfToken.headerName]: csrfToken.token
                        },
                        body: JSON.stringify({
                            displayName: editingUserDisplayNameDraft.trim() || null
                        })
                    }
                );
                if (!response.ok) {
                    throw new Error(await readFriendlyError(response));
                }

                await loadAdminData(true, true);
                setAdminSuccess(`Updated display name for '${username}'.`);
                setEditingUserDisplayName(null);
                setEditingUserDisplayNameDraft('');
            } catch (error) {
                setAdminError(
                    error instanceof Error ? error.message : 'Failed to update display name.'
                );
            } finally {
                setSavingUserDisplayName(null);
            }
        },
        [
            editingUserDisplayNameDraft,
            fetchCsrfToken,
            loadAdminData,
            localAuthEnabled,
            readFriendlyError
        ]
    );

    const handleCreateGroup = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setAdminSuccess('');

        const name = normalizeAdminIdentifier(groupNameInput);
        if (!name) {
            setAdminError('Group name is required.');
            return;
        }
        if (!adminIdentifierPattern.test(name)) {
            setAdminError(
                "Group name must start with a letter and contain only lowercase letters, numbers, '.' and '-'."
            );
            return;
        }

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
                    name,
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
            setGroupAdminMode('list');
            setSelectedGroupForEdit('');
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

    const handleStartCreateGroup = useCallback(() => {
        setGroupNameInput('');
        setGroupDescriptionInput('');
        setSelectedGroupForEdit('');
        setGroupAdminMode('create');
        setAdminError('');
        setAdminSuccess('');
    }, []);

    const handleStartEditGroup = useCallback(
        (groupId: string) => {
            const group = adminGroups.find((candidate) => candidate.id === groupId);
            if (!group) {
                return;
            }

            setSelectedGroupForEdit(groupId);
            setGroupDescriptionDrafts((drafts) => ({
                ...drafts,
                [groupId]: group.description ?? ''
            }));
            setGroupAdminMode('edit');
            setAdminError('');
            setAdminSuccess('');
        },
        [adminGroups]
    );

    const handleCancelGroupAdmin = useCallback(() => {
        setGroupAdminMode('list');
        setSelectedGroupForEdit('');
        setAdminError('');
    }, []);

    const handleDeleteGroup = useCallback(
        async (groupId: string) => {
            if (protectedGroupIds.has(groupId)) {
                setAdminError('System groups cannot be deleted.');
                return;
            }

            const confirmed = window.confirm(
                `Delete group '${groupId}'? This will remove all related access rules.`
            );
            if (!confirmed) {
                return;
            }

            setAdminError('');
            setAdminSuccess('');
            try {
                const csrfToken = await fetchCsrfToken();
                const response = await fetch(`/api/admin/groups/${encodeURIComponent(groupId)}`, {
                    method: 'DELETE',
                    credentials: 'include',
                    headers: {
                        [csrfToken.headerName]: csrfToken.token
                    }
                });
                if (!response.ok) {
                    throw new Error(await readFriendlyError(response));
                }

                await loadAdminData(true, true);
                setGroupAdminMode('list');
                setSelectedGroupForEdit('');
                setAdminSuccess(`Deleted group ${groupId}.`);
            } catch (error) {
                setAdminError(error instanceof Error ? error.message : 'Failed to delete group.');
            }
        },
        [fetchCsrfToken, loadAdminData, readFriendlyError]
    );

    const handleSaveDatasourceAccess = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setAdminSuccess('');

        if (!selectedGroupId || !selectedDatasourceForAccess) {
            setAdminError('Select both group and connection.');
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
                        readOnly,
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
            setAdminSuccess('Connection access mapping saved.');
            setAccessAdminMode('list');
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to save connection mapping.');
            }
        } finally {
            setSavingAccess(false);
        }
    };

    const handleStartCreateAccessRule = useCallback(() => {
        setSelectedGroupId(adminGroups[0]?.id ?? '');
        setSelectedDatasourceForAccess(adminDatasourceCatalog[0]?.id ?? '');
        setCanQuery(true);
        setCanExport(false);
        setReadOnly(true);
        setMaxRowsPerQuery('');
        setMaxRuntimeSeconds('');
        setConcurrencyLimit('');
        setCredentialProfile('');
        setAccessAdminMode('create');
        setAdminError('');
        setAdminSuccess('');
    }, [adminDatasourceCatalog, adminGroups]);

    const handleStartEditAccessRule = useCallback((rule: DatasourceAccessResponse) => {
        setSelectedGroupId(rule.groupId);
        setSelectedDatasourceForAccess(rule.datasourceId);
        setCanQuery(rule.canQuery);
        setCanExport(rule.canExport);
        setReadOnly(rule.readOnly);
        setMaxRowsPerQuery(rule.maxRowsPerQuery?.toString() ?? '');
        setMaxRuntimeSeconds(rule.maxRuntimeSeconds?.toString() ?? '');
        setConcurrencyLimit(rule.concurrencyLimit?.toString() ?? '');
        setCredentialProfile(rule.credentialProfile);
        setAccessAdminMode('edit');
        setAdminError('');
        setAdminSuccess('');
    }, []);

    const handleCancelAccessAdmin = useCallback(() => {
        setAccessAdminMode('list');
        setAdminError('');
    }, []);

    const handleDeleteDatasourceAccess = useCallback(
        async (groupId: string, datasourceId: string) => {
            const confirmed = window.confirm(`Delete access rule '${groupId} -> ${datasourceId}'?`);
            if (!confirmed) {
                return;
            }

            setAdminError('');
            setAdminSuccess('');
            try {
                const csrfToken = await fetchCsrfToken();
                const response = await fetch(
                    `/api/admin/datasource-access/${encodeURIComponent(groupId)}/${encodeURIComponent(datasourceId)}`,
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

                await loadAdminData();
                setAdminSuccess(`Deleted access rule ${groupId} -> ${datasourceId}.`);
            } catch (error) {
                setAdminError(
                    error instanceof Error ? error.message : 'Failed to delete access rule.'
                );
            }
        },
        [fetchCsrfToken, loadAdminData, readFriendlyError]
    );

    const handleStartCreateUser = useCallback(() => {
        setUserAdminMode('create');
        setAdminError('');
        setAdminSuccess('');
    }, []);

    const handleCancelUserAdmin = useCallback(() => {
        setUserAdminMode('list');
        setAdminError('');
    }, []);

    const parsePositiveInteger = (value: string, fieldName: string): number => {
        const parsed = Number(value);
        if (!Number.isInteger(parsed) || parsed < 1) {
            throw new Error(`${fieldName} must be a positive whole number.`);
        }
        return parsed;
    };

    const upsertPrimaryCredentialProfile = useCallback(
        async (
            datasourceId: string,
            existingProfiles: ManagedCredentialProfileResponse[],
            csrfToken: CsrfTokenResponse
        ): Promise<string> => {
            const profileId = managedDatasourceForm.credentialProfileId.trim() || 'admin-ro';
            const noAuth = managedDatasourceForm.authentication === 'NO_AUTH';
            const username = noAuth ? '' : managedDatasourceForm.credentialUsername.trim();
            const password = noAuth ? '' : managedDatasourceForm.credentialPassword;
            const description = managedDatasourceForm.credentialDescription.trim()
                ? managedDatasourceForm.credentialDescription.trim()
                : null;
            const existingProfile = existingProfiles.find(
                (profile) => profile.profileId === profileId
            );

            if (!noAuth && !username) {
                throw new Error('Credential username is required.');
            }

            if (!noAuth && existingProfile && !password.trim()) {
                const existingDescription = existingProfile.description ?? '';
                if (
                    existingProfile.username !== username ||
                    existingDescription !== (description ?? '')
                ) {
                    throw new Error(
                        'Enter the credential password to update username or description.'
                    );
                }
                return profileId;
            }

            if (!noAuth && !existingProfile && !password.trim()) {
                throw new Error('Credential password is required for a new connection profile.');
            }

            const response = await fetch(
                `/api/admin/datasource-management/${datasourceId}/credentials/${profileId}`,
                {
                    method: 'PUT',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/json',
                        [csrfToken.headerName]: csrfToken.token
                    },
                    body: JSON.stringify({
                        username,
                        password,
                        description
                    })
                }
            );

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            return profileId;
        },
        [managedDatasourceForm, readFriendlyError]
    );

    const handlePrepareNewDatasource = useCallback(() => {
        const defaultEngine = managedDatasourceForm.engine;
        const empty = buildBlankDatasourceForm(defaultEngine);
        const candidates = adminDrivers.filter((driver) => driver.engine === defaultEngine);
        const preferredDriver = candidates.find((driver) => driver.available) ?? candidates[0];

        setSelectedManagedDatasourceId('');
        setManagedDatasourceForm({
            ...empty,
            driverId: preferredDriver?.driverId ?? ''
        });
        setCredentialProfileIdInput('admin-ro');
        setCredentialUsernameInput('');
        setCredentialPasswordInput('');
        setCredentialDescriptionInput('');
        setSelectedCredentialProfileForTest('');
        setTestConnectionMessage('');
        setTestConnectionOutcome('');
        setUploadDriverClassInput('');
        setUploadDriverDescriptionInput('');
        setUploadDriverJarFile(null);
        setTlsCaCertificatePemInput(null);
        setTlsCaCertificateFileName('');
        setTlsClientCertificatePemInput(null);
        setTlsClientCertificateFileName('');
        setTlsClientKeyPemInput(null);
        setTlsClientKeyFileName('');
        setAdminError('');
        setAdminSuccess('');
    }, [adminDrivers, managedDatasourceForm.engine]);

    const handleStartCreateManagedDatasource = useCallback(() => {
        handlePrepareNewDatasource();
        setConnectionEditorMode('create');
    }, [handlePrepareNewDatasource]);

    const handleStartEditManagedDatasource = useCallback((datasourceId: string) => {
        setSelectedManagedDatasourceId(datasourceId);
        setConnectionEditorMode('edit');
        setAdminError('');
        setAdminSuccess('');
    }, []);

    const handleCancelConnectionEditor = useCallback(() => {
        const confirmed = window.confirm(
            'Cancel editing this connection? Unsaved changes will not be saved.'
        );
        if (!confirmed) {
            return;
        }

        setConnectionEditorMode('list');
        setAdminError('');
        setAdminSuccess('');
    }, []);

    const handleSaveManagedDatasource = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAdminError('');
        setAdminSuccess('');

        const connectionName = managedDatasourceForm.name.trim();
        if (!connectionName) {
            setAdminError('Connection name is required.');
            return;
        }
        if (!adminIdentifierPattern.test(connectionName)) {
            setAdminError(
                "Connection name must start with a letter and contain only lowercase letters, numbers, '.' and '-'."
            );
            return;
        }

        if (
            managedDatasourceForm.connectionType === 'HOST_PORT' &&
            !managedDatasourceForm.host.trim()
        ) {
            setAdminError('Connection host is required.');
            return;
        }

        if (!managedDatasourceForm.driverId.trim()) {
            setAdminError('Select a JDBC driver.');
            return;
        }

        setSavingDatasource(true);
        try {
            const parsedOptions = parseOptionsInput(managedDatasourceForm.optionsInput);
            if (managedDatasourceForm.connectionType === 'JDBC_URL') {
                if (!managedDatasourceForm.jdbcUrl.trim()) {
                    throw new Error('JDBC URL is required for URL connection type.');
                }
                parsedOptions.jdbcUrl = managedDatasourceForm.jdbcUrl.trim();
            } else {
                delete parsedOptions.jdbcUrl;
            }

            const fallbackPort = defaultPortByEngine[managedDatasourceForm.engine].toString();
            const portInput = managedDatasourceForm.port.trim() || fallbackPort;
            const port = parsePositiveInteger(portInput, 'Port');
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

            const host =
                managedDatasourceForm.host.trim() ||
                (() => {
                    const hostMatch = managedDatasourceForm.jdbcUrl.match(
                        /^jdbc:[^:]+:\/\/([^:/?#]+)/
                    );
                    return hostMatch?.[1] ?? 'localhost';
                })();

            const tlsCertificatesPayload: Record<string, string> = {};
            const shouldSendTlsCertificates =
                tlsCaCertificatePemInput !== null ||
                tlsClientCertificatePemInput !== null ||
                tlsClientKeyPemInput !== null;
            if (tlsCaCertificatePemInput !== null) {
                tlsCertificatesPayload.caCertificatePem = tlsCaCertificatePemInput;
            }
            if (tlsClientCertificatePemInput !== null) {
                tlsCertificatesPayload.clientCertificatePem = tlsClientCertificatePemInput;
            }
            if (tlsClientKeyPemInput !== null) {
                tlsCertificatesPayload.clientKeyPem = tlsClientKeyPemInput;
            }

            const commonPayload = {
                name: connectionName,
                host,
                port,
                database: managedDatasourceForm.database.trim()
                    ? managedDatasourceForm.database.trim()
                    : null,
                driverId: managedDatasourceForm.driverId,
                options: parsedOptions,
                pool: poolPayload,
                tls: {
                    mode: managedDatasourceForm.tlsMode,
                    verifyServerCertificate: managedDatasourceForm.verifyServerCertificate,
                    allowSelfSigned: managedDatasourceForm.allowSelfSigned
                },
                ...(shouldSendTlsCertificates ? { tlsCertificates: tlsCertificatesPayload } : {})
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
            const selectedProfile = await upsertPrimaryCredentialProfile(
                savedDatasource.id,
                savedDatasource.credentialProfiles,
                csrfToken
            );

            setManagedDatasourceForm((current) => ({
                ...current,
                credentialPassword: ''
            }));
            setCredentialProfileIdInput(
                managedDatasourceForm.credentialProfileId.trim() || 'admin-ro'
            );
            setCredentialUsernameInput(
                managedDatasourceForm.authentication === 'NO_AUTH'
                    ? ''
                    : managedDatasourceForm.credentialUsername
            );
            setCredentialDescriptionInput(managedDatasourceForm.credentialDescription);
            setCredentialPasswordInput('');
            setSelectedManagedDatasourceId(savedDatasource.id);
            setSelectedCredentialProfileForTest(selectedProfile);
            setTlsCaCertificatePemInput(null);
            setTlsCaCertificateFileName('');
            setTlsClientCertificatePemInput(null);
            setTlsClientCertificateFileName('');
            setTlsClientKeyPemInput(null);
            setTlsClientKeyFileName('');
            await loadAdminData();
            setConnectionEditorMode('list');
            setAdminSuccess(
                selectedManagedDatasource
                    ? `Connection ${savedDatasource.id} updated.`
                    : `Connection ${savedDatasource.id} created.`
            );
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to save connection.');
            }
        } finally {
            setSavingDatasource(false);
        }
    };

    const handleUploadDriver = async () => {
        if (!uploadDriverJarFile) {
            setAdminError('Select a driver jar file to upload.');
            return;
        }

        if (!uploadDriverClassInput.trim()) {
            setAdminError('Driver class is required for upload.');
            return;
        }

        setAdminError('');
        setAdminSuccess('');
        setUploadingDriver(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const formData = new FormData();
            formData.append('engine', managedDatasourceForm.engine);
            formData.append('driverClass', uploadDriverClassInput.trim());
            formData.append('jarFile', uploadDriverJarFile);
            if (uploadDriverDescriptionInput.trim()) {
                formData.append('description', uploadDriverDescriptionInput.trim());
            }

            const response = await fetch('/api/admin/drivers/upload', {
                method: 'POST',
                credentials: 'include',
                headers: {
                    [csrfToken.headerName]: csrfToken.token
                },
                body: formData
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const uploadedDriver = (await response.json()) as DriverDescriptorResponse;
            await loadAdminData();
            setManagedDatasourceForm((current) => ({
                ...current,
                driverId: uploadedDriver.driverId
            }));
            setUploadDriverJarFile(null);
            setUploadDriverClassInput('');
            setUploadDriverDescriptionInput('');
            setAdminSuccess(`Driver ${uploadedDriver.driverId} uploaded.`);
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to upload JDBC driver.');
            }
        } finally {
            setUploadingDriver(false);
        }
    };

    const handleInstallMavenDriver = async () => {
        const version = mavenDriverVersionInput.trim();
        if (!version) {
            setAdminError('Driver version is required.');
            return;
        }

        setAdminError('');
        setAdminSuccess('');
        setInstallingMavenDriver(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch('/api/admin/drivers/install-maven', {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfToken.headerName]: csrfToken.token
                },
                body: JSON.stringify({
                    preset: mavenDriverPreset,
                    version
                })
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            const installedDriver = (await response.json()) as DriverDescriptorResponse;
            await loadAdminData();
            setManagedDatasourceForm((current) => ({
                ...current,
                driverId: installedDriver.driverId
            }));
            setMavenDriverVersionInput('');
            setAdminSuccess(`Driver ${installedDriver.driverId} installed.`);
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to install JDBC driver.');
            }
        } finally {
            setInstallingMavenDriver(false);
        }
    };

    const handleDeleteManagedDatasource = async (datasource: ManagedDatasourceResponse) => {
        if (!datasource) {
            setAdminError('Select a connection to delete.');
            return;
        }

        const confirmationInput = window.prompt(
            `Type "${datasource.name}" to delete this connection.\n\nConnection ID: ${datasource.id}`,
            datasource.name
        );
        if (confirmationInput === null) {
            return;
        }

        if (confirmationInput.trim() !== datasource.name) {
            setAdminError('Connection name did not match. Delete canceled.');
            return;
        }

        setAdminError('');
        setAdminSuccess('');
        setDeletingDatasource(true);
        try {
            const csrfToken = await fetchCsrfToken();
            const response = await fetch(`/api/admin/datasource-management/${datasource.id}`, {
                method: 'DELETE',
                credentials: 'include',
                headers: {
                    [csrfToken.headerName]: csrfToken.token
                }
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            setSelectedManagedDatasourceId('');
            setConnectionEditorMode('list');
            await loadAdminData();
            setAdminSuccess(`Connection ${datasource.id} deleted.`);
        } catch (error) {
            if (error instanceof Error) {
                setAdminError(error.message);
            } else {
                setAdminError('Failed to delete connection.');
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
            setAdminError('Select a connection first.');
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
            setAdminError('Select a connection first.');
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
            <AppShell title="dwarvenpick" showTitle={false} className="workspace-app-shell">
                <section className="panel">
                    <p>Loading...</p>
                </section>
            </AppShell>
        );
    }

    return (
        <AppShell
            title="dwarvenpick"
            showTitle={false}
            topNav={false}
            className="workspace-app-shell"
        >
            {workspaceError ? (
                <section className="panel">
                    <p className="form-error">{workspaceError}</p>
                </section>
            ) : null}

            <div className="workspace-shell">
                <aside
                    className={
                        leftRailCollapsed
                            ? 'workspace-left-rail is-collapsed'
                            : 'workspace-left-rail'
                    }
                >
                    <div className="workspace-left-rail-head">
                        <button
                            type="button"
                            className="workspace-logo"
                            onClick={() =>
                                window.open(
                                    'https://en.wikipedia.org/wiki/Dwarves_in_Middle-earth',
                                    '_blank',
                                    'noopener,noreferrer'
                                )
                            }
                            title="Open dwarves in Middle-earth reference"
                        >
                            <span className="workspace-logo-icon" aria-hidden>
                                <img src="/dwarvenpick-mark.svg" alt="" width={24} height={24} />
                            </span>
                            {!leftRailCollapsed ? (
                                <span className="workspace-logo-label">dwarvenpick</span>
                            ) : null}
                        </button>
                        <button
                            type="button"
                            className="workspace-rail-toggle"
                            onClick={() => setLeftRailCollapsed((current) => !current)}
                            title={leftRailCollapsed ? 'Expand menu' : 'Collapse menu'}
                            aria-label={leftRailCollapsed ? 'Expand menu' : 'Collapse menu'}
                        >
                            <RailIcon glyph={leftRailCollapsed ? 'menu' : 'collapse'} />
                        </button>
                    </div>
                    <div className="workspace-left-rail-separator" aria-hidden />
                    <nav
                        className="workspace-mode-tabs"
                        role="tablist"
                        aria-label="Workspace sections"
                    >
                        <button
                            type="button"
                            role="tab"
                            className={
                                activeSection === 'workbench'
                                    ? 'workspace-mode-tab active'
                                    : 'workspace-mode-tab'
                            }
                            aria-selected={activeSection === 'workbench'}
                            onClick={() => {
                                setCollapsedAdminSubmenuOpen(false);
                                setActiveSection('workbench');
                            }}
                            title={leftRailCollapsed ? 'Workbench' : undefined}
                        >
                            <span className="workspace-mode-icon">
                                <RailIcon glyph="workbench" />
                            </span>
                            {!leftRailCollapsed ? <span>Workbench</span> : null}
                        </button>
                        <button
                            type="button"
                            role="tab"
                            className={
                                activeSection === 'history'
                                    ? 'workspace-mode-tab active'
                                    : 'workspace-mode-tab'
                            }
                            aria-selected={activeSection === 'history'}
                            onClick={() => {
                                setCollapsedAdminSubmenuOpen(false);
                                setActiveSection('history');
                            }}
                            title={leftRailCollapsed ? 'Query History' : undefined}
                        >
                            <span className="workspace-mode-icon">
                                <RailIcon glyph="history" />
                            </span>
                            {!leftRailCollapsed ? <span>Query History</span> : null}
                        </button>
                        <button
                            type="button"
                            role="tab"
                            className={
                                activeSection === 'snippets'
                                    ? 'workspace-mode-tab active'
                                    : 'workspace-mode-tab'
                            }
                            aria-selected={activeSection === 'snippets'}
                            onClick={() => {
                                setCollapsedAdminSubmenuOpen(false);
                                setActiveSection('snippets');
                            }}
                            title={leftRailCollapsed ? 'Snippets' : undefined}
                        >
                            <span className="workspace-mode-icon">
                                <RailIcon glyph="snippets" />
                            </span>
                            {!leftRailCollapsed ? <span>Snippets</span> : null}
                        </button>
                        {isSystemAdmin ? (
                            <button
                                type="button"
                                role="tab"
                                className={
                                    activeSection === 'connections'
                                        ? 'workspace-mode-tab active'
                                        : 'workspace-mode-tab'
                                }
                                aria-selected={activeSection === 'connections'}
                                onClick={() => {
                                    setCollapsedAdminSubmenuOpen(false);
                                    setActiveSection('connections');
                                }}
                                title={leftRailCollapsed ? 'Connections' : undefined}
                            >
                                <span className="workspace-mode-icon">
                                    <RailIcon glyph="connections" />
                                </span>
                                {!leftRailCollapsed ? <span>Connections</span> : null}
                            </button>
                        ) : null}
                        {isSystemAdmin ? (
                            <button
                                type="button"
                                role="tab"
                                className={
                                    activeSection === 'audit'
                                        ? 'workspace-mode-tab active'
                                        : 'workspace-mode-tab'
                                }
                                aria-selected={activeSection === 'audit'}
                                onClick={() => {
                                    setCollapsedAdminSubmenuOpen(false);
                                    setActiveSection('audit');
                                }}
                                title={leftRailCollapsed ? 'Audit Events' : undefined}
                            >
                                <span className="workspace-mode-icon">
                                    <RailIcon glyph="audit" />
                                </span>
                                {!leftRailCollapsed ? <span>Audit Events</span> : null}
                            </button>
                        ) : null}
                        {isSystemAdmin ? (
                            <button
                                type="button"
                                role="tab"
                                className={
                                    activeSection === 'health'
                                        ? 'workspace-mode-tab active'
                                        : 'workspace-mode-tab'
                                }
                                aria-selected={activeSection === 'health'}
                                onClick={() => {
                                    setCollapsedAdminSubmenuOpen(false);
                                    setActiveSection('health');
                                }}
                                title={leftRailCollapsed ? 'System Health' : undefined}
                            >
                                <span className="workspace-mode-icon">
                                    <RailIcon glyph="health" />
                                </span>
                                {!leftRailCollapsed ? <span>System Health</span> : null}
                            </button>
                        ) : null}
                        {isSystemAdmin ? (
                            <div
                                className="workspace-admin-menu-anchor"
                                ref={collapsedAdminAnchorRef}
                            >
                                <button
                                    type="button"
                                    role="tab"
                                    className={
                                        activeSection === 'admin' || collapsedAdminSubmenuOpen
                                            ? 'workspace-mode-tab active'
                                            : 'workspace-mode-tab'
                                    }
                                    aria-selected={activeSection === 'admin'}
                                    onClick={() => {
                                        if (leftRailCollapsed) {
                                            setCollapsedAdminSubmenuOpen((current) => !current);
                                            return;
                                        }

                                        setCollapsedAdminSubmenuOpen(false);
                                        setActiveSection('admin');
                                    }}
                                    title={leftRailCollapsed ? 'Admin' : undefined}
                                >
                                    <span className="workspace-mode-icon">
                                        <RailIcon glyph="admin" />
                                    </span>
                                    {!leftRailCollapsed ? <span>Admin</span> : null}
                                </button>
                                {leftRailCollapsed && collapsedAdminSubmenuOpen ? (
                                    <div
                                        className="workspace-admin-submenu workspace-admin-submenu-flyout"
                                        ref={collapsedAdminSubmenuRef}
                                    >
                                        {localAuthEnabled ? (
                                            <button
                                                type="button"
                                                className={
                                                    activeAdminSubsection === 'users'
                                                        ? 'workspace-admin-submenu-item active'
                                                        : 'workspace-admin-submenu-item'
                                                }
                                                onClick={() => {
                                                    setActiveAdminSubsection('users');
                                                    setActiveSection('admin');
                                                    setCollapsedAdminSubmenuOpen(false);
                                                }}
                                            >
                                                Users
                                            </button>
                                        ) : null}
                                        <button
                                            type="button"
                                            className={
                                                activeAdminSubsection === 'groups'
                                                    ? 'workspace-admin-submenu-item active'
                                                    : 'workspace-admin-submenu-item'
                                            }
                                            onClick={() => {
                                                setActiveAdminSubsection('groups');
                                                setActiveSection('admin');
                                                setCollapsedAdminSubmenuOpen(false);
                                            }}
                                        >
                                            Groups
                                        </button>
                                        <button
                                            type="button"
                                            className={
                                                activeAdminSubsection === 'access'
                                                    ? 'workspace-admin-submenu-item active'
                                                    : 'workspace-admin-submenu-item'
                                            }
                                            onClick={() => {
                                                setActiveAdminSubsection('access');
                                                setActiveSection('admin');
                                                setCollapsedAdminSubmenuOpen(false);
                                            }}
                                        >
                                            Access
                                        </button>
                                    </div>
                                ) : null}
                            </div>
                        ) : null}
                        {isSystemAdmin && activeSection === 'admin' && !leftRailCollapsed ? (
                            <div className="workspace-admin-submenu">
                                {localAuthEnabled ? (
                                    <button
                                        type="button"
                                        className={
                                            activeAdminSubsection === 'users'
                                                ? 'workspace-admin-submenu-item active'
                                                : 'workspace-admin-submenu-item'
                                        }
                                        onClick={() => setActiveAdminSubsection('users')}
                                    >
                                        Users
                                    </button>
                                ) : null}
                                <button
                                    type="button"
                                    className={
                                        activeAdminSubsection === 'groups'
                                            ? 'workspace-admin-submenu-item active'
                                            : 'workspace-admin-submenu-item'
                                    }
                                    onClick={() => setActiveAdminSubsection('groups')}
                                >
                                    Groups
                                </button>
                                <button
                                    type="button"
                                    className={
                                        activeAdminSubsection === 'access'
                                            ? 'workspace-admin-submenu-item active'
                                            : 'workspace-admin-submenu-item'
                                    }
                                    onClick={() => setActiveAdminSubsection('access')}
                                >
                                    Access
                                </button>
                            </div>
                        ) : null}
                    </nav>

                    <div className="workspace-left-footer">
                        {currentUser ? (
                            <div className="workspace-left-user" ref={leftRailUserMenuRef}>
                                <button
                                    type="button"
                                    className="workspace-left-user-trigger"
                                    onClick={() => setLeftRailUserMenuOpen((current) => !current)}
                                    title={leftRailCollapsed ? currentUser.displayName : undefined}
                                >
                                    <span className="workspace-left-user-avatar">
                                        {currentUser.displayName.charAt(0).toUpperCase()}
                                    </span>
                                    {!leftRailCollapsed ? (
                                        <span className="workspace-left-user-meta">
                                            <strong>{currentUser.displayName}</strong>
                                            <small>@{currentUser.username}</small>
                                        </span>
                                    ) : null}
                                </button>
                                {leftRailUserMenuOpen ? (
                                    <div className="workspace-left-user-menu" role="menu">
                                        {currentUser.email ? <p>{currentUser.email}</p> : null}
                                        <button
                                            type="button"
                                            className="workspace-left-user-menu-action"
                                            onClick={toggleTheme}
                                        >
                                            <span
                                                className="workspace-left-user-menu-icon"
                                                aria-hidden
                                            >
                                                {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
                                            </span>
                                            {theme === 'dark' ? 'Light mode' : 'Dark mode'}
                                        </button>
                                        <button
                                            type="button"
                                            className="danger-button"
                                            onClick={() => {
                                                setLeftRailUserMenuOpen(false);
                                                void handleLogout();
                                            }}
                                        >
                                            Logout
                                        </button>
                                    </div>
                                ) : null}
                            </div>
                        ) : null}

                        <div className="workspace-version-wrap" ref={versionInfoRef}>
                            {!leftRailCollapsed ? (
                                <div
                                    className="workspace-version-flag"
                                    title={`Running version ${appVersionLabel}`}
                                    aria-label={`Running version ${appVersionLabel}`}
                                >
                                    <span className="workspace-version-value">
                                        {`version ${appVersionLabel}`}
                                    </span>
                                </div>
                            ) : (
                                <button
                                    type="button"
                                    className="workspace-version-icon"
                                    title={`Version ${appVersionLabel}`}
                                    aria-label={`Version ${appVersionLabel}`}
                                    onClick={() => setShowVersionInfo((current) => !current)}
                                >
                                    <RailIcon glyph="info" />
                                </button>
                            )}
                            {leftRailCollapsed && showVersionInfo ? (
                                <div className="workspace-version-popover">
                                    <span className="workspace-version-value">
                                        {`version ${appVersionLabel}`}
                                    </span>
                                </div>
                            ) : null}
                        </div>
                    </div>
                </aside>

                <section className="workspace-main">
                    <div
                        ref={workbenchGridRef}
                        style={
                            workbenchResultsSizePx === null
                                ? undefined
                                : ({
                                      '--workbench-results-size': `${workbenchResultsSizePx}px`
                                  } as CSSProperties)
                        }
                        className={
                            showSchemaBrowser
                                ? 'workspace-grid'
                                : 'workspace-grid is-explorer-collapsed'
                        }
                        hidden={activeSection !== 'workbench'}
                    >
                        <aside className={showSchemaBrowser ? 'sidebar' : 'sidebar is-collapsed'}>
                            <section
                                className={
                                    showSchemaBrowser
                                        ? 'schema-browser'
                                        : 'schema-browser is-collapsed'
                                }
                            >
                                <div className="row schema-browser-header">
                                    {showSchemaBrowser ? <h3>Explorer</h3> : null}
                                    {showSchemaBrowser ? (
                                        <button
                                            type="button"
                                            className="icon-button schema-browser-toggle"
                                            title={
                                                loadingSchemaBrowser
                                                    ? 'Refreshing explorer metadata...'
                                                    : 'Refresh explorer metadata'
                                            }
                                            aria-label={
                                                loadingSchemaBrowser
                                                    ? 'Refreshing explorer metadata...'
                                                    : 'Refresh explorer metadata'
                                            }
                                            disabled={
                                                !activeTab?.datasourceId || loadingSchemaBrowser
                                            }
                                            onClick={() =>
                                                activeTab?.datasourceId
                                                    ? void loadSchemaBrowser(
                                                          activeTab.datasourceId,
                                                          true
                                                      )
                                                    : undefined
                                            }
                                        >
                                            <ExplorerRefreshIcon />
                                        </button>
                                    ) : null}
                                    <button
                                        type="button"
                                        className="icon-button schema-browser-toggle"
                                        onClick={() => setShowSchemaBrowser((current) => !current)}
                                        title={
                                            showSchemaBrowser
                                                ? 'Collapse explorer'
                                                : 'Expand explorer'
                                        }
                                        aria-label={
                                            showSchemaBrowser
                                                ? 'Collapse explorer'
                                                : 'Expand explorer'
                                        }
                                    >
                                        <span className="icon-button-glyph" aria-hidden>
                                            <RailIcon
                                                glyph={showSchemaBrowser ? 'collapse' : 'menu'}
                                            />
                                        </span>
                                    </button>
                                </div>
                                <div
                                    className={
                                        showSchemaBrowser
                                            ? 'explorer-body'
                                            : 'explorer-body is-collapsed'
                                    }
                                    aria-hidden={!showSchemaBrowser}
                                >
                                    <div className="explorer-toolbar-fields">
                                        <div className="explorer-toolbar-label-row">
                                            <span className="tile-heading-icon" aria-hidden>
                                                <RailIcon glyph="connections" />
                                            </span>
                                            <span className="explorer-toolbar-label-text">
                                                Connection
                                            </span>
                                        </div>
                                        <div className="explorer-toolbar-control-row">
                                            <div className="editor-connection-picker">
                                                <span
                                                    className={`editor-connection-health tone-${selectedDatasourceHealth}`}
                                                    title={`Connection status: ${selectedDatasourceHealthLabel}`}
                                                    aria-label={`Connection status ${selectedDatasourceHealthLabel}`}
                                                />
                                                <span
                                                    className="editor-connection-icon"
                                                    aria-hidden
                                                >
                                                    <img
                                                        src={selectedDatasourceIcon}
                                                        alt=""
                                                        width={16}
                                                        height={16}
                                                    />
                                                </span>
                                                <div className="select-wrap">
                                                    <select
                                                        id="tab-datasource"
                                                        aria-label="Active tab connection"
                                                        value={activeTab?.datasourceId ?? ''}
                                                        onChange={(event) =>
                                                            handleDatasourceChangeForActiveTab(
                                                                event.target.value
                                                            )
                                                        }
                                                        disabled={
                                                            !activeTab || activeTab.isExecuting
                                                        }
                                                    >
                                                        {visibleDatasources.map((datasource) => (
                                                            <option
                                                                key={`tab-ds-${datasource.id}`}
                                                                value={datasource.id}
                                                            >
                                                                {datasource.name}
                                                            </option>
                                                        ))}
                                                    </select>
                                                </div>
                                            </div>
                                        </div>
                                        {isSystemAdmin && activeTab ? (
                                            <>
                                                <div className="explorer-toolbar-label-row">
                                                    <span className="tile-heading-icon" aria-hidden>
                                                        <ExplorerIcon glyph="database" />
                                                    </span>
                                                    <label
                                                        htmlFor="tab-credential-profile"
                                                        className="explorer-toolbar-label-text"
                                                    >
                                                        Credential Profile
                                                    </label>
                                                </div>
                                                <div className="explorer-toolbar-control-row">
                                                    <div className="select-wrap">
                                                        <select
                                                            id="tab-credential-profile"
                                                            aria-label="Credential profile override"
                                                            value={
                                                                activeTab.requestedCredentialProfile
                                                            }
                                                            onChange={(event) => {
                                                                updateWorkspaceTab(
                                                                    activeTab.id,
                                                                    (currentTab) => ({
                                                                        ...currentTab,
                                                                        requestedCredentialProfile:
                                                                            event.target.value
                                                                    })
                                                                );
                                                            }}
                                                            disabled={activeTab.isExecuting}
                                                        >
                                                            <option value="">Auto (RBAC)</option>
                                                            {(
                                                                visibleDatasources.find(
                                                                    (datasource) =>
                                                                        datasource.id ===
                                                                        activeTab.datasourceId
                                                                )?.credentialProfiles ?? []
                                                            ).map((profile) => (
                                                                <option
                                                                    key={`credential-profile-${profile}`}
                                                                    value={profile}
                                                                >
                                                                    {profile}
                                                                </option>
                                                            ))}
                                                        </select>
                                                    </div>
                                                </div>
                                            </>
                                        ) : null}
                                        <div className="explorer-toolbar-label-row">
                                            <span className="tile-heading-icon" aria-hidden>
                                                <ExplorerIcon glyph="schema" />
                                            </span>
                                            <label
                                                htmlFor="tab-schema"
                                                className="explorer-toolbar-label-text"
                                            >
                                                Default Schema
                                            </label>
                                        </div>
                                        <div className="explorer-toolbar-control-row">
                                            <div className="select-wrap">
                                                <select
                                                    id="tab-schema"
                                                    aria-label="Default schema"
                                                    value={activeTab?.schema ?? ''}
                                                    onChange={(event) => {
                                                        if (!activeTab) {
                                                            return;
                                                        }

                                                        updateWorkspaceTab(
                                                            activeTab.id,
                                                            (currentTab) => ({
                                                                ...currentTab,
                                                                schema: event.target.value
                                                            })
                                                        );
                                                    }}
                                                    disabled={!activeTab}
                                                >
                                                    <option value="">None</option>
                                                    {availableSchemaNames.map((schemaName) => (
                                                        <option
                                                            key={`schema-option-${schemaName}`}
                                                            value={schemaName}
                                                        >
                                                            {schemaName}
                                                        </option>
                                                    ))}
                                                    {activeTab?.schema &&
                                                    !availableSchemaNames.includes(
                                                        activeTab.schema
                                                    ) ? (
                                                        <option value={activeTab.schema}>
                                                            {activeTab.schema}
                                                        </option>
                                                    ) : null}
                                                </select>
                                            </div>
                                        </div>
                                    </div>
                                    {schemaBrowserError ? (
                                        <p className="form-error">{schemaBrowserError}</p>
                                    ) : null}
                                    {loadingSchemaBrowser && !schemaBrowser ? (
                                        <p className="explorer-empty">Loading explorer...</p>
                                    ) : null}
                                    {schemaBrowser ? (
                                        <ul className="explorer-tree explorer-root" role="tree">
                                            {(() => {
                                                const datasourceKey = `datasource:${schemaBrowser.datasourceId}`;
                                                const datasourceExpanded =
                                                    expandedExplorerDatasources[datasourceKey] ??
                                                    true;
                                                const activeDatasource =
                                                    visibleDatasources.find(
                                                        (datasource) =>
                                                            datasource.id ===
                                                            schemaBrowser.datasourceId
                                                    ) ?? null;
                                                return (
                                                    <li className="explorer-node explorer-depth-0">
                                                        <div
                                                            className={
                                                                selectedExplorerNode ===
                                                                datasourceKey
                                                                    ? 'explorer-node-row selected'
                                                                    : 'explorer-node-row'
                                                            }
                                                        >
                                                            <button
                                                                type="button"
                                                                className="explorer-toggle"
                                                                onClick={() =>
                                                                    setExpandedExplorerDatasources(
                                                                        (current) => ({
                                                                            ...current,
                                                                            [datasourceKey]:
                                                                                !datasourceExpanded
                                                                        })
                                                                    )
                                                                }
                                                                title={
                                                                    datasourceExpanded
                                                                        ? 'Collapse connection'
                                                                        : 'Expand connection'
                                                                }
                                                            >
                                                                <ChevronIcon
                                                                    expanded={datasourceExpanded}
                                                                />
                                                            </button>
                                                            <div className="explorer-item">
                                                                <button
                                                                    type="button"
                                                                    className="explorer-item-main"
                                                                    onClick={() =>
                                                                        setSelectedExplorerNode(
                                                                            datasourceKey
                                                                        )
                                                                    }
                                                                    title={
                                                                        activeDatasource?.name ??
                                                                        schemaBrowser.datasourceId
                                                                    }
                                                                >
                                                                    <span className="explorer-item-icon">
                                                                        <ExplorerIcon glyph="database" />
                                                                    </span>
                                                                    <span className="explorer-item-title">
                                                                        {activeDatasource?.name ??
                                                                            schemaBrowser.datasourceId}
                                                                    </span>
                                                                </button>
                                                                <span className="explorer-item-tail">
                                                                    {activeDatasource ? (
                                                                        <span className="explorer-item-meta">
                                                                            {
                                                                                activeDatasource.engine
                                                                            }
                                                                        </span>
                                                                    ) : null}
                                                                    <span
                                                                        className="explorer-item-count"
                                                                        title="Schema count"
                                                                    >
                                                                        {
                                                                            schemaBrowser.schemas
                                                                                .length
                                                                        }
                                                                    </span>
                                                                </span>
                                                            </div>
                                                        </div>
                                                        {datasourceExpanded ? (
                                                            <ul className="explorer-children">
                                                                {schemaBrowser.schemas.length ===
                                                                0 ? (
                                                                    <li className="explorer-empty">
                                                                        No schemas discovered.
                                                                    </li>
                                                                ) : (
                                                                    schemaBrowser.schemas.map(
                                                                        (schemaEntry) => {
                                                                            const schemaKey = `${datasourceKey}:schema:${schemaEntry.schema}`;
                                                                            const schemaExpanded =
                                                                                expandedExplorerSchemas[
                                                                                    schemaKey
                                                                                ] ?? false;
                                                                            return (
                                                                                <li
                                                                                    key={schemaKey}
                                                                                    className="explorer-node explorer-depth-1"
                                                                                >
                                                                                    <div
                                                                                        className={
                                                                                            selectedExplorerNode ===
                                                                                            schemaKey
                                                                                                ? 'explorer-node-row selected'
                                                                                                : 'explorer-node-row'
                                                                                        }
                                                                                    >
                                                                                        <button
                                                                                            type="button"
                                                                                            className="explorer-toggle"
                                                                                            onClick={() =>
                                                                                                setExpandedExplorerSchemas(
                                                                                                    (
                                                                                                        current
                                                                                                    ) => ({
                                                                                                        ...current,
                                                                                                        [schemaKey]:
                                                                                                            !schemaExpanded
                                                                                                    })
                                                                                                )
                                                                                            }
                                                                                            title={
                                                                                                schemaExpanded
                                                                                                    ? 'Collapse schema'
                                                                                                    : 'Expand schema'
                                                                                            }
                                                                                        >
                                                                                            <ChevronIcon
                                                                                                expanded={
                                                                                                    schemaExpanded
                                                                                                }
                                                                                            />
                                                                                        </button>
                                                                                        <div className="explorer-item">
                                                                                            <button
                                                                                                type="button"
                                                                                                className="explorer-item-main"
                                                                                                onClick={() =>
                                                                                                    setSelectedExplorerNode(
                                                                                                        schemaKey
                                                                                                    )
                                                                                                }
                                                                                            >
                                                                                                <span className="explorer-item-icon">
                                                                                                    <ExplorerIcon glyph="schema" />
                                                                                                </span>
                                                                                                <span className="explorer-item-title">
                                                                                                    {
                                                                                                        schemaEntry.schema
                                                                                                    }
                                                                                                </span>
                                                                                            </button>
                                                                                            <span className="explorer-item-tail">
                                                                                                <span
                                                                                                    className="explorer-item-count"
                                                                                                    title="Table count"
                                                                                                >
                                                                                                    {
                                                                                                        schemaEntry
                                                                                                            .tables
                                                                                                            .length
                                                                                                    }
                                                                                                </span>
                                                                                                <button
                                                                                                    type="button"
                                                                                                    className="explorer-insert-button"
                                                                                                    title="Insert schema name into editor"
                                                                                                    aria-label={`Insert schema ${schemaEntry.schema}`}
                                                                                                    onClick={(
                                                                                                        event
                                                                                                    ) => {
                                                                                                        event.stopPropagation();
                                                                                                        handleInsertTextIntoActiveQuery(
                                                                                                            schemaEntry.schema
                                                                                                        );
                                                                                                    }}
                                                                                                >
                                                                                                    <ExplorerInsertIcon />
                                                                                                </button>
                                                                                            </span>
                                                                                        </div>
                                                                                    </div>
                                                                                    {schemaExpanded ? (
                                                                                        <ul className="explorer-children">
                                                                                            {schemaEntry.tables.map(
                                                                                                (
                                                                                                    tableEntry
                                                                                                ) => {
                                                                                                    const tableKey = `${schemaKey}:table:${tableEntry.table}`;
                                                                                                    const tableExpanded =
                                                                                                        expandedExplorerTables[
                                                                                                            tableKey
                                                                                                        ] ??
                                                                                                        false;
                                                                                                    return (
                                                                                                        <li
                                                                                                            key={
                                                                                                                tableKey
                                                                                                            }
                                                                                                            className="explorer-node explorer-depth-2"
                                                                                                        >
                                                                                                            <div
                                                                                                                className={
                                                                                                                    selectedExplorerNode ===
                                                                                                                    tableKey
                                                                                                                        ? 'explorer-node-row selected'
                                                                                                                        : 'explorer-node-row'
                                                                                                                }
                                                                                                            >
                                                                                                                {tableEntry
                                                                                                                    .columns
                                                                                                                    .length >
                                                                                                                0 ? (
                                                                                                                    <button
                                                                                                                        type="button"
                                                                                                                        className="explorer-toggle"
                                                                                                                        onClick={() =>
                                                                                                                            setExpandedExplorerTables(
                                                                                                                                (
                                                                                                                                    current
                                                                                                                                ) => ({
                                                                                                                                    ...current,
                                                                                                                                    [tableKey]:
                                                                                                                                        !tableExpanded
                                                                                                                                })
                                                                                                                            )
                                                                                                                        }
                                                                                                                        title={
                                                                                                                            tableExpanded
                                                                                                                                ? 'Collapse table columns'
                                                                                                                                : 'Expand table columns'
                                                                                                                        }
                                                                                                                    >
                                                                                                                        <ChevronIcon
                                                                                                                            expanded={
                                                                                                                                tableExpanded
                                                                                                                            }
                                                                                                                        />
                                                                                                                    </button>
                                                                                                                ) : (
                                                                                                                    <span className="explorer-toggle-spacer" />
                                                                                                                )}
                                                                                                                <div className="explorer-item">
                                                                                                                    <button
                                                                                                                        type="button"
                                                                                                                        className="explorer-item-main"
                                                                                                                        onClick={() =>
                                                                                                                            setSelectedExplorerNode(
                                                                                                                                tableKey
                                                                                                                            )
                                                                                                                        }
                                                                                                                    >
                                                                                                                        <span className="explorer-item-icon">
                                                                                                                            <ExplorerIcon glyph="table" />
                                                                                                                        </span>
                                                                                                                        <span className="explorer-item-title">
                                                                                                                            {
                                                                                                                                tableEntry.table
                                                                                                                            }
                                                                                                                        </span>
                                                                                                                    </button>
                                                                                                                    <span className="explorer-item-tail">
                                                                                                                        <span
                                                                                                                            className="explorer-item-count"
                                                                                                                            title="Column count"
                                                                                                                        >
                                                                                                                            {
                                                                                                                                tableEntry
                                                                                                                                    .columns
                                                                                                                                    .length
                                                                                                                            }
                                                                                                                        </span>
                                                                                                                        <button
                                                                                                                            type="button"
                                                                                                                            className="explorer-insert-button"
                                                                                                                            title="Insert table reference into editor"
                                                                                                                            aria-label={`Insert table ${schemaEntry.schema}.${tableEntry.table}`}
                                                                                                                            onClick={(
                                                                                                                                event
                                                                                                                            ) => {
                                                                                                                                event.stopPropagation();
                                                                                                                                handleInsertTextIntoActiveQuery(
                                                                                                                                    `${schemaEntry.schema}.${tableEntry.table}`
                                                                                                                                );
                                                                                                                            }}
                                                                                                                        >
                                                                                                                            <ExplorerInsertIcon />
                                                                                                                        </button>
                                                                                                                    </span>
                                                                                                                </div>
                                                                                                            </div>
                                                                                                            {tableExpanded &&
                                                                                                            tableEntry
                                                                                                                .columns
                                                                                                                .length >
                                                                                                                0 ? (
                                                                                                                <ul className="explorer-children">
                                                                                                                    {tableEntry.columns.map(
                                                                                                                        (
                                                                                                                            columnEntry
                                                                                                                        ) => {
                                                                                                                            const columnKey = `${tableKey}:column:${columnEntry.name}`;
                                                                                                                            return (
                                                                                                                                <li
                                                                                                                                    key={
                                                                                                                                        columnKey
                                                                                                                                    }
                                                                                                                                    className="explorer-node explorer-depth-3"
                                                                                                                                >
                                                                                                                                    <div
                                                                                                                                        className={
                                                                                                                                            selectedExplorerNode ===
                                                                                                                                            columnKey
                                                                                                                                                ? 'explorer-node-row selected'
                                                                                                                                                : 'explorer-node-row'
                                                                                                                                        }
                                                                                                                                    >
                                                                                                                                        <span className="explorer-toggle-spacer" />
                                                                                                                                        <div className="explorer-item">
                                                                                                                                            <button
                                                                                                                                                type="button"
                                                                                                                                                className="explorer-item-main"
                                                                                                                                                onClick={() =>
                                                                                                                                                    setSelectedExplorerNode(
                                                                                                                                                        columnKey
                                                                                                                                                    )
                                                                                                                                                }
                                                                                                                                            >
                                                                                                                                                <span className="explorer-item-icon">
                                                                                                                                                    <ExplorerIcon glyph="column" />
                                                                                                                                                </span>
                                                                                                                                                <span className="explorer-item-title">
                                                                                                                                                    {
                                                                                                                                                        columnEntry.name
                                                                                                                                                    }
                                                                                                                                                </span>
                                                                                                                                            </button>
                                                                                                                                            <span className="explorer-item-tail">
                                                                                                                                                <span className="explorer-item-type">
                                                                                                                                                    (
                                                                                                                                                    {
                                                                                                                                                        columnEntry.jdbcType
                                                                                                                                                    }

                                                                                                                                                    )
                                                                                                                                                </span>
                                                                                                                                                <button
                                                                                                                                                    type="button"
                                                                                                                                                    className="explorer-insert-button"
                                                                                                                                                    title="Insert column name into editor"
                                                                                                                                                    aria-label={`Insert column ${columnEntry.name}`}
                                                                                                                                                    onClick={(
                                                                                                                                                        event
                                                                                                                                                    ) => {
                                                                                                                                                        event.stopPropagation();
                                                                                                                                                        handleInsertTextIntoActiveQuery(
                                                                                                                                                            columnEntry.name
                                                                                                                                                        );
                                                                                                                                                    }}
                                                                                                                                                >
                                                                                                                                                    <ExplorerInsertIcon />
                                                                                                                                                </button>
                                                                                                                                            </span>
                                                                                                                                        </div>
                                                                                                                                    </div>
                                                                                                                                </li>
                                                                                                                            );
                                                                                                                        }
                                                                                                                    )}
                                                                                                                </ul>
                                                                                                            ) : null}
                                                                                                        </li>
                                                                                                    );
                                                                                                }
                                                                                            )}
                                                                                        </ul>
                                                                                    ) : null}
                                                                                </li>
                                                                            );
                                                                        }
                                                                    )
                                                                )}
                                                            </ul>
                                                        ) : null}
                                                    </li>
                                                );
                                            })()}
                                        </ul>
                                    ) : (
                                        <p className="explorer-empty">
                                            Select a connection to browse schemas and tables.
                                        </p>
                                    )}
                                </div>
                            </section>
                        </aside>

                        <section className="editor">
                            <div className="editor-toolbar">
                                <div className="editor-tabs-row">
                                    <div
                                        className="editor-tabs"
                                        role="tablist"
                                        aria-label="SQL tabs"
                                    >
                                        {workspaceTabs.map((tab) => (
                                            <div
                                                key={tab.id}
                                                role="presentation"
                                                className={
                                                    tab.id === activeTabId
                                                        ? 'editor-tab active'
                                                        : 'editor-tab'
                                                }
                                            >
                                                <button
                                                    type="button"
                                                    role="tab"
                                                    className="editor-tab-trigger"
                                                    aria-selected={tab.id === activeTabId}
                                                    onClick={() => setActiveTabId(tab.id)}
                                                    title={tab.title}
                                                >
                                                    <span>{tab.title}</span>
                                                    {tab.isExecuting ? (
                                                        <span className="editor-tab-running">
                                                            Running
                                                        </span>
                                                    ) : null}
                                                </button>
                                                <button
                                                    type="button"
                                                    className="editor-tab-close"
                                                    title="Close tab"
                                                    aria-label={`Close ${tab.title}`}
                                                    disabled={workspaceTabs.length <= 1}
                                                    onClick={(event) => {
                                                        event.stopPropagation();
                                                        handleCloseTab(tab.id);
                                                    }}
                                                >
                                                    <EditorTabCloseIcon />
                                                </button>
                                                {tab.id === activeTabId ? (
                                                    <div
                                                        className="editor-tab-menu-anchor"
                                                        ref={activeTabMenuAnchorRef}
                                                    >
                                                        <button
                                                            type="button"
                                                            className="editor-tab-menu-trigger"
                                                            title="Tab actions"
                                                            aria-label="Tab actions"
                                                            onClick={(event) => {
                                                                const triggerRect =
                                                                    event.currentTarget.getBoundingClientRect();
                                                                setActiveTabMenuOpen((current) => {
                                                                    const next = !current;
                                                                    if (next) {
                                                                        setActiveTabMenuPosition({
                                                                            top:
                                                                                triggerRect.bottom +
                                                                                6,
                                                                            left: Math.max(
                                                                                12,
                                                                                triggerRect.right -
                                                                                    188
                                                                            )
                                                                        });
                                                                    } else {
                                                                        setActiveTabMenuPosition(
                                                                            null
                                                                        );
                                                                    }
                                                                    return next;
                                                                });
                                                            }}
                                                        >
                                                            <EditorTabMenuIcon />
                                                        </button>
                                                    </div>
                                                ) : null}
                                            </div>
                                        ))}
                                        <button
                                            type="button"
                                            className="editor-tab-add"
                                            onClick={handleOpenNewTab}
                                            title="New tab"
                                            aria-label="New tab"
                                        >
                                            <svg
                                                viewBox="0 0 20 20"
                                                fill="none"
                                                stroke="currentColor"
                                                strokeWidth="1.8"
                                            >
                                                <path d="M10 4v12" />
                                                <path d="M4 10h12" />
                                            </svg>
                                        </button>
                                    </div>
                                </div>
                                {activeTabMenuOpen && activeTab && activeTabMenuPosition ? (
                                    <div
                                        className="editor-tab-menu is-floating"
                                        role="menu"
                                        ref={activeTabMenuRef}
                                        style={{
                                            top: `${activeTabMenuPosition.top}px`,
                                            left: `${activeTabMenuPosition.left}px`
                                        }}
                                    >
                                        <button
                                            type="button"
                                            role="menuitem"
                                            className="editor-tab-menu-item"
                                            onClick={() => {
                                                setActiveTabMenuOpen(false);
                                                setActiveTabMenuPosition(null);
                                                handleRenameTab(activeTab.id);
                                            }}
                                        >
                                            <span className="editor-tab-menu-item-icon" aria-hidden>
                                                <IconGlyph icon="rename" />
                                            </span>
                                            <span>Rename</span>
                                        </button>
                                        <button
                                            type="button"
                                            role="menuitem"
                                            className="editor-tab-menu-item"
                                            onClick={() => {
                                                setActiveTabMenuOpen(false);
                                                setActiveTabMenuPosition(null);
                                                handleDuplicateTab(activeTab.id);
                                            }}
                                        >
                                            <span className="editor-tab-menu-item-icon" aria-hidden>
                                                <IconGlyph icon="duplicate" />
                                            </span>
                                            <span>Duplicate</span>
                                        </button>
                                    </div>
                                ) : null}
                            </div>

                            <div className="monaco-host">
                                <div className="monaco-frame">
                                    {monacoLoadTimedOut ? (
                                        <div className="editor-fallback">
                                            <p className="form-error">
                                                SQL editor failed to initialize. You can continue
                                                using fallback mode.
                                            </p>
                                            <textarea
                                                value={activeTab?.queryText ?? ''}
                                                onChange={(event) => {
                                                    if (!activeTab) {
                                                        return;
                                                    }

                                                    updateWorkspaceTab(
                                                        activeTab.id,
                                                        (currentTab) => ({
                                                            ...currentTab,
                                                            queryText: event.target.value
                                                        })
                                                    );
                                                }}
                                                rows={14}
                                                className="fallback-editor-textarea"
                                            />
                                            <div className="row">
                                                <button
                                                    type="button"
                                                    className="chip"
                                                    onClick={handleRetryEditorLoad}
                                                >
                                                    Retry Monaco Editor
                                                </button>
                                            </div>
                                        </div>
                                    ) : (
                                        <Editor
                                            key={`monaco-${editorRenderKey}`}
                                            height="100%"
                                            language="sql"
                                            beforeMount={handleEditorWillMount}
                                            theme={
                                                theme === 'dark'
                                                    ? 'dwarvenpick-sql-dark'
                                                    : 'dwarvenpick-sql'
                                            }
                                            loading={
                                                <div className="editor-loading">
                                                    Loading SQL editor...
                                                </div>
                                            }
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
                                                lineNumbersMinChars: 4,
                                                fontFamily:
                                                    'JetBrains Mono, IBM Plex Mono, monospace',
                                                fixedOverflowWidgets: true,
                                                bracketPairColorization: { enabled: true },
                                                wordWrap: 'on',
                                                scrollBeyondLastLine: false,
                                                padding: { top: 10, bottom: 10 },
                                                quickSuggestions: {
                                                    comments: false,
                                                    strings: true,
                                                    other: true
                                                },
                                                quickSuggestionsDelay: 75,
                                                suggestOnTriggerCharacters: true,
                                                suggest: {
                                                    showKeywords: true,
                                                    showWords: true,
                                                    showFields: true,
                                                    showStatusBar: true,
                                                    preview: true,
                                                    filterGraceful: true,
                                                    localityBonus: true,
                                                    matchOnWordStartOnly: false
                                                },
                                                tabCompletion: 'on',
                                                wordBasedSuggestions: 'allDocuments'
                                            }}
                                        />
                                    )}
                                </div>
                            </div>

                            <div className="editor-action-row">
                                <div className="row editor-primary-actions">
                                    <button
                                        type="button"
                                        disabled={
                                            !activeTab ||
                                            activeTab.isExecuting ||
                                            !selectedDatasource
                                        }
                                        onClick={handleRunSelection}
                                    >
                                        {activeTab?.isExecuting ? 'Running...' : 'Run Selection'}
                                    </button>
                                    <button
                                        type="button"
                                        disabled={
                                            !activeTab ||
                                            activeTab.isExecuting ||
                                            !selectedDatasource
                                        }
                                        onClick={handleRunAll}
                                    >
                                        Run All
                                    </button>
                                    <button
                                        type="button"
                                        disabled={
                                            !activeTab ||
                                            activeTab.isExecuting ||
                                            !selectedDatasource
                                        }
                                        onClick={handleExplain}
                                    >
                                        Explain
                                    </button>
                                    <button
                                        type="button"
                                        disabled={!activeTab || activeTab.isExecuting}
                                        onClick={handleFormatSql}
                                    >
                                        Format SQL
                                    </button>
                                    <button
                                        type="button"
                                        disabled={!activeTab || !activeTab.isExecuting}
                                        onClick={() => {
                                            if (!activeTab) {
                                                return;
                                            }

                                            void handleCancelRun(activeTab.id);
                                        }}
                                    >
                                        Cancel
                                    </button>
                                </div>
                                <div className="row editor-secondary-actions">
                                    <button
                                        type="button"
                                        className="chip"
                                        disabled={!activeTab || savingSnippet}
                                        onClick={() => void handleSaveSnippetFromEditor()}
                                    >
                                        {savingSnippet ? 'Saving...' : 'Save Snippet'}
                                    </button>
                                    <div
                                        className="editor-shortcuts-wrapper"
                                        ref={editorShortcutsRef}
                                    >
                                        <IconButton
                                            icon="info"
                                            title="Editor shortcuts"
                                            onClick={() =>
                                                setShowEditorShortcuts((current) => !current)
                                            }
                                        />
                                        {showEditorShortcuts ? (
                                            <div className="editor-shortcuts-popover" role="dialog">
                                                <h4>Editor Shortcuts</h4>
                                                <ul>
                                                    <li>
                                                        <kbd>Ctrl/Cmd + Enter</kbd>: Run selection
                                                        (or full tab if no selection)
                                                    </li>
                                                    <li>
                                                        <kbd>Esc</kbd>: Cancel currently running
                                                        execution
                                                    </li>
                                                </ul>
                                            </div>
                                        ) : null}
                                    </div>
                                    <div className="editor-cursor-legend" aria-live="polite">
                                        <span>
                                            Line {editorCursorLegend.line}, Col{' '}
                                            {editorCursorLegend.column}, Pos{' '}
                                            {editorCursorLegend.position}
                                        </span>
                                        {editorCursorLegend.selectedChars > 0 ? (
                                            <span>
                                                {editorCursorLegend.selectedChars} chars,{' '}
                                                {editorCursorLegend.selectedLines} lines selected
                                            </span>
                                        ) : null}
                                    </div>
                                </div>
                            </div>
                        </section>

                        <section className="results" ref={resultsSectionRef}>
                            <div
                                className="workbench-results-resizer"
                                role="separator"
                                aria-label="Resize results panel"
                                aria-orientation="horizontal"
                                title="Drag to resize results panel (double-click to reset)"
                                tabIndex={0}
                                onPointerDown={handleResultsResizerPointerDown}
                                onPointerMove={handleResultsResizerPointerMove}
                                onPointerUp={handleResultsResizerPointerUp}
                                onPointerCancel={handleResultsResizerPointerCancel}
                                onDoubleClick={handleResultsResizerReset}
                                onKeyDown={handleResultsResizerKeyDown}
                            />
                            <div className="results-head">
                                {activeTab?.executionId ? (
                                    <div className="result-stats-grid">
                                        <div className="result-stat">
                                            <span>Status</span>
                                            <strong>
                                                {activeTab.executionStatus || 'PENDING_SUBMISSION'}
                                            </strong>
                                        </div>
                                        <div className="result-stat">
                                            <span>Rows</span>
                                            <strong>{activeTab.rowCount.toLocaleString()}</strong>
                                        </div>
                                        <div className="result-stat">
                                            <span>Columns</span>
                                            <strong>
                                                {activeTab.columnCount.toLocaleString()}
                                            </strong>
                                        </div>
                                        <div className="result-stat">
                                            <span>Duration</span>
                                            <strong>{executionDurationLabel}</strong>
                                        </div>
                                        <div className="result-stat">
                                            <span>Submitted</span>
                                            <strong title={executionSubmittedAtLabel}>
                                                {executionSubmittedAtLabel}
                                            </strong>
                                        </div>
                                        <div className="result-stat">
                                            <span>Completed</span>
                                            <strong title={executionCompletedAtLabel}>
                                                {executionCompletedAtLabel}
                                            </strong>
                                        </div>
                                        <div className="result-stat">
                                            <span>Row Limit</span>
                                            <strong>
                                                {activeTab.maxRowsPerQuery > 0
                                                    ? activeTab.maxRowsPerQuery.toLocaleString()
                                                    : '-'}
                                            </strong>
                                        </div>
                                        <div className="result-stat">
                                            <span>Runtime Limit</span>
                                            <strong>
                                                {activeTab.maxRuntimeSeconds > 0
                                                    ? `${activeTab.maxRuntimeSeconds}s`
                                                    : '-'}
                                            </strong>
                                        </div>
                                    </div>
                                ) : null}
                                {activeTab?.statusMessage && !hideRedundantResultStatusMessage ? (
                                    <p>{activeTab.statusMessage}</p>
                                ) : null}
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
                                {copyFeedback ? (
                                    <p className="form-success">{copyFeedback}</p>
                                ) : null}
                                {explainPlanText ? (
                                    <div className="explain-plan">
                                        <h3>Explain Plan</h3>
                                        <pre>{explainPlanText}</pre>
                                    </div>
                                ) : null}
                                {activeTab?.executionStatus === 'SUCCEEDED' &&
                                activeTab.resultColumns.length === 0 &&
                                !activeTab.errorMessage ? (
                                    <p>Query completed successfully and returned no rows.</p>
                                ) : null}
                                {!activeTab?.executionId &&
                                !activeTab?.statusMessage &&
                                !activeTab?.errorMessage ? (
                                    <p className="results-empty">Results</p>
                                ) : null}
                            </div>

                            {activeTab?.resultColumns.length ? (
                                <div className="results-body">
                                    <div className="result-actions row">
                                        <div className="result-pagination-controls row">
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
                                            <div
                                                className="result-export-wrapper"
                                                ref={exportMenuRef}
                                            >
                                                <IconButton
                                                    icon="download"
                                                    title="Export CSV"
                                                    onClick={() =>
                                                        setShowExportMenu((current) => !current)
                                                    }
                                                    disabled={exportingCsv}
                                                />
                                                {showExportMenu ? (
                                                    <div
                                                        className="result-export-popover"
                                                        role="dialog"
                                                    >
                                                        <label className="checkbox-row">
                                                            <input
                                                                type="checkbox"
                                                                checked={exportIncludeHeaders}
                                                                onChange={(event) =>
                                                                    setExportIncludeHeaders(
                                                                        event.target.checked
                                                                    )
                                                                }
                                                            />
                                                            <span>Include headers</span>
                                                        </label>
                                                        <button
                                                            type="button"
                                                            onClick={() => void handleExportCsv()}
                                                            disabled={exportingCsv}
                                                        >
                                                            {exportingCsv
                                                                ? 'Exporting...'
                                                                : 'Download CSV'}
                                                        </button>
                                                    </div>
                                                ) : null}
                                            </div>
                                        </div>
                                        <div className="result-page-size-inline">
                                            <label htmlFor="result-page-size">Rows per page</label>
                                            <div className="select-wrap">
                                                <select
                                                    id="result-page-size"
                                                    value={resultsPageSize}
                                                    onChange={(event) => {
                                                        setResultsPageSize(
                                                            Number(event.target.value)
                                                        );
                                                        setResultGridScrollTop(0);
                                                    }}
                                                >
                                                    <option value={10}>10</option>
                                                    <option value={100}>100</option>
                                                    <option value={250}>250</option>
                                                    <option value={500}>500</option>
                                                    <option value={1000}>1000</option>
                                                </select>
                                            </div>
                                        </div>
                                    </div>
                                    <div
                                        className="result-table-wrap"
                                        onScroll={(event) =>
                                            setResultGridScrollTop(event.currentTarget.scrollTop)
                                        }
                                    >
                                        <table className="result-table">
                                            <thead>
                                                <tr>
                                                    <th className="result-meta-heading">Row</th>
                                                    {activeTab.resultColumns.map(
                                                        (column, columnIndex) => {
                                                            const direction =
                                                                resultSortState?.columnIndex ===
                                                                columnIndex
                                                                    ? resultSortState.direction
                                                                    : null;

                                                            return (
                                                                <th
                                                                    key={`${column.name}-${column.jdbcType}-${columnIndex}`}
                                                                >
                                                                    <button
                                                                        type="button"
                                                                        className="result-sort-trigger"
                                                                        onClick={() =>
                                                                            handleToggleResultSort(
                                                                                columnIndex
                                                                            )
                                                                        }
                                                                        title={`Sort by ${column.name}`}
                                                                        aria-label={`Sort by ${column.name}`}
                                                                    >
                                                                        <span>{column.name}</span>
                                                                        <ResultSortIcon
                                                                            direction={direction}
                                                                        />
                                                                    </button>
                                                                </th>
                                                            );
                                                        }
                                                    )}
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {visibleResultRows.topSpacerPx > 0 ? (
                                                    <tr>
                                                        <td
                                                            colSpan={
                                                                activeTab.resultColumns.length + 1
                                                            }
                                                            style={{
                                                                height: `${visibleResultRows.topSpacerPx}px`,
                                                                padding: 0,
                                                                border: 'none'
                                                            }}
                                                        />
                                                    </tr>
                                                ) : null}
                                                {visibleResultRows.rows.map(
                                                    (row, relativeIndex) => {
                                                        const absoluteIndex =
                                                            visibleResultRows.start + relativeIndex;
                                                        return (
                                                            <tr key={`row-${absoluteIndex}`}>
                                                                <td className="result-row-index">
                                                                    {absoluteIndex + 1}
                                                                </td>
                                                                {row.map((value, columnIndex) => (
                                                                    <td
                                                                        key={`cell-${absoluteIndex}-${columnIndex}`}
                                                                    >
                                                                        <div className="result-cell">
                                                                            <span>
                                                                                {value ?? 'NULL'}
                                                                            </span>
                                                                            <button
                                                                                type="button"
                                                                                className="result-copy-icon"
                                                                                onClick={() =>
                                                                                    void handleCopyCell(
                                                                                        value
                                                                                    )
                                                                                }
                                                                                title="Copy cell value"
                                                                                aria-label="Copy cell value"
                                                                            >
                                                                                <IconGlyph icon="copy" />
                                                                            </button>
                                                                        </div>
                                                                    </td>
                                                                ))}
                                                            </tr>
                                                        );
                                                    }
                                                )}
                                                {visibleResultRows.bottomSpacerPx > 0 ? (
                                                    <tr>
                                                        <td
                                                            colSpan={
                                                                activeTab.resultColumns.length + 1
                                                            }
                                                            style={{
                                                                height: `${visibleResultRows.bottomSpacerPx}px`,
                                                                padding: 0,
                                                                border: 'none'
                                                            }}
                                                        />
                                                    </tr>
                                                ) : null}
                                            </tbody>
                                        </table>
                                    </div>
                                </div>
                            ) : null}
                        </section>
                    </div>

                    <QueryHistorySection
                        hidden={activeSection !== 'history'}
                        visibleDatasources={visibleDatasources}
                        datasourceFilter={historyDatasourceFilter}
                        onDatasourceFilterChange={(value) => setHistoryDatasourceFilter(value)}
                        statusFilter={historyStatusFilter}
                        onStatusFilterChange={(value) => setHistoryStatusFilter(value)}
                        fromFilter={historyFromFilter}
                        onFromFilterChange={(value) => setHistoryFromFilter(value)}
                        toFilter={historyToFilter}
                        onToFilterChange={(value) => setHistoryToFilter(value)}
                        loadingQueryHistory={loadingQueryHistory}
                        onRefresh={() => void loadQueryHistory()}
                        sortOrder={historySortOrder}
                        onToggleSortOrder={() =>
                            setHistorySortOrder((current) =>
                                current === 'newest' ? 'oldest' : 'newest'
                            )
                        }
                        onClearFilters={() => {
                            setHistoryDatasourceFilter('');
                            setHistoryStatusFilter('');
                            setHistoryFromFilter('');
                            setHistoryToFilter('');
                            window.setTimeout(() => {
                                void loadQueryHistory();
                            }, 0);
                        }}
                        entries={sortedQueryHistoryEntries}
                        onOpenEntry={handleOpenHistoryEntry}
                    />

                    <SnippetsSection
                        hidden={activeSection !== 'snippets'}
                        snippetScope={snippetScope}
                        onSnippetScopeChange={(scope) => setSnippetScope(scope)}
                        snippetTitleInput={snippetTitleInput}
                        onSnippetTitleChange={(value) => setSnippetTitleInput(value)}
                        snippetGroupInput={snippetGroupInput}
                        onSnippetGroupChange={(value) => setSnippetGroupInput(value)}
                        snippetGroupOptions={snippetGroupOptions}
                        loadingSnippets={loadingSnippets}
                        onRefresh={() => void loadSnippets()}
                        snippetError={snippetError}
                        snippets={snippets}
                        onOpenSnippet={handleOpenSnippet}
                        onDeleteSnippet={(snippetId) => void handleDeleteSnippet(snippetId)}
                    />

                    {isSystemAdmin ? (
                        <>
                            <SystemHealthSection
                                hidden={activeSection !== 'health'}
                                visibleDatasources={visibleDatasources}
                                datasourceId={systemHealthDatasourceId}
                                onDatasourceChange={(value) => {
                                    setSystemHealthDatasourceId(value);
                                    setSystemHealthError('');
                                    setSystemHealthResponse(null);

                                    const selected = visibleDatasources.find(
                                        (datasource) => datasource.id === value
                                    );
                                    const defaultProfile = selected?.credentialProfiles[0] ?? '';
                                    setSystemHealthCredentialProfile(defaultProfile);
                                }}
                                credentialProfile={systemHealthCredentialProfile}
                                onCredentialProfileChange={(value) =>
                                    setSystemHealthCredentialProfile(value)
                                }
                                loading={loadingSystemHealth}
                                error={systemHealthError}
                                response={systemHealthResponse}
                                onRefresh={() => void loadSystemHealth()}
                            />
                            <AuditEventsSection
                                hidden={activeSection !== 'audit'}
                                auditActionOptions={auditActionOptions}
                                auditTypeFilter={auditTypeFilter}
                                onAuditTypeFilterChange={(value) => setAuditTypeFilter(value)}
                                auditActorFilter={auditActorFilter}
                                onAuditActorFilterChange={(value) => setAuditActorFilter(value)}
                                auditOutcomeOptions={auditOutcomeOptions}
                                auditOutcomeFilter={auditOutcomeFilter}
                                onAuditOutcomeFilterChange={(value) => setAuditOutcomeFilter(value)}
                                auditFromFilter={auditFromFilter}
                                onAuditFromFilterChange={(value) => setAuditFromFilter(value)}
                                auditToFilter={auditToFilter}
                                onAuditToFilterChange={(value) => setAuditToFilter(value)}
                                loadingAuditEvents={loadingAuditEvents}
                                onRefresh={() => void loadAuditEvents()}
                                auditSortOrder={auditSortOrder}
                                onToggleSortOrder={() =>
                                    setAuditSortOrder((current) =>
                                        current === 'newest' ? 'oldest' : 'newest'
                                    )
                                }
                                onClearFilters={() => {
                                    setAuditTypeFilter('');
                                    setAuditActorFilter('');
                                    setAuditOutcomeFilter('');
                                    setAuditFromFilter('');
                                    setAuditToFilter('');
                                    window.setTimeout(() => {
                                        void loadAuditEvents();
                                    }, 0);
                                }}
                                events={sortedAuditEvents}
                            />

                            <section
                                className="panel admin-console"
                                hidden={activeSection !== 'admin'}
                            >
                                {adminError ? (
                                    <p className="form-error" role="alert">
                                        {adminError}
                                    </p>
                                ) : null}
                                {adminSuccess ? (
                                    <p className="form-success">{adminSuccess}</p>
                                ) : null}

                                {activeAdminSubsection === 'groups' ? (
                                    <>
                                        {groupAdminMode === 'list' ? (
                                            <>
                                                <div className="row toolbar-actions admin-toolbar-actions">
                                                    <button
                                                        type="button"
                                                        onClick={handleStartCreateGroup}
                                                    >
                                                        Create Group
                                                    </button>
                                                </div>
                                                <div className="history-table-wrap">
                                                    <table className="result-table history-table admin-table">
                                                        <thead>
                                                            <tr>
                                                                <th>Name</th>
                                                                <th>Group ID</th>
                                                                <th>Description</th>
                                                                <th>Members</th>
                                                                <th>Actions</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            {adminGroups.length === 0 ? (
                                                                <tr>
                                                                    <td colSpan={5}>
                                                                        No groups found.
                                                                    </td>
                                                                </tr>
                                                            ) : (
                                                                adminGroups.map((group) => (
                                                                    <tr
                                                                        key={`admin-group-row-${group.id}`}
                                                                    >
                                                                        <td>{group.name}</td>
                                                                        <td>{group.id}</td>
                                                                        <td>
                                                                            {group.description ||
                                                                                '-'}
                                                                        </td>
                                                                        <td>
                                                                            {group.members.length}
                                                                        </td>
                                                                        <td className="history-actions">
                                                                            <IconButton
                                                                                icon="rename"
                                                                                title={`Edit group ${group.name}`}
                                                                                onClick={() =>
                                                                                    handleStartEditGroup(
                                                                                        group.id
                                                                                    )
                                                                                }
                                                                            />
                                                                            <IconButton
                                                                                icon="delete"
                                                                                title={
                                                                                    protectedGroupIds.has(
                                                                                        group.id
                                                                                    )
                                                                                        ? 'System groups cannot be deleted'
                                                                                        : `Delete group ${group.name}`
                                                                                }
                                                                                variant="danger"
                                                                                disabled={protectedGroupIds.has(
                                                                                    group.id
                                                                                )}
                                                                                onClick={() =>
                                                                                    void handleDeleteGroup(
                                                                                        group.id
                                                                                    )
                                                                                }
                                                                            />
                                                                        </td>
                                                                    </tr>
                                                                ))
                                                            )}
                                                        </tbody>
                                                    </table>
                                                </div>
                                            </>
                                        ) : null}

                                        {groupAdminMode === 'create' ? (
                                            <div className="admin-form-page">
                                                <h3 className="admin-form-title">Create Group</h3>
                                                <form
                                                    onSubmit={handleCreateGroup}
                                                    className="stack-form admin-form"
                                                >
                                                    <label htmlFor="new-group-name">Name</label>
                                                    <input
                                                        id="new-group-name"
                                                        value={groupNameInput}
                                                        onChange={(event) =>
                                                            setGroupNameInput(
                                                                normalizeAdminIdentifier(
                                                                    event.target.value
                                                                )
                                                            )
                                                        }
                                                        placeholder="incident-responders"
                                                        pattern="[a-z][a-z0-9.-]*"
                                                        autoCapitalize="none"
                                                        autoCorrect="off"
                                                        spellCheck={false}
                                                        required
                                                        aria-describedby="new-group-name-hint"
                                                    />
                                                    <p
                                                        className="form-hint"
                                                        id="new-group-name-hint"
                                                    >
                                                        Lowercase letters, numbers, dots (.), and
                                                        hyphens (-). Must start with a letter.
                                                    </p>

                                                    <label htmlFor="new-group-description">
                                                        Description
                                                    </label>
                                                    <input
                                                        id="new-group-description"
                                                        value={groupDescriptionInput}
                                                        onChange={(event) =>
                                                            setGroupDescriptionInput(
                                                                event.target.value
                                                            )
                                                        }
                                                        placeholder="Optional description"
                                                    />

                                                    <div className="row admin-form-actions">
                                                        <button type="submit">Create</button>
                                                        <button
                                                            type="button"
                                                            className="chip"
                                                            onClick={handleCancelGroupAdmin}
                                                        >
                                                            Cancel
                                                        </button>
                                                    </div>
                                                </form>
                                            </div>
                                        ) : null}

                                        {groupAdminMode === 'edit' && selectedGroupRecord ? (
                                            <div className="group-edit-layout">
                                                <div className="row toolbar-actions admin-toolbar-actions">
                                                    <button
                                                        type="button"
                                                        className="chip"
                                                        onClick={handleCancelGroupAdmin}
                                                    >
                                                        Back
                                                    </button>
                                                    <span className="connection-mode-pill">
                                                        Editing {selectedGroupRecord.name}
                                                    </span>
                                                </div>
                                                <form
                                                    className="stack-form"
                                                    onSubmit={(event) => {
                                                        event.preventDefault();
                                                        void handleUpdateGroupDescription(
                                                            selectedGroupRecord.id
                                                        );
                                                    }}
                                                >
                                                    <label
                                                        htmlFor={`group-description-${selectedGroupRecord.id}`}
                                                    >
                                                        Description
                                                    </label>
                                                    <input
                                                        id={`group-description-${selectedGroupRecord.id}`}
                                                        value={
                                                            groupDescriptionDrafts[
                                                                selectedGroupRecord.id
                                                            ] ?? ''
                                                        }
                                                        onChange={(event) =>
                                                            setGroupDescriptionDrafts((drafts) => ({
                                                                ...drafts,
                                                                [selectedGroupRecord.id]:
                                                                    event.target.value
                                                            }))
                                                        }
                                                    />
                                                    <button type="submit">Save Description</button>
                                                </form>

                                                <div className="stack-form">
                                                    <label htmlFor="group-member-add">
                                                        Add Member
                                                    </label>
                                                    <div className="member-row">
                                                        <input
                                                            id="group-member-add"
                                                            value={
                                                                memberDrafts[
                                                                    selectedGroupRecord.id
                                                                ] ?? ''
                                                            }
                                                            onChange={(event) =>
                                                                setMemberDrafts((drafts) => ({
                                                                    ...drafts,
                                                                    [selectedGroupRecord.id]:
                                                                        event.target.value
                                                                }))
                                                            }
                                                            placeholder="username"
                                                        />
                                                        <button
                                                            type="button"
                                                            onClick={() =>
                                                                void handleAddMember(
                                                                    selectedGroupRecord.id
                                                                )
                                                            }
                                                        >
                                                            Add
                                                        </button>
                                                    </div>
                                                </div>

                                                <div className="history-table-wrap">
                                                    <table className="result-table history-table admin-table">
                                                        <thead>
                                                            <tr>
                                                                <th>Member</th>
                                                                <th>Actions</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            {selectedGroupRecord.members.length ===
                                                            0 ? (
                                                                <tr>
                                                                    <td colSpan={2}>No members</td>
                                                                </tr>
                                                            ) : (
                                                                selectedGroupRecord.members.map(
                                                                    (member) => (
                                                                        <tr
                                                                            key={`${selectedGroupRecord.id}-${member}`}
                                                                        >
                                                                            <td>{member}</td>
                                                                            <td className="history-actions">
                                                                                <IconButton
                                                                                    icon="delete"
                                                                                    title={`Remove ${member}`}
                                                                                    variant="danger"
                                                                                    onClick={() =>
                                                                                        void handleRemoveMember(
                                                                                            selectedGroupRecord.id,
                                                                                            member
                                                                                        )
                                                                                    }
                                                                                />
                                                                            </td>
                                                                        </tr>
                                                                    )
                                                                )
                                                            )}
                                                        </tbody>
                                                    </table>
                                                </div>
                                                <button
                                                    type="button"
                                                    className="danger-button"
                                                    disabled={selectedGroupProtected}
                                                    title={
                                                        selectedGroupProtected
                                                            ? 'System groups cannot be deleted'
                                                            : `Delete group ${selectedGroupRecord.name}`
                                                    }
                                                    onClick={() =>
                                                        void handleDeleteGroup(
                                                            selectedGroupRecord.id
                                                        )
                                                    }
                                                >
                                                    Delete Group
                                                </button>
                                            </div>
                                        ) : null}
                                    </>
                                ) : null}

                                {activeAdminSubsection === 'users' ? (
                                    <>
                                        {userAdminMode === 'list' ? (
                                            <>
                                                <div className="row toolbar-actions admin-toolbar-actions">
                                                    {localAuthEnabled ? (
                                                        <button
                                                            type="button"
                                                            onClick={handleStartCreateUser}
                                                        >
                                                            Create User
                                                        </button>
                                                    ) : (
                                                        <span className="muted-id">
                                                            LDAP mode enabled. User creation is
                                                            disabled.
                                                        </span>
                                                    )}
                                                </div>
                                                <div className="history-table-wrap">
                                                    <table className="result-table history-table admin-table">
                                                        <thead>
                                                            <tr>
                                                                <th>Username</th>
                                                                <th>Display Name</th>
                                                                <th>Provider</th>
                                                                <th>Groups</th>
                                                                <th>Password</th>
                                                                <th>Actions</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            {adminUsers.length === 0 ? (
                                                                <tr>
                                                                    <td colSpan={6}>
                                                                        No users available.
                                                                    </td>
                                                                </tr>
                                                            ) : (
                                                                adminUsers.map((user) => (
                                                                    <tr
                                                                        key={`admin-user-row-${user.username}`}
                                                                    >
                                                                        <td>{user.username}</td>
                                                                        <td>
                                                                            {editingUserDisplayName ===
                                                                            user.username ? (
                                                                                <input
                                                                                    value={
                                                                                        editingUserDisplayNameDraft
                                                                                    }
                                                                                    onChange={(
                                                                                        event
                                                                                    ) =>
                                                                                        setEditingUserDisplayNameDraft(
                                                                                            event
                                                                                                .target
                                                                                                .value
                                                                                        )
                                                                                    }
                                                                                    placeholder={
                                                                                        user.username
                                                                                    }
                                                                                    aria-label={`Display name for ${user.username}`}
                                                                                />
                                                                            ) : (
                                                                                user.displayName
                                                                            )}
                                                                        </td>
                                                                        <td>
                                                                            {user.provider.toUpperCase()}
                                                                        </td>
                                                                        <td>
                                                                            {user.groups.length > 0
                                                                                ? user.groups.join(
                                                                                      ', '
                                                                                  )
                                                                                : '-'}
                                                                        </td>
                                                                        <td>
                                                                            {user.temporaryPassword
                                                                                ? 'Temporary'
                                                                                : 'Permanent'}
                                                                        </td>
                                                                        <td className="history-actions">
                                                                            {editingUserDisplayName ===
                                                                            user.username ? (
                                                                                <>
                                                                                    <button
                                                                                        type="button"
                                                                                        disabled={
                                                                                            savingUserDisplayName ===
                                                                                            user.username
                                                                                        }
                                                                                        onClick={() =>
                                                                                            void handleSaveUserDisplayName(
                                                                                                user.username
                                                                                            )
                                                                                        }
                                                                                    >
                                                                                        {savingUserDisplayName ===
                                                                                        user.username
                                                                                            ? 'Saving...'
                                                                                            : 'Save'}
                                                                                    </button>
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        disabled={
                                                                                            savingUserDisplayName ===
                                                                                            user.username
                                                                                        }
                                                                                        onClick={
                                                                                            handleCancelEditUserDisplayName
                                                                                        }
                                                                                    >
                                                                                        Cancel
                                                                                    </button>
                                                                                </>
                                                                            ) : user.provider ===
                                                                              'local' ? (
                                                                                <>
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() =>
                                                                                            handleStartEditUserDisplayName(
                                                                                                user
                                                                                            )
                                                                                        }
                                                                                    >
                                                                                        Edit Name
                                                                                    </button>
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        disabled={
                                                                                            resettingLocalUser ===
                                                                                            user.username
                                                                                        }
                                                                                        onClick={() =>
                                                                                            void handleResetLocalPassword(
                                                                                                user.username
                                                                                            )
                                                                                        }
                                                                                    >
                                                                                        {resettingLocalUser ===
                                                                                        user.username
                                                                                            ? 'Resetting...'
                                                                                            : 'Reset Password'}
                                                                                    </button>
                                                                                </>
                                                                            ) : (
                                                                                <span className="muted-id">
                                                                                    LDAP managed
                                                                                </span>
                                                                            )}
                                                                        </td>
                                                                    </tr>
                                                                ))
                                                            )}
                                                        </tbody>
                                                    </table>
                                                </div>
                                            </>
                                        ) : null}

                                        {userAdminMode === 'create' ? (
                                            <div className="admin-form-page">
                                                <h3 className="admin-form-title">Create User</h3>
                                                <form
                                                    className="stack-form admin-form"
                                                    onSubmit={handleCreateLocalUser}
                                                >
                                                    <label htmlFor="local-user-username">
                                                        Username
                                                    </label>
                                                    <input
                                                        id="local-user-username"
                                                        value={localUserUsernameInput}
                                                        onChange={(event) =>
                                                            setLocalUserUsernameInput(
                                                                normalizeAdminIdentifier(
                                                                    event.target.value
                                                                )
                                                            )
                                                        }
                                                        placeholder="new.analyst"
                                                        pattern="[a-z][a-z0-9.-]*"
                                                        autoCapitalize="none"
                                                        autoCorrect="off"
                                                        spellCheck={false}
                                                        required
                                                        aria-describedby="local-user-username-hint"
                                                    />
                                                    <p
                                                        className="form-hint"
                                                        id="local-user-username-hint"
                                                    >
                                                        Lowercase letters, numbers, dots (.), and
                                                        hyphens (-). Must start with a letter.
                                                    </p>

                                                    <label htmlFor="local-user-display-name">
                                                        Display Name
                                                    </label>
                                                    <input
                                                        id="local-user-display-name"
                                                        value={localUserDisplayNameInput}
                                                        onChange={(event) =>
                                                            setLocalUserDisplayNameInput(
                                                                event.target.value
                                                            )
                                                        }
                                                        placeholder="New Analyst"
                                                    />

                                                    <label htmlFor="local-user-email">Email</label>
                                                    <input
                                                        id="local-user-email"
                                                        type="email"
                                                        value={localUserEmailInput}
                                                        onChange={(event) =>
                                                            setLocalUserEmailInput(
                                                                event.target.value
                                                            )
                                                        }
                                                        placeholder="analyst@example.com"
                                                    />

                                                    <label htmlFor="local-user-password">
                                                        Password
                                                    </label>
                                                    <input
                                                        id="local-user-password"
                                                        type="password"
                                                        value={localUserPasswordInput}
                                                        onChange={(event) =>
                                                            setLocalUserPasswordInput(
                                                                event.target.value
                                                            )
                                                        }
                                                        required
                                                    />

                                                    <label className="checkbox-row">
                                                        <input
                                                            type="checkbox"
                                                            checked={localUserTemporaryPassword}
                                                            onChange={(event) =>
                                                                setLocalUserTemporaryPassword(
                                                                    event.target.checked
                                                                )
                                                            }
                                                        />
                                                        <span>Temporary password</span>
                                                    </label>

                                                    <label className="checkbox-row">
                                                        <input
                                                            type="checkbox"
                                                            checked={localUserSystemAdmin}
                                                            onChange={(event) =>
                                                                setLocalUserSystemAdmin(
                                                                    event.target.checked
                                                                )
                                                            }
                                                        />
                                                        <span>Grant system admin role</span>
                                                    </label>

                                                    <div className="row admin-form-actions">
                                                        <button
                                                            type="submit"
                                                            disabled={creatingLocalUser}
                                                        >
                                                            {creatingLocalUser
                                                                ? 'Creating...'
                                                                : 'Create'}
                                                        </button>
                                                        <button
                                                            type="button"
                                                            className="chip"
                                                            onClick={handleCancelUserAdmin}
                                                        >
                                                            Cancel
                                                        </button>
                                                    </div>
                                                </form>
                                            </div>
                                        ) : null}
                                    </>
                                ) : null}

                                {activeAdminSubsection === 'access' ? (
                                    <>
                                        {accessAdminMode === 'list' ? (
                                            <>
                                                <div className="row toolbar-actions admin-toolbar-actions">
                                                    <button
                                                        type="button"
                                                        onClick={handleStartCreateAccessRule}
                                                    >
                                                        Create Access Rule
                                                    </button>
                                                </div>
                                                <div className="history-table-wrap">
                                                    <table className="result-table history-table admin-table">
                                                        <thead>
                                                            <tr>
                                                                <th>Group</th>
                                                                <th>Connection</th>
                                                                <th>Query</th>
                                                                <th>Export</th>
                                                                <th>Read Only</th>
                                                                <th>Credential Profile</th>
                                                                <th>Max Rows</th>
                                                                <th>Max Runtime (s)</th>
                                                                <th>Concurrency</th>
                                                                <th>Actions</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            {adminDatasourceAccess.length === 0 ? (
                                                                <tr>
                                                                    <td colSpan={10}>
                                                                        No access rules configured.
                                                                    </td>
                                                                </tr>
                                                            ) : (
                                                                adminDatasourceAccess.map(
                                                                    (access) => (
                                                                        <tr
                                                                            key={`admin-access-row-${access.groupId}-${access.datasourceId}`}
                                                                        >
                                                                            <td>
                                                                                {access.groupId}
                                                                            </td>
                                                                            <td>
                                                                                {
                                                                                    access.datasourceId
                                                                                }
                                                                            </td>
                                                                            <td>
                                                                                {access.canQuery
                                                                                    ? 'Yes'
                                                                                    : 'No'}
                                                                            </td>
                                                                            <td>
                                                                                {access.canExport
                                                                                    ? 'Yes'
                                                                                    : 'No'}
                                                                            </td>
                                                                            <td>
                                                                                {access.readOnly
                                                                                    ? 'Yes'
                                                                                    : 'No'}
                                                                            </td>
                                                                            <td>
                                                                                {
                                                                                    access.credentialProfile
                                                                                }
                                                                            </td>
                                                                            <td>
                                                                                {access.maxRowsPerQuery ??
                                                                                    '-'}
                                                                            </td>
                                                                            <td>
                                                                                {access.maxRuntimeSeconds ??
                                                                                    '-'}
                                                                            </td>
                                                                            <td>
                                                                                {access.concurrencyLimit ??
                                                                                    '-'}
                                                                            </td>
                                                                            <td className="history-actions">
                                                                                <IconButton
                                                                                    icon="rename"
                                                                                    title={`Edit access for ${access.groupId}`}
                                                                                    onClick={() =>
                                                                                        handleStartEditAccessRule(
                                                                                            access
                                                                                        )
                                                                                    }
                                                                                />
                                                                                <IconButton
                                                                                    icon="delete"
                                                                                    title={`Delete access for ${access.groupId}`}
                                                                                    variant="danger"
                                                                                    onClick={() =>
                                                                                        void handleDeleteDatasourceAccess(
                                                                                            access.groupId,
                                                                                            access.datasourceId
                                                                                        )
                                                                                    }
                                                                                />
                                                                            </td>
                                                                        </tr>
                                                                    )
                                                                )
                                                            )}
                                                        </tbody>
                                                    </table>
                                                </div>
                                            </>
                                        ) : null}

                                        {accessAdminMode === 'create' ||
                                        accessAdminMode === 'edit' ? (
                                            <div className="admin-form-page">
                                                <h3 className="admin-form-title">
                                                    {accessAdminMode === 'edit'
                                                        ? 'Edit Access Rule'
                                                        : 'Create Access Rule'}
                                                </h3>
                                                <form
                                                    className={
                                                        accessAdminMode === 'edit'
                                                            ? 'stack-form access-edit-layout admin-form'
                                                            : 'stack-form admin-form'
                                                    }
                                                    onSubmit={handleSaveDatasourceAccess}
                                                >
                                                    <label htmlFor="access-group">Group</label>
                                                    <div className="select-wrap">
                                                        <select
                                                            id="access-group"
                                                            value={selectedGroupId}
                                                            onChange={(event) =>
                                                                setSelectedGroupId(
                                                                    event.target.value
                                                                )
                                                            }
                                                        >
                                                            <option value="">Select group</option>
                                                            {adminGroups.map((group) => (
                                                                <option
                                                                    key={group.id}
                                                                    value={group.id}
                                                                >
                                                                    {group.name} ({group.id})
                                                                </option>
                                                            ))}
                                                        </select>
                                                    </div>

                                                    <label htmlFor="access-datasource">
                                                        Connection
                                                    </label>
                                                    <div className="select-wrap">
                                                        <select
                                                            id="access-datasource"
                                                            value={selectedDatasourceForAccess}
                                                            onChange={(event) =>
                                                                setSelectedDatasourceForAccess(
                                                                    event.target.value
                                                                )
                                                            }
                                                        >
                                                            <option value="">
                                                                Select connection
                                                            </option>
                                                            {adminDatasourceCatalog.map(
                                                                (datasource) => (
                                                                    <option
                                                                        key={datasource.id}
                                                                        value={datasource.id}
                                                                    >
                                                                        {datasource.name} (
                                                                        {datasource.engine})
                                                                    </option>
                                                                )
                                                            )}
                                                        </select>
                                                    </div>

                                                    <div className="row">
                                                        <label className="checkbox-row">
                                                            <input
                                                                type="checkbox"
                                                                checked={canQuery}
                                                                onChange={(event) =>
                                                                    setCanQuery(
                                                                        event.target.checked
                                                                    )
                                                                }
                                                            />
                                                            <span>Can Query</span>
                                                        </label>
                                                        <label className="checkbox-row">
                                                            <input
                                                                type="checkbox"
                                                                checked={canExport}
                                                                onChange={(event) =>
                                                                    setCanExport(
                                                                        event.target.checked
                                                                    )
                                                                }
                                                            />
                                                            <span>Can Export</span>
                                                        </label>
                                                        <label className="checkbox-row">
                                                            <input
                                                                type="checkbox"
                                                                checked={readOnly}
                                                                onChange={(event) =>
                                                                    setReadOnly(
                                                                        event.target.checked
                                                                    )
                                                                }
                                                            />
                                                            <span>Read Only</span>
                                                        </label>
                                                    </div>

                                                    <label htmlFor="credential-profile">
                                                        Credential Profile
                                                    </label>
                                                    <div className="select-wrap">
                                                        <select
                                                            id="credential-profile"
                                                            value={credentialProfile}
                                                            onChange={(event) =>
                                                                setCredentialProfile(
                                                                    event.target.value
                                                                )
                                                            }
                                                        >
                                                            <option value="">
                                                                Select credential profile
                                                            </option>
                                                            {(
                                                                selectedAdminDatasource?.credentialProfiles ??
                                                                []
                                                            ).map((profile) => (
                                                                <option
                                                                    key={profile}
                                                                    value={profile}
                                                                >
                                                                    {profile}
                                                                </option>
                                                            ))}
                                                        </select>
                                                    </div>

                                                    <label htmlFor="max-rows">
                                                        Max Rows Per Query
                                                    </label>
                                                    <input
                                                        id="max-rows"
                                                        type="number"
                                                        min={0}
                                                        value={maxRowsPerQuery}
                                                        onChange={(event) =>
                                                            setMaxRowsPerQuery(event.target.value)
                                                        }
                                                    />
                                                    <p className="form-hint">
                                                        Default: 5,000. Leave blank for default. Use
                                                        0 for unlimited.
                                                    </p>

                                                    <label htmlFor="max-runtime">
                                                        Max Runtime Seconds
                                                    </label>
                                                    <input
                                                        id="max-runtime"
                                                        type="number"
                                                        min={0}
                                                        value={maxRuntimeSeconds}
                                                        onChange={(event) =>
                                                            setMaxRuntimeSeconds(event.target.value)
                                                        }
                                                    />
                                                    <p className="form-hint">
                                                        Default: 300. Leave blank for default. Use 0
                                                        for unlimited.
                                                    </p>

                                                    <label htmlFor="concurrency-limit">
                                                        Concurrency Limit
                                                    </label>
                                                    <input
                                                        id="concurrency-limit"
                                                        type="number"
                                                        min={0}
                                                        value={concurrencyLimit}
                                                        onChange={(event) =>
                                                            setConcurrencyLimit(event.target.value)
                                                        }
                                                    />
                                                    <p className="form-hint">
                                                        Default: 5 (capped by the server max per
                                                        user). Leave blank for default. Use 0 for
                                                        unlimited.
                                                    </p>

                                                    <div className="row admin-form-actions">
                                                        <button
                                                            type="submit"
                                                            disabled={savingAccess}
                                                        >
                                                            {savingAccess
                                                                ? 'Saving...'
                                                                : accessAdminMode === 'edit'
                                                                  ? 'Save'
                                                                  : 'Create'}
                                                        </button>
                                                        <button
                                                            type="button"
                                                            className="chip"
                                                            onClick={handleCancelAccessAdmin}
                                                        >
                                                            Cancel
                                                        </button>
                                                    </div>
                                                </form>
                                            </div>
                                        ) : null}
                                    </>
                                ) : null}
                            </section>

                            <section
                                className="panel admin-governance"
                                hidden={activeSection !== 'connections'}
                            >
                                {adminError ? (
                                    <p className="form-error" role="alert">
                                        {adminError}
                                    </p>
                                ) : null}
                                {adminSuccess ? (
                                    <p className="form-success">{adminSuccess}</p>
                                ) : null}

                                {activeSection === 'connections' ? (
                                    <div className="admin-grid admin-grid-connections">
                                        {activeSection === 'connections' ? (
                                            <section className="datasource-admin">
                                                {connectionEditorMode === 'list' ? (
                                                    <section className="connection-list-view">
                                                        <div className="row connection-list-toolbar">
                                                            <button
                                                                type="button"
                                                                onClick={
                                                                    handleStartCreateManagedDatasource
                                                                }
                                                            >
                                                                Create Connection
                                                            </button>
                                                            <button
                                                                type="button"
                                                                disabled={reencryptingCredentials}
                                                                title="Re-encrypt stored credential profiles with the current server key. Useful after rotating encryption keys or restoring backups."
                                                                onClick={() =>
                                                                    void handleReencryptCredentials()
                                                                }
                                                            >
                                                                {reencryptingCredentials
                                                                    ? 'Re-encrypting...'
                                                                    : 'Re-encrypt Credentials'}
                                                            </button>
                                                        </div>
                                                        {managedDatasourcesByEngine.length === 0 ? (
                                                            <p className="muted-id">
                                                                No connections configured yet.
                                                            </p>
                                                        ) : (
                                                            <div className="connection-catalog-list connection-catalog-list-standalone">
                                                                {managedDatasourcesByEngine.map(
                                                                    (datasource) => (
                                                                        <article
                                                                            key={datasource.id}
                                                                            className="connection-catalog-item connection-catalog-item-tile"
                                                                        >
                                                                            <header className="connection-tile-top connection-tile-top-centered">
                                                                                <span className="connection-catalog-actions connection-catalog-actions-floating">
                                                                                    <IconButton
                                                                                        icon="rename"
                                                                                        title={`Edit ${datasource.name}`}
                                                                                        onClick={() =>
                                                                                            handleStartEditManagedDatasource(
                                                                                                datasource.id
                                                                                            )
                                                                                        }
                                                                                    />
                                                                                    <IconButton
                                                                                        icon="delete"
                                                                                        title={`Delete ${datasource.name}`}
                                                                                        variant="danger"
                                                                                        disabled={
                                                                                            deletingDatasource
                                                                                        }
                                                                                        onClick={() =>
                                                                                            void handleDeleteManagedDatasource(
                                                                                                datasource
                                                                                            )
                                                                                        }
                                                                                    />
                                                                                </span>
                                                                                <span
                                                                                    className="connection-catalog-icon"
                                                                                    aria-hidden
                                                                                >
                                                                                    <img
                                                                                        src={resolveDatasourceIcon(
                                                                                            datasource.engine
                                                                                        )}
                                                                                        alt=""
                                                                                        width={24}
                                                                                        height={24}
                                                                                    />
                                                                                </span>
                                                                                <span className="connection-catalog-main connection-catalog-main-centered">
                                                                                    <span className="connection-catalog-name">
                                                                                        {
                                                                                            datasource.name
                                                                                        }
                                                                                    </span>
                                                                                    {datasource.id !==
                                                                                    datasource.name ? (
                                                                                        <span className="connection-catalog-id">
                                                                                            {
                                                                                                datasource.id
                                                                                            }
                                                                                        </span>
                                                                                    ) : null}
                                                                                </span>
                                                                            </header>
                                                                            <footer className="connection-tile-bottom">
                                                                                <span className="connection-catalog-engine">
                                                                                    {
                                                                                        datasource.engine
                                                                                    }
                                                                                </span>
                                                                            </footer>
                                                                        </article>
                                                                    )
                                                                )}
                                                            </div>
                                                        )}
                                                    </section>
                                                ) : (
                                                    <div className="connection-editor-page">
                                                        <header className="connection-editor-heading">
                                                            <h3 className="connection-editor-title">
                                                                {connectionEditorMode === 'edit'
                                                                    ? 'Edit connection'
                                                                    : 'Create connection'}
                                                            </h3>
                                                            <p className="muted-id connection-editor-subtitle">
                                                                {connectionEditorMode === 'edit' &&
                                                                selectedManagedDatasource
                                                                    ? `Editing ${selectedManagedDatasource.name}.`
                                                                    : 'Quick setup is usually enough. Advanced settings stay collapsed by default.'}
                                                            </p>
                                                        </header>

                                                        <form
                                                            className="stack-form connection-form"
                                                            onSubmit={handleSaveManagedDatasource}
                                                        >
                                                            <h4>Quick Setup</h4>

                                                            <div className="connection-form-grid">
                                                                <div className="form-field">
                                                                    <label
                                                                        htmlFor="managed-name"
                                                                        className="label-with-help"
                                                                    >
                                                                        Name{' '}
                                                                        <InfoHint text="Lowercase letters, numbers, dots (.), and hyphens (-). Must start with a letter." />
                                                                    </label>
                                                                    <input
                                                                        id="managed-name"
                                                                        value={
                                                                            managedDatasourceForm.name
                                                                        }
                                                                        onChange={(event) =>
                                                                            setManagedDatasourceForm(
                                                                                (current) => ({
                                                                                    ...current,
                                                                                    name: normalizeAdminIdentifier(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                })
                                                                            )
                                                                        }
                                                                        placeholder="mariadb-mart"
                                                                        pattern="[a-z][a-z0-9.-]*"
                                                                        autoCapitalize="none"
                                                                        autoCorrect="off"
                                                                        spellCheck={false}
                                                                        required
                                                                    />
                                                                </div>

                                                                <div className="form-field">
                                                                    <label htmlFor="managed-engine">
                                                                        Engine
                                                                    </label>
                                                                    <div className="managed-engine-field">
                                                                        <span
                                                                            className="managed-engine-icon"
                                                                            aria-hidden
                                                                        >
                                                                            <img
                                                                                src={resolveDatasourceIcon(
                                                                                    managedDatasourceForm.engine
                                                                                )}
                                                                                alt=""
                                                                                width={16}
                                                                                height={16}
                                                                            />
                                                                        </span>
                                                                        <div className="select-wrap">
                                                                            <select
                                                                                id="managed-engine"
                                                                                value={
                                                                                    managedDatasourceForm.engine
                                                                                }
                                                                                disabled={Boolean(
                                                                                    selectedManagedDatasource
                                                                                )}
                                                                                onChange={(
                                                                                    event
                                                                                ) => {
                                                                                    const nextEngine =
                                                                                        event.target
                                                                                            .value as DatasourceEngine;
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            engine: nextEngine,
                                                                                            port: defaultPortByEngine[
                                                                                                nextEngine
                                                                                            ].toString()
                                                                                        })
                                                                                    );
                                                                                }}
                                                                            >
                                                                                <option value="POSTGRESQL">
                                                                                    PostgreSQL
                                                                                </option>
                                                                                <option value="MYSQL">
                                                                                    MySQL
                                                                                </option>
                                                                                <option value="MARIADB">
                                                                                    MariaDB
                                                                                </option>
                                                                                <option value="TRINO">
                                                                                    Trino
                                                                                </option>
                                                                                <option value="STARROCKS">
                                                                                    StarRocks
                                                                                </option>
                                                                                <option value="VERTICA">
                                                                                    Vertica
                                                                                </option>
                                                                            </select>
                                                                        </div>
                                                                    </div>
                                                                </div>

                                                                <div className="form-field">
                                                                    <label htmlFor="managed-host">
                                                                        Host
                                                                    </label>
                                                                    <input
                                                                        id="managed-host"
                                                                        value={
                                                                            managedDatasourceForm.host
                                                                        }
                                                                        onChange={(event) =>
                                                                            setManagedDatasourceForm(
                                                                                (current) => ({
                                                                                    ...current,
                                                                                    host: event
                                                                                        .target
                                                                                        .value
                                                                                })
                                                                            )
                                                                        }
                                                                        required={
                                                                            managedDatasourceForm.connectionType ===
                                                                            'HOST_PORT'
                                                                        }
                                                                        placeholder="localhost"
                                                                    />
                                                                </div>

                                                                <div className="form-field">
                                                                    <label htmlFor="managed-port">
                                                                        Port
                                                                    </label>
                                                                    <input
                                                                        id="managed-port"
                                                                        type="number"
                                                                        min={1}
                                                                        max={65535}
                                                                        value={
                                                                            managedDatasourceForm.port
                                                                        }
                                                                        onChange={(event) =>
                                                                            setManagedDatasourceForm(
                                                                                (current) => ({
                                                                                    ...current,
                                                                                    port: event
                                                                                        .target
                                                                                        .value
                                                                                })
                                                                            )
                                                                        }
                                                                        required
                                                                    />
                                                                </div>

                                                                <div className="form-field">
                                                                    <label htmlFor="managed-database">
                                                                        Database (optional)
                                                                    </label>
                                                                    <input
                                                                        id="managed-database"
                                                                        value={
                                                                            managedDatasourceForm.database
                                                                        }
                                                                        onChange={(event) =>
                                                                            setManagedDatasourceForm(
                                                                                (current) => ({
                                                                                    ...current,
                                                                                    database:
                                                                                        event.target
                                                                                            .value
                                                                                })
                                                                            )
                                                                        }
                                                                        placeholder="schema or database"
                                                                    />
                                                                </div>

                                                                <div className="form-field">
                                                                    <label htmlFor="managed-authentication">
                                                                        Authentication
                                                                    </label>
                                                                    <div className="select-wrap">
                                                                        <select
                                                                            id="managed-authentication"
                                                                            value={
                                                                                managedDatasourceForm.authentication
                                                                            }
                                                                            onChange={(event) =>
                                                                                setManagedDatasourceForm(
                                                                                    (current) => ({
                                                                                        ...current,
                                                                                        authentication:
                                                                                            event
                                                                                                .target
                                                                                                .value as ConnectionAuthentication
                                                                                    })
                                                                                )
                                                                            }
                                                                        >
                                                                            <option value="USER_PASSWORD">
                                                                                User &amp; Password
                                                                            </option>
                                                                            <option value="NO_AUTH">
                                                                                No Auth
                                                                            </option>
                                                                        </select>
                                                                    </div>
                                                                </div>

                                                                <div className="form-field">
                                                                    <label
                                                                        htmlFor="managed-profile-id"
                                                                        className="label-with-help"
                                                                    >
                                                                        Credential Profile{' '}
                                                                        <InfoHint text="Default profile to use for this connection. After saving, you can add more profiles and run tests below." />
                                                                    </label>
                                                                    <input
                                                                        id="managed-profile-id"
                                                                        value={
                                                                            managedDatasourceForm.credentialProfileId
                                                                        }
                                                                        onChange={(event) =>
                                                                            setManagedDatasourceForm(
                                                                                (current) => ({
                                                                                    ...current,
                                                                                    credentialProfileId:
                                                                                        event.target
                                                                                            .value
                                                                                })
                                                                            )
                                                                        }
                                                                        placeholder="admin-ro"
                                                                        required
                                                                    />
                                                                </div>

                                                                {managedDatasourceForm.authentication ===
                                                                'USER_PASSWORD' ? (
                                                                    <>
                                                                        <div className="form-field">
                                                                            <label htmlFor="managed-credential-username">
                                                                                User
                                                                            </label>
                                                                            <input
                                                                                id="managed-credential-username"
                                                                                value={
                                                                                    managedDatasourceForm.credentialUsername
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            credentialUsername:
                                                                                                event
                                                                                                    .target
                                                                                                    .value
                                                                                        })
                                                                                    )
                                                                                }
                                                                                required
                                                                            />
                                                                        </div>

                                                                        <div className="form-field">
                                                                            <label htmlFor="managed-credential-password">
                                                                                Password
                                                                            </label>
                                                                            <input
                                                                                id="managed-credential-password"
                                                                                type="password"
                                                                                value={
                                                                                    managedDatasourceForm.credentialPassword
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            credentialPassword:
                                                                                                event
                                                                                                    .target
                                                                                                    .value
                                                                                        })
                                                                                    )
                                                                                }
                                                                                placeholder={
                                                                                    selectedManagedDatasource
                                                                                        ? 'Leave blank to keep existing password'
                                                                                        : ''
                                                                                }
                                                                                required={
                                                                                    !selectedManagedDatasource
                                                                                }
                                                                            />
                                                                        </div>
                                                                    </>
                                                                ) : (
                                                                    <p className="muted-id connection-form-note connection-form-full">
                                                                        No credential
                                                                        username/password will be
                                                                        stored for this connection.
                                                                    </p>
                                                                )}

                                                                <div className="form-field connection-form-full">
                                                                    <label htmlFor="managed-credential-description">
                                                                        Credential Description
                                                                        (optional)
                                                                    </label>
                                                                    <input
                                                                        id="managed-credential-description"
                                                                        value={
                                                                            managedDatasourceForm.credentialDescription
                                                                        }
                                                                        onChange={(event) =>
                                                                            setManagedDatasourceForm(
                                                                                (current) => ({
                                                                                    ...current,
                                                                                    credentialDescription:
                                                                                        event.target
                                                                                            .value
                                                                                })
                                                                            )
                                                                        }
                                                                        placeholder="Readonly profile for analysts"
                                                                    />
                                                                </div>
                                                            </div>

                                                            <details className="managed-advanced-block">
                                                                <DetailsSummary>
                                                                    Connection
                                                                </DetailsSummary>

                                                                <div className="managed-advanced-body">
                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-connection-type">
                                                                            Connection Type
                                                                        </label>
                                                                        <div className="select-wrap">
                                                                            <select
                                                                                id="managed-connection-type"
                                                                                value={
                                                                                    managedDatasourceForm.connectionType
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            connectionType:
                                                                                                event
                                                                                                    .target
                                                                                                    .value as ConnectionType
                                                                                        })
                                                                                    )
                                                                                }
                                                                            >
                                                                                <option value="HOST_PORT">
                                                                                    Default (Host +
                                                                                    Port)
                                                                                </option>
                                                                                <option value="JDBC_URL">
                                                                                    JDBC URL
                                                                                </option>
                                                                            </select>
                                                                        </div>
                                                                    </div>

                                                                    {managedDatasourceForm.connectionType ===
                                                                    'JDBC_URL' ? (
                                                                        <div className="form-field">
                                                                            <label htmlFor="managed-jdbc-url">
                                                                                JDBC URL
                                                                            </label>
                                                                            <input
                                                                                id="managed-jdbc-url"
                                                                                value={
                                                                                    managedDatasourceForm.jdbcUrl
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            jdbcUrl:
                                                                                                event
                                                                                                    .target
                                                                                                    .value
                                                                                        })
                                                                                    )
                                                                                }
                                                                                placeholder={`jdbc:${managedDatasourceForm.engine.toLowerCase()}://host:port/database`}
                                                                                required
                                                                            />
                                                                        </div>
                                                                    ) : null}

                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-options">
                                                                            JDBC Options (key=value
                                                                            per line)
                                                                        </label>
                                                                        <textarea
                                                                            id="managed-options"
                                                                            rows={4}
                                                                            value={
                                                                                managedDatasourceForm.optionsInput
                                                                            }
                                                                            onChange={(event) =>
                                                                                setManagedDatasourceForm(
                                                                                    (current) => ({
                                                                                        ...current,
                                                                                        optionsInput:
                                                                                            event
                                                                                                .target
                                                                                                .value
                                                                                    })
                                                                                )
                                                                            }
                                                                            placeholder={
                                                                                'allowPublicKeyRetrieval=true\\nserverTimezone=UTC'
                                                                            }
                                                                        />
                                                                    </div>

                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-jdbc-preview">
                                                                            JDBC URL Preview
                                                                        </label>
                                                                        <input
                                                                            id="managed-jdbc-preview"
                                                                            value={
                                                                                managedFormJdbcPreview
                                                                            }
                                                                            readOnly
                                                                        />
                                                                    </div>
                                                                </div>
                                                            </details>

                                                            <details className="managed-advanced-block">
                                                                <DetailsSummary>
                                                                    Driver
                                                                </DetailsSummary>

                                                                <div className="managed-advanced-body">
                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-driver">
                                                                            Driver
                                                                        </label>
                                                                        <div className="select-wrap">
                                                                            <select
                                                                                id="managed-driver"
                                                                                value={
                                                                                    managedDatasourceForm.driverId
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            driverId:
                                                                                                event
                                                                                                    .target
                                                                                                    .value
                                                                                        })
                                                                                    )
                                                                                }
                                                                            >
                                                                                <option value="">
                                                                                    Select driver
                                                                                </option>
                                                                                {driversForFormEngine.map(
                                                                                    (driver) => (
                                                                                        <option
                                                                                            key={
                                                                                                driver.driverId
                                                                                            }
                                                                                            value={
                                                                                                driver.driverId
                                                                                            }
                                                                                        >
                                                                                            {
                                                                                                driver.driverId
                                                                                            }
                                                                                            {driver.version
                                                                                                ? ` v${driver.version}`
                                                                                                : ''}{' '}
                                                                                            (
                                                                                            {
                                                                                                driver.source
                                                                                            }
                                                                                            )
                                                                                            {driver.available
                                                                                                ? ''
                                                                                                : ' [unavailable]'}
                                                                                        </option>
                                                                                    )
                                                                                )}
                                                                            </select>
                                                                        </div>
                                                                        {selectedDriverForForm ? (
                                                                            <p
                                                                                className={
                                                                                    selectedDriverForForm.available
                                                                                        ? 'form-success'
                                                                                        : 'form-error'
                                                                                }
                                                                            >
                                                                                {
                                                                                    selectedDriverForForm.description
                                                                                }
                                                                                .{' '}
                                                                                {selectedDriverForForm.version
                                                                                    ? `Version ${selectedDriverForForm.version}. `
                                                                                    : ''}
                                                                                {
                                                                                    selectedDriverForForm.message
                                                                                }
                                                                            </p>
                                                                        ) : null}
                                                                    </div>

                                                                    <div className="driver-upload">
                                                                        <h4>Upload Driver</h4>
                                                                        <p className="muted-id">
                                                                            Upload a JDBC driver jar
                                                                            when built-in versions
                                                                            are unavailable.
                                                                        </p>
                                                                        <div className="form-field">
                                                                            <label htmlFor="upload-driver-class">
                                                                                Driver Class
                                                                            </label>
                                                                            <input
                                                                                id="upload-driver-class"
                                                                                value={
                                                                                    uploadDriverClassInput
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setUploadDriverClassInput(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                }
                                                                                placeholder="com.mysql.cj.jdbc.Driver"
                                                                            />
                                                                        </div>

                                                                        <div className="form-field">
                                                                            <label htmlFor="upload-driver-description">
                                                                                Description
                                                                                (optional)
                                                                            </label>
                                                                            <input
                                                                                id="upload-driver-description"
                                                                                value={
                                                                                    uploadDriverDescriptionInput
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setUploadDriverDescriptionInput(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                }
                                                                                placeholder="MySQL 9 Connector/J"
                                                                            />
                                                                        </div>

                                                                        <div className="form-field">
                                                                            <label htmlFor="upload-driver-jar">
                                                                                Driver Jar
                                                                            </label>
                                                                            <input
                                                                                id="upload-driver-jar"
                                                                                type="file"
                                                                                accept=".jar,application/java-archive"
                                                                                onChange={(event) =>
                                                                                    setUploadDriverJarFile(
                                                                                        event.target
                                                                                            .files?.[0] ??
                                                                                            null
                                                                                    )
                                                                                }
                                                                            />
                                                                        </div>
                                                                        <button
                                                                            type="button"
                                                                            className="chip upload-driver-button"
                                                                            onClick={() =>
                                                                                void handleUploadDriver()
                                                                            }
                                                                            disabled={
                                                                                uploadingDriver
                                                                            }
                                                                        >
                                                                            {uploadingDriver
                                                                                ? 'Uploading...'
                                                                                : 'Upload Driver Jar'}
                                                                        </button>
                                                                    </div>

                                                                    {mavenPresetsForFormEngine.length >
                                                                    0 ? (
                                                                        <div className="driver-maven-install">
                                                                            <h4>Download Driver</h4>
                                                                            <p className="muted-id">
                                                                                Download a JDBC
                                                                                driver jar from
                                                                                Maven Central and
                                                                                store it alongside
                                                                                uploaded drivers.
                                                                            </p>
                                                                            <div className="form-field">
                                                                                <label htmlFor="maven-driver-preset">
                                                                                    Preset
                                                                                </label>
                                                                                <div className="select-wrap">
                                                                                    <select
                                                                                        id="maven-driver-preset"
                                                                                        value={
                                                                                            mavenDriverPreset
                                                                                        }
                                                                                        onChange={(
                                                                                            event
                                                                                        ) =>
                                                                                            setMavenDriverPreset(
                                                                                                event
                                                                                                    .target
                                                                                                    .value as MavenDriverPreset
                                                                                            )
                                                                                        }
                                                                                    >
                                                                                        {mavenPresetsForFormEngine.map(
                                                                                            (
                                                                                                preset
                                                                                            ) => (
                                                                                                <option
                                                                                                    key={
                                                                                                        preset
                                                                                                    }
                                                                                                    value={
                                                                                                        preset
                                                                                                    }
                                                                                                >
                                                                                                    {
                                                                                                        preset
                                                                                                    }
                                                                                                </option>
                                                                                            )
                                                                                        )}
                                                                                    </select>
                                                                                </div>
                                                                            </div>

                                                                            <div className="muted-id driver-maven-details">
                                                                                <div>
                                                                                    Group:{' '}
                                                                                    <code>
                                                                                        {
                                                                                            mavenDriverPresetDetails[
                                                                                                mavenDriverPreset
                                                                                            ]
                                                                                                .groupId
                                                                                        }
                                                                                    </code>
                                                                                </div>
                                                                                <div>
                                                                                    Artifact:{' '}
                                                                                    <code>
                                                                                        {
                                                                                            mavenDriverPresetDetails[
                                                                                                mavenDriverPreset
                                                                                            ]
                                                                                                .artifactId
                                                                                        }
                                                                                    </code>
                                                                                </div>
                                                                                <div>
                                                                                    Maven Central:{' '}
                                                                                    <a
                                                                                        href={`https://search.maven.org/artifact/${encodeURIComponent(
                                                                                            mavenDriverPresetDetails[
                                                                                                mavenDriverPreset
                                                                                            ]
                                                                                                .groupId
                                                                                        )}/${encodeURIComponent(
                                                                                            mavenDriverPresetDetails[
                                                                                                mavenDriverPreset
                                                                                            ]
                                                                                                .artifactId
                                                                                        )}`}
                                                                                        target="_blank"
                                                                                        rel="noreferrer"
                                                                                    >
                                                                                        search.maven.org
                                                                                    </a>
                                                                                </div>
                                                                                <div>
                                                                                    Driver class:{' '}
                                                                                    <code>
                                                                                        {
                                                                                            mavenDriverPresetDetails[
                                                                                                mavenDriverPreset
                                                                                            ]
                                                                                                .driverClass
                                                                                        }
                                                                                    </code>
                                                                                </div>
                                                                            </div>

                                                                            <div className="form-field">
                                                                                <label htmlFor="maven-driver-version">
                                                                                    Version
                                                                                </label>
                                                                                {mavenDriverVersions.length >
                                                                                0 ? (
                                                                                    <div className="select-wrap">
                                                                                        <select
                                                                                            id="maven-driver-version"
                                                                                            value={
                                                                                                mavenDriverVersionInput
                                                                                            }
                                                                                            onChange={(
                                                                                                event
                                                                                            ) =>
                                                                                                setMavenDriverVersionInput(
                                                                                                    event
                                                                                                        .target
                                                                                                        .value
                                                                                                )
                                                                                            }
                                                                                            disabled={
                                                                                                loadingMavenDriverVersions
                                                                                            }
                                                                                        >
                                                                                            {mavenDriverVersions.map(
                                                                                                (
                                                                                                    version
                                                                                                ) => (
                                                                                                    <option
                                                                                                        key={
                                                                                                            version
                                                                                                        }
                                                                                                        value={
                                                                                                            version
                                                                                                        }
                                                                                                    >
                                                                                                        {
                                                                                                            version
                                                                                                        }
                                                                                                    </option>
                                                                                                )
                                                                                            )}
                                                                                        </select>
                                                                                    </div>
                                                                                ) : (
                                                                                    <input
                                                                                        id="maven-driver-version"
                                                                                        value={
                                                                                            mavenDriverVersionInput
                                                                                        }
                                                                                        onChange={(
                                                                                            event
                                                                                        ) =>
                                                                                            setMavenDriverVersionInput(
                                                                                                event
                                                                                                    .target
                                                                                                    .value
                                                                                            )
                                                                                        }
                                                                                        placeholder="42.7.5"
                                                                                        disabled={
                                                                                            loadingMavenDriverVersions
                                                                                        }
                                                                                    />
                                                                                )}
                                                                            </div>

                                                                            {loadingMavenDriverVersions ? (
                                                                                <p className="muted-id">
                                                                                    Loading
                                                                                    available
                                                                                    versions...
                                                                                </p>
                                                                            ) : null}
                                                                            {mavenDriverVersionsError ? (
                                                                                <p className="form-error">
                                                                                    {
                                                                                        mavenDriverVersionsError
                                                                                    }
                                                                                </p>
                                                                            ) : null}

                                                                            <button
                                                                                type="button"
                                                                                className="chip upload-driver-button"
                                                                                onClick={() =>
                                                                                    void handleInstallMavenDriver()
                                                                                }
                                                                                disabled={
                                                                                    installingMavenDriver
                                                                                }
                                                                            >
                                                                                {installingMavenDriver
                                                                                    ? 'Downloading...'
                                                                                    : 'Download Driver Jar'}
                                                                            </button>
                                                                        </div>
                                                                    ) : null}
                                                                </div>
                                                            </details>

                                                            <details className="managed-advanced-block">
                                                                <DetailsSummary>
                                                                    Pooling{' '}
                                                                    <InfoHint text="Connection pooling keeps a small set of warm connections for each credential profile to reduce latency. Tune carefully to avoid exhausting database connection limits." />
                                                                </DetailsSummary>

                                                                <div className="managed-advanced-body">
                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-pool-max">
                                                                            Maximum Pool Size
                                                                        </label>
                                                                        <input
                                                                            id="managed-pool-max"
                                                                            type="number"
                                                                            min={1}
                                                                            value={
                                                                                managedDatasourceForm.maximumPoolSize
                                                                            }
                                                                            onChange={(event) =>
                                                                                setManagedDatasourceForm(
                                                                                    (current) => ({
                                                                                        ...current,
                                                                                        maximumPoolSize:
                                                                                            event
                                                                                                .target
                                                                                                .value
                                                                                    })
                                                                                )
                                                                            }
                                                                            required
                                                                        />
                                                                    </div>

                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-pool-min">
                                                                            Minimum Idle
                                                                        </label>
                                                                        <input
                                                                            id="managed-pool-min"
                                                                            type="number"
                                                                            min={1}
                                                                            value={
                                                                                managedDatasourceForm.minimumIdle
                                                                            }
                                                                            onChange={(event) =>
                                                                                setManagedDatasourceForm(
                                                                                    (current) => ({
                                                                                        ...current,
                                                                                        minimumIdle:
                                                                                            event
                                                                                                .target
                                                                                                .value
                                                                                    })
                                                                                )
                                                                            }
                                                                            required
                                                                        />
                                                                    </div>

                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-pool-connection-timeout">
                                                                            Connection Timeout (ms)
                                                                        </label>
                                                                        <input
                                                                            id="managed-pool-connection-timeout"
                                                                            type="number"
                                                                            min={1}
                                                                            value={
                                                                                managedDatasourceForm.connectionTimeoutMs
                                                                            }
                                                                            onChange={(event) =>
                                                                                setManagedDatasourceForm(
                                                                                    (current) => ({
                                                                                        ...current,
                                                                                        connectionTimeoutMs:
                                                                                            event
                                                                                                .target
                                                                                                .value
                                                                                    })
                                                                                )
                                                                            }
                                                                            required
                                                                        />
                                                                    </div>

                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-pool-idle-timeout">
                                                                            Idle Timeout (ms)
                                                                        </label>
                                                                        <input
                                                                            id="managed-pool-idle-timeout"
                                                                            type="number"
                                                                            min={1}
                                                                            value={
                                                                                managedDatasourceForm.idleTimeoutMs
                                                                            }
                                                                            onChange={(event) =>
                                                                                setManagedDatasourceForm(
                                                                                    (current) => ({
                                                                                        ...current,
                                                                                        idleTimeoutMs:
                                                                                            event
                                                                                                .target
                                                                                                .value
                                                                                    })
                                                                                )
                                                                            }
                                                                            required
                                                                        />
                                                                    </div>
                                                                </div>
                                                            </details>

                                                            <details className="managed-advanced-block">
                                                                <DetailsSummary>
                                                                    TLS{' '}
                                                                    <InfoHint text="TLS encrypts traffic between dwarvenpick and your database. For production, enable TLS and verify the server certificate (recommended)." />
                                                                </DetailsSummary>

                                                                <div className="managed-advanced-body">
                                                                    <div className="form-field">
                                                                        <label htmlFor="managed-tls-mode">
                                                                            TLS Mode{' '}
                                                                            <InfoHint text="Controls whether dwarvenpick uses TLS when connecting to this database." />
                                                                        </label>
                                                                        <div className="select-wrap">
                                                                            <select
                                                                                id="managed-tls-mode"
                                                                                value={
                                                                                    managedDatasourceForm.tlsMode
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            tlsMode:
                                                                                                event
                                                                                                    .target
                                                                                                    .value as TlsMode
                                                                                        })
                                                                                    )
                                                                                }
                                                                            >
                                                                                <option value="DISABLE">
                                                                                    Disable
                                                                                </option>
                                                                                <option value="REQUIRE">
                                                                                    Require
                                                                                </option>
                                                                            </select>
                                                                        </div>
                                                                    </div>

                                                                    <div className="checkbox-stack">
                                                                        <label className="checkbox-row">
                                                                            <input
                                                                                type="checkbox"
                                                                                checked={
                                                                                    managedDatasourceForm.verifyServerCertificate
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            verifyServerCertificate:
                                                                                                event
                                                                                                    .target
                                                                                                    .checked,
                                                                                            allowSelfSigned:
                                                                                                event
                                                                                                    .target
                                                                                                    .checked
                                                                                                    ? false
                                                                                                    : current.allowSelfSigned
                                                                                        })
                                                                                    )
                                                                                }
                                                                            />
                                                                            <span>
                                                                                Verify server
                                                                                certificate
                                                                            </span>
                                                                        </label>
                                                                        <label className="checkbox-row">
                                                                            <input
                                                                                type="checkbox"
                                                                                checked={
                                                                                    managedDatasourceForm.allowSelfSigned
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setManagedDatasourceForm(
                                                                                        (
                                                                                            current
                                                                                        ) => ({
                                                                                            ...current,
                                                                                            allowSelfSigned:
                                                                                                event
                                                                                                    .target
                                                                                                    .checked,
                                                                                            verifyServerCertificate:
                                                                                                event
                                                                                                    .target
                                                                                                    .checked
                                                                                                    ? false
                                                                                                    : current.verifyServerCertificate
                                                                                        })
                                                                                    )
                                                                                }
                                                                            />
                                                                            <span>
                                                                                Allow self-signed
                                                                                certificates
                                                                            </span>
                                                                        </label>
                                                                    </div>

                                                                    <div className="form-field tls-certificates-section">
                                                                        <label className="form-section-label">
                                                                            SSL Certificates{' '}
                                                                            <InfoHint text="Optional certificates for TLS. Provide a CA certificate to verify the database server, and (optionally) a client certificate + private key for mutual TLS." />
                                                                        </label>
                                                                        <p className="muted-id">
                                                                            Certificates are stored
                                                                            on the server and
                                                                            applied when you save
                                                                            this connection.
                                                                        </p>

                                                                        <div className="form-field">
                                                                            <label htmlFor="tls-ca-certificate">
                                                                                CA Certificate (PEM){' '}
                                                                                <InfoHint text="Used to verify the database server certificate when TLS verification is enabled." />
                                                                            </label>
                                                                            <input
                                                                                id="tls-ca-certificate"
                                                                                type="file"
                                                                                accept=".pem,.crt,.cer,text/plain"
                                                                                onChange={(
                                                                                    event
                                                                                ) => {
                                                                                    const file =
                                                                                        event.target
                                                                                            .files?.[0] ??
                                                                                        null;
                                                                                    if (!file) {
                                                                                        return;
                                                                                    }
                                                                                    void (async () => {
                                                                                        try {
                                                                                            setAdminError(
                                                                                                ''
                                                                                            );
                                                                                            setAdminSuccess(
                                                                                                ''
                                                                                            );
                                                                                            setTlsCaCertificatePemInput(
                                                                                                await file.text()
                                                                                            );
                                                                                            setTlsCaCertificateFileName(
                                                                                                file.name
                                                                                            );
                                                                                        } catch {
                                                                                            setAdminError(
                                                                                                'Failed to read CA certificate file.'
                                                                                            );
                                                                                        }
                                                                                    })();
                                                                                }}
                                                                            />
                                                                            {tlsCaCertificatePemInput ===
                                                                            '' ? (
                                                                                <p className="muted-id">
                                                                                    CA certificate
                                                                                    will be removed
                                                                                    when you save.
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsCaCertificatePemInput(
                                                                                                null
                                                                                            );
                                                                                            setTlsCaCertificateFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Undo
                                                                                    </button>
                                                                                </p>
                                                                            ) : tlsCaCertificatePemInput ? (
                                                                                <p className="muted-id">
                                                                                    Selected:{' '}
                                                                                    <code>
                                                                                        {tlsCaCertificateFileName ||
                                                                                            'ca.pem'}
                                                                                    </code>{' '}
                                                                                    (will be saved)
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsCaCertificatePemInput(
                                                                                                null
                                                                                            );
                                                                                            setTlsCaCertificateFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Undo
                                                                                    </button>
                                                                                </p>
                                                                            ) : selectedManagedDatasource
                                                                                  ?.tlsCertificates
                                                                                  .hasCaCertificate ? (
                                                                                <p className="muted-id">
                                                                                    CA certificate
                                                                                    is stored.
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsCaCertificatePemInput(
                                                                                                ''
                                                                                            );
                                                                                            setTlsCaCertificateFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Remove
                                                                                    </button>
                                                                                </p>
                                                                            ) : null}
                                                                        </div>

                                                                        <div className="form-field">
                                                                            <label htmlFor="tls-client-certificate">
                                                                                Client Certificate
                                                                                (PEM){' '}
                                                                                <InfoHint text="Optional. Provide together with a client private key to enable mutual TLS authentication." />
                                                                            </label>
                                                                            <input
                                                                                id="tls-client-certificate"
                                                                                type="file"
                                                                                accept=".pem,.crt,.cer,text/plain"
                                                                                onChange={(
                                                                                    event
                                                                                ) => {
                                                                                    const file =
                                                                                        event.target
                                                                                            .files?.[0] ??
                                                                                        null;
                                                                                    if (!file) {
                                                                                        return;
                                                                                    }
                                                                                    void (async () => {
                                                                                        try {
                                                                                            setAdminError(
                                                                                                ''
                                                                                            );
                                                                                            setAdminSuccess(
                                                                                                ''
                                                                                            );
                                                                                            setTlsClientCertificatePemInput(
                                                                                                await file.text()
                                                                                            );
                                                                                            setTlsClientCertificateFileName(
                                                                                                file.name
                                                                                            );
                                                                                        } catch {
                                                                                            setAdminError(
                                                                                                'Failed to read client certificate file.'
                                                                                            );
                                                                                        }
                                                                                    })();
                                                                                }}
                                                                            />
                                                                            {tlsClientCertificatePemInput ===
                                                                            '' ? (
                                                                                <p className="muted-id">
                                                                                    Client
                                                                                    certificate will
                                                                                    be removed when
                                                                                    you save.
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsClientCertificatePemInput(
                                                                                                null
                                                                                            );
                                                                                            setTlsClientCertificateFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Undo
                                                                                    </button>
                                                                                </p>
                                                                            ) : tlsClientCertificatePemInput ? (
                                                                                <p className="muted-id">
                                                                                    Selected:{' '}
                                                                                    <code>
                                                                                        {tlsClientCertificateFileName ||
                                                                                            'client.pem'}
                                                                                    </code>{' '}
                                                                                    (will be saved)
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsClientCertificatePemInput(
                                                                                                null
                                                                                            );
                                                                                            setTlsClientCertificateFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Undo
                                                                                    </button>
                                                                                </p>
                                                                            ) : selectedManagedDatasource
                                                                                  ?.tlsCertificates
                                                                                  .hasClientCertificate ? (
                                                                                <p className="muted-id">
                                                                                    Client
                                                                                    certificate is
                                                                                    stored.
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsClientCertificatePemInput(
                                                                                                ''
                                                                                            );
                                                                                            setTlsClientCertificateFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Remove
                                                                                    </button>
                                                                                </p>
                                                                            ) : null}
                                                                        </div>

                                                                        <div className="form-field">
                                                                            <label htmlFor="tls-client-key">
                                                                                Client Private Key
                                                                                (PEM){' '}
                                                                                <InfoHint text="Optional. Must be an unencrypted PKCS#8 PEM (BEGIN PRIVATE KEY). Provide together with a client certificate." />
                                                                            </label>
                                                                            <input
                                                                                id="tls-client-key"
                                                                                type="file"
                                                                                accept=".pem,.key,text/plain"
                                                                                onChange={(
                                                                                    event
                                                                                ) => {
                                                                                    const file =
                                                                                        event.target
                                                                                            .files?.[0] ??
                                                                                        null;
                                                                                    if (!file) {
                                                                                        return;
                                                                                    }
                                                                                    void (async () => {
                                                                                        try {
                                                                                            setAdminError(
                                                                                                ''
                                                                                            );
                                                                                            setAdminSuccess(
                                                                                                ''
                                                                                            );
                                                                                            setTlsClientKeyPemInput(
                                                                                                await file.text()
                                                                                            );
                                                                                            setTlsClientKeyFileName(
                                                                                                file.name
                                                                                            );
                                                                                        } catch {
                                                                                            setAdminError(
                                                                                                'Failed to read client key file.'
                                                                                            );
                                                                                        }
                                                                                    })();
                                                                                }}
                                                                            />
                                                                            {tlsClientKeyPemInput ===
                                                                            '' ? (
                                                                                <p className="muted-id">
                                                                                    Client key will
                                                                                    be removed when
                                                                                    you save.
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsClientKeyPemInput(
                                                                                                null
                                                                                            );
                                                                                            setTlsClientKeyFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Undo
                                                                                    </button>
                                                                                </p>
                                                                            ) : tlsClientKeyPemInput ? (
                                                                                <p className="muted-id">
                                                                                    Selected:{' '}
                                                                                    <code>
                                                                                        {tlsClientKeyFileName ||
                                                                                            'client.key'}
                                                                                    </code>{' '}
                                                                                    (will be saved)
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsClientKeyPemInput(
                                                                                                null
                                                                                            );
                                                                                            setTlsClientKeyFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Undo
                                                                                    </button>
                                                                                </p>
                                                                            ) : selectedManagedDatasource
                                                                                  ?.tlsCertificates
                                                                                  .hasClientKey ? (
                                                                                <p className="muted-id">
                                                                                    Client key is
                                                                                    stored.
                                                                                    <button
                                                                                        type="button"
                                                                                        className="chip"
                                                                                        onClick={() => {
                                                                                            setTlsClientKeyPemInput(
                                                                                                ''
                                                                                            );
                                                                                            setTlsClientKeyFileName(
                                                                                                ''
                                                                                            );
                                                                                        }}
                                                                                    >
                                                                                        Remove
                                                                                    </button>
                                                                                </p>
                                                                            ) : null}
                                                                        </div>
                                                                    </div>
                                                                </div>
                                                            </details>

                                                            <div className="row connection-form-actions">
                                                                <button
                                                                    type="submit"
                                                                    className="connection-submit-button"
                                                                    disabled={savingDatasource}
                                                                >
                                                                    {savingDatasource
                                                                        ? 'Saving...'
                                                                        : selectedManagedDatasource
                                                                          ? 'Update Connection'
                                                                          : 'Create Connection'}
                                                                </button>
                                                                <button
                                                                    type="button"
                                                                    className="chip"
                                                                    onClick={
                                                                        handleCancelConnectionEditor
                                                                    }
                                                                    disabled={savingDatasource}
                                                                >
                                                                    Cancel
                                                                </button>
                                                            </div>
                                                        </form>

                                                        {selectedManagedDatasource ? (
                                                            <details className="managed-advanced-block managed-connection-tools">
                                                                <DetailsSummary>
                                                                    Credential Profiles and
                                                                    Connection Test
                                                                </DetailsSummary>
                                                                <div className="managed-advanced-body">
                                                                    <div className="managed-datasource-actions">
                                                                        <h4>Credential Profiles</h4>
                                                                        <ul className="credentials-list">
                                                                            {selectedManagedDatasource.credentialProfiles.map(
                                                                                (profile) => (
                                                                                    <li
                                                                                        key={`${selectedManagedDatasource.id}-${profile.profileId}`}
                                                                                    >
                                                                                        <strong>
                                                                                            {
                                                                                                profile.profileId
                                                                                            }
                                                                                        </strong>{' '}
                                                                                        (
                                                                                        {
                                                                                            profile.username
                                                                                        }
                                                                                        ) key:{' '}
                                                                                        {
                                                                                            profile.encryptionKeyId
                                                                                        }
                                                                                    </li>
                                                                                )
                                                                            )}
                                                                        </ul>

                                                                        <form
                                                                            className="stack-form"
                                                                            onSubmit={
                                                                                handleSaveCredentialProfile
                                                                            }
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
                                                                                onChange={(
                                                                                    event
                                                                                ) => {
                                                                                    const selectedProfileId =
                                                                                        event.target
                                                                                            .value;
                                                                                    if (
                                                                                        !selectedProfileId
                                                                                    ) {
                                                                                        return;
                                                                                    }

                                                                                    setCredentialProfileIdInput(
                                                                                        selectedProfileId
                                                                                    );
                                                                                    setSelectedCredentialProfileForTest(
                                                                                        selectedProfileId
                                                                                    );
                                                                                }}
                                                                            >
                                                                                <option value="">
                                                                                    Select existing
                                                                                    profile
                                                                                </option>
                                                                                {selectedManagedDatasource.credentialProfiles.map(
                                                                                    (profile) => (
                                                                                        <option
                                                                                            key={
                                                                                                profile.profileId
                                                                                            }
                                                                                            value={
                                                                                                profile.profileId
                                                                                            }
                                                                                        >
                                                                                            {
                                                                                                profile.profileId
                                                                                            }
                                                                                        </option>
                                                                                    )
                                                                                )}
                                                                            </select>

                                                                            <label htmlFor="credential-profile-id">
                                                                                Profile ID
                                                                            </label>
                                                                            <input
                                                                                id="credential-profile-id"
                                                                                value={
                                                                                    credentialProfileIdInput
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setCredentialProfileIdInput(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                }
                                                                                placeholder="admin-ro"
                                                                                required
                                                                            />

                                                                            <label htmlFor="credential-username">
                                                                                Username
                                                                            </label>
                                                                            <input
                                                                                id="credential-username"
                                                                                value={
                                                                                    credentialUsernameInput
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setCredentialUsernameInput(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                }
                                                                                required
                                                                            />

                                                                            <label htmlFor="credential-password">
                                                                                Password
                                                                            </label>
                                                                            <input
                                                                                id="credential-password"
                                                                                type="password"
                                                                                value={
                                                                                    credentialPasswordInput
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setCredentialPasswordInput(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                }
                                                                                required
                                                                            />

                                                                            <label htmlFor="credential-description">
                                                                                Description
                                                                            </label>
                                                                            <input
                                                                                id="credential-description"
                                                                                value={
                                                                                    credentialDescriptionInput
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setCredentialDescriptionInput(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                }
                                                                                placeholder="Readonly profile for analysts"
                                                                            />

                                                                            <button
                                                                                type="submit"
                                                                                disabled={
                                                                                    savingCredentialProfile
                                                                                }
                                                                            >
                                                                                {savingCredentialProfile
                                                                                    ? 'Saving...'
                                                                                    : 'Save Credential Profile'}
                                                                            </button>
                                                                        </form>

                                                                        <h4>Test Connection</h4>
                                                                        <form
                                                                            className="stack-form"
                                                                            onSubmit={
                                                                                handleTestConnection
                                                                            }
                                                                        >
                                                                            <label htmlFor="test-credential-profile">
                                                                                Credential Profile
                                                                            </label>
                                                                            <select
                                                                                id="test-credential-profile"
                                                                                value={
                                                                                    selectedCredentialProfileForTest
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setSelectedCredentialProfileForTest(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                }
                                                                            >
                                                                                <option value="">
                                                                                    Select
                                                                                    credential
                                                                                    profile
                                                                                </option>
                                                                                {selectedManagedDatasource.credentialProfiles.map(
                                                                                    (profile) => (
                                                                                        <option
                                                                                            key={
                                                                                                profile.profileId
                                                                                            }
                                                                                            value={
                                                                                                profile.profileId
                                                                                            }
                                                                                        >
                                                                                            {
                                                                                                profile.profileId
                                                                                            }
                                                                                        </option>
                                                                                    )
                                                                                )}
                                                                            </select>

                                                                            <label htmlFor="test-validation-query">
                                                                                Validation Query
                                                                            </label>
                                                                            <input
                                                                                id="test-validation-query"
                                                                                value={
                                                                                    validationQueryInput
                                                                                }
                                                                                onChange={(event) =>
                                                                                    setValidationQueryInput(
                                                                                        event.target
                                                                                            .value
                                                                                    )
                                                                                }
                                                                                placeholder="SELECT 1"
                                                                            />

                                                                            <label className="checkbox-row">
                                                                                <input
                                                                                    type="checkbox"
                                                                                    checked={
                                                                                        overrideTlsForTest
                                                                                    }
                                                                                    onChange={(
                                                                                        event
                                                                                    ) =>
                                                                                        setOverrideTlsForTest(
                                                                                            event
                                                                                                .target
                                                                                                .checked
                                                                                        )
                                                                                    }
                                                                                />
                                                                                <span>
                                                                                    Override
                                                                                    connection TLS
                                                                                    settings for
                                                                                    this test
                                                                                </span>
                                                                            </label>

                                                                            {overrideTlsForTest ? (
                                                                                <>
                                                                                    <label htmlFor="test-tls-mode">
                                                                                        TLS Mode
                                                                                    </label>
                                                                                    <select
                                                                                        id="test-tls-mode"
                                                                                        value={
                                                                                            testTlsMode
                                                                                        }
                                                                                        onChange={(
                                                                                            event
                                                                                        ) =>
                                                                                            setTestTlsMode(
                                                                                                event
                                                                                                    .target
                                                                                                    .value as TlsMode
                                                                                            )
                                                                                        }
                                                                                    >
                                                                                        <option value="DISABLE">
                                                                                            Disable
                                                                                        </option>
                                                                                        <option value="REQUIRE">
                                                                                            Require
                                                                                        </option>
                                                                                    </select>

                                                                                    <label className="checkbox-row">
                                                                                        <input
                                                                                            type="checkbox"
                                                                                            checked={
                                                                                                testVerifyServerCertificate
                                                                                            }
                                                                                            onChange={(
                                                                                                event
                                                                                            ) => {
                                                                                                const checked =
                                                                                                    event
                                                                                                        .target
                                                                                                        .checked;
                                                                                                setTestVerifyServerCertificate(
                                                                                                    checked
                                                                                                );
                                                                                                if (
                                                                                                    checked
                                                                                                ) {
                                                                                                    setTestAllowSelfSigned(
                                                                                                        false
                                                                                                    );
                                                                                                }
                                                                                            }}
                                                                                        />
                                                                                        <span>
                                                                                            Verify
                                                                                            server
                                                                                            certificate
                                                                                        </span>
                                                                                    </label>

                                                                                    <label className="checkbox-row">
                                                                                        <input
                                                                                            type="checkbox"
                                                                                            checked={
                                                                                                testAllowSelfSigned
                                                                                            }
                                                                                            onChange={(
                                                                                                event
                                                                                            ) => {
                                                                                                const checked =
                                                                                                    event
                                                                                                        .target
                                                                                                        .checked;
                                                                                                setTestAllowSelfSigned(
                                                                                                    checked
                                                                                                );
                                                                                                if (
                                                                                                    checked
                                                                                                ) {
                                                                                                    setTestVerifyServerCertificate(
                                                                                                        false
                                                                                                    );
                                                                                                }
                                                                                            }}
                                                                                        />
                                                                                        <span>
                                                                                            Allow
                                                                                            self-signed
                                                                                            certificates
                                                                                        </span>
                                                                                    </label>
                                                                                </>
                                                                            ) : null}

                                                                            <button
                                                                                type="submit"
                                                                                disabled={
                                                                                    testingConnection
                                                                                }
                                                                            >
                                                                                {testingConnection
                                                                                    ? 'Testing...'
                                                                                    : 'Run Test Connection'}
                                                                            </button>
                                                                        </form>

                                                                        {testConnectionMessage ? (
                                                                            <p
                                                                                className={
                                                                                    testConnectionOutcome ===
                                                                                    'success'
                                                                                        ? 'form-success'
                                                                                        : 'form-error'
                                                                                }
                                                                                role="alert"
                                                                            >
                                                                                {
                                                                                    testConnectionMessage
                                                                                }
                                                                            </p>
                                                                        ) : null}
                                                                    </div>
                                                                </div>
                                                            </details>
                                                        ) : (
                                                            <p className="muted-id connection-tools-note">
                                                                Save a connection first to manage
                                                                credential profiles and run tests.
                                                            </p>
                                                        )}
                                                    </div>
                                                )}
                                            </section>
                                        ) : null}
                                    </div>
                                ) : null}
                            </section>
                        </>
                    ) : null}
                </section>
            </div>
        </AppShell>
    );
}
