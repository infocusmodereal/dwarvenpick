export type CurrentUserResponse = {
    username: string;
    displayName: string;
    email?: string;
    provider?: string;
    roles: string[];
    groups: string[];
};

export type AuthMethodsResponse = {
    methods?: string[];
};

export type VersionResponse = {
    service: string;
    version: string;
    artifact: string;
    group: string;
    buildTime: string;
};

export type CatalogDatasourceResponse = {
    id: string;
    name: string;
    engine: string;
    credentialProfiles: string[];
};

export type DatasourceEngine =
    | 'POSTGRESQL'
    | 'MYSQL'
    | 'MARIADB'
    | 'TRINO'
    | 'STARROCKS'
    | 'VERTICA';

export type TlsMode = 'DISABLE' | 'REQUIRE';

export type DriverDescriptorResponse = {
    driverId: string;
    engine: DatasourceEngine;
    driverClass: string;
    source: string;
    available: boolean;
    description: string;
    message: string;
    version?: string;
};

export type PoolSettings = {
    maximumPoolSize: number;
    minimumIdle: number;
    connectionTimeoutMs: number;
    idleTimeoutMs: number;
};

export type TlsSettings = {
    mode: TlsMode;
    verifyServerCertificate: boolean;
    allowSelfSigned: boolean;
};

export type ManagedCredentialProfileResponse = {
    profileId: string;
    username: string;
    description?: string;
    encryptionKeyId: string;
    updatedAt: string;
};

export type ManagedDatasourceResponse = {
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

export type GroupResponse = {
    id: string;
    name: string;
    description?: string;
    members: string[];
};

export type DatasourceAccessResponse = {
    groupId: string;
    datasourceId: string;
    canQuery: boolean;
    canExport: boolean;
    readOnly: boolean;
    maxRowsPerQuery?: number;
    maxRuntimeSeconds?: number;
    concurrencyLimit?: number;
    credentialProfile: string;
};

export type QueryExecutionResponse = {
    executionId: string;
    datasourceId: string;
    status: string;
    message: string;
    queryHash: string;
};

export type QueryExecutionStatusResponse = {
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

export type QueryResultColumn = {
    name: string;
    jdbcType: string;
};

export type QueryResultsResponse = {
    executionId: string;
    status: string;
    columns: QueryResultColumn[];
    rows: Array<Array<string | null>>;
    pageSize: number;
    nextPageToken?: string;
    rowLimitReached: boolean;
};

export type QueryStatusEventResponse = {
    eventId: string;
    executionId: string;
    datasourceId: string;
    status: string;
    message: string;
    occurredAt: string;
};

export type QueryHistoryEntryResponse = {
    executionId: string;
    actor: string;
    datasourceId: string;
    status: string;
    message: string;
    queryHash: string;
    queryText?: string;
    queryTextRedacted: boolean;
    errorSummary?: string;
    rowCount: number;
    columnCount: number;
    rowLimitReached: boolean;
    maxRowsPerQuery: number;
    maxRuntimeSeconds: number;
    credentialProfile: string;
    submittedAt: string;
    startedAt?: string;
    completedAt?: string;
    durationMs?: number;
};

export type AuditEventResponse = {
    type: string;
    actor?: string;
    outcome: string;
    ipAddress?: string;
    details: Record<string, unknown>;
    timestamp: string;
};

export type AdminUserResponse = {
    username: string;
    displayName: string;
    email?: string;
    provider: string;
    enabled: boolean;
    roles: string[];
    groups: string[];
    temporaryPassword: boolean;
};

export type DatasourceColumnEntryResponse = {
    name: string;
    jdbcType: string;
    nullable: boolean;
};

export type DatasourceTableEntryResponse = {
    table: string;
    type: string;
    columns: DatasourceColumnEntryResponse[];
};

export type DatasourceSchemaEntryResponse = {
    schema: string;
    tables: DatasourceTableEntryResponse[];
};

export type DatasourceSchemaBrowserResponse = {
    datasourceId: string;
    cached: boolean;
    fetchedAt: string;
    schemas: DatasourceSchemaEntryResponse[];
};

export type SnippetResponse = {
    snippetId: string;
    title: string;
    sql: string;
    owner: string;
    groupId?: string;
    createdAt: string;
    updatedAt: string;
};

export type PersistentWorkspaceTab = {
    id: string;
    title: string;
    datasourceId: string;
    schema: string;
    queryText: string;
};

export type WorkspaceTab = PersistentWorkspaceTab & {
    isExecuting: boolean;
    statusMessage: string;
    errorMessage: string;
    lastRunKind: 'query' | 'explain';
    executionId: string;
    executionStatus: string;
    queryHash: string;
    resultColumns: QueryResultColumn[];
    resultRows: Array<Array<string | null>>;
    nextPageToken: string;
    currentPageToken: string;
    previousPageTokens: string[];
    rowLimitReached: boolean;
    submittedAt: string;
    startedAt: string;
    completedAt: string;
    rowCount: number;
    columnCount: number;
    maxRowsPerQuery: number;
    maxRuntimeSeconds: number;
    credentialProfile: string;
};

export type TestConnectionResponse = {
    success: boolean;
    datasourceId: string;
    credentialProfile: string;
    driverId: string;
    driverClass: string;
    message: string;
};

export type ReencryptCredentialsResponse = {
    updatedProfiles: number;
    activeKeyId: string;
    message: string;
};

export type QueryRunMode = 'selection' | 'statement' | 'all' | 'explain';
export type WorkspaceSection =
    | 'workbench'
    | 'history'
    | 'snippets'
    | 'audit'
    | 'admin'
    | 'connections';
export type AdminSubsection = 'users' | 'groups' | 'access';
export type IconGlyph =
    | 'new'
    | 'rename'
    | 'duplicate'
    | 'close'
    | 'refresh'
    | 'copy'
    | 'info'
    | 'delete'
    | 'download';
export type ConnectionType = 'HOST_PORT' | 'JDBC_URL';
export type ConnectionAuthentication = 'USER_PASSWORD' | 'NO_AUTH';
export type DatasourceHealthState = 'active' | 'inactive' | 'unknown';
export type ConnectionEditorMode = 'list' | 'create' | 'edit';
export type GroupAdminMode = 'list' | 'create' | 'edit';
export type UserAdminMode = 'list' | 'create';
export type AccessAdminMode = 'list' | 'create' | 'edit';
export type EditorCursorLegend = {
    line: number;
    column: number;
    position: number;
    selectedChars: number;
    selectedLines: number;
};
export type AutocompleteDiagnostics = {
    enabled: boolean;
    monacoReady: boolean;
    editorMounted: boolean;
    modelLanguageId: string;
    availableLanguageIds: string[];
    registeredLanguageIds: string[];
    suggestionSeedCount: number;
    triggerCount: number;
    providerInvocationCount: number;
    lastSuggestionCount: number;
    lastTriggerSource: string;
    lastTriggerAt: string;
    lastInvocationAt: string;
    lastError: string;
};
export type ResultSortDirection = 'asc' | 'desc';
export type ResultSortState = {
    columnIndex: number;
    direction: ResultSortDirection;
} | null;
export type RailGlyph =
    | 'workbench'
    | 'history'
    | 'snippets'
    | 'audit'
    | 'admin'
    | 'connections'
    | 'collapse'
    | 'menu'
    | 'info';
export type ExplorerGlyph = 'database' | 'schema' | 'table' | 'column';

export type IconButtonProps = {
    icon: IconGlyph;
    title: string;
    onClick: () => void;
    disabled?: boolean;
    variant?: 'default' | 'danger';
};

export type ManagedDatasourceFormState = {
    name: string;
    engine: DatasourceEngine;
    connectionType: ConnectionType;
    host: string;
    port: string;
    database: string;
    jdbcUrl: string;
    optionsInput: string;
    authentication: ConnectionAuthentication;
    credentialProfileId: string;
    credentialUsername: string;
    credentialPassword: string;
    credentialDescription: string;
    driverId: string;
    maximumPoolSize: string;
    minimumIdle: string;
    connectionTimeoutMs: string;
    idleTimeoutMs: string;
    tlsMode: TlsMode;
    verifyServerCertificate: boolean;
    allowSelfSigned: boolean;
};

export type ApiErrorResponse = {
    error?: string;
};

export type CsrfTokenResponse = {
    token: string;
    headerName: string;
};
