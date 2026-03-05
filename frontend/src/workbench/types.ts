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
    sysadminCredentialProfiles?: string[];
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

export type MavenDriverPreset =
    | 'POSTGRESQL'
    | 'MYSQL'
    | 'MARIADB'
    | 'TRINO'
    | 'STARROCKS_MYSQL'
    | 'STARROCKS_MARIADB';

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

export type TlsCertificateStatus = {
    hasCaCertificate: boolean;
    hasClientCertificate: boolean;
    hasClientKey: boolean;
};

export type ManagedCredentialProfileResponse = {
    profileId: string;
    username: string;
    description?: string;
    sysadmin: boolean;
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
    tlsCertificates: TlsCertificateStatus;
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
    scriptSummary?: QueryScriptSummary | null;
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

export type QueryValidationResponse = {
    valid: boolean;
    message: string;
    line?: number;
    column?: number;
    position?: number;
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

export type SystemHealthStatus = 'OK' | 'INSUFFICIENT_PRIVILEGES' | 'UNSUPPORTED' | 'ERROR';

export type SystemHealthNode = {
    name: string;
    role?: string;
    status: string;
    details: Record<string, unknown>;
};

export type SystemHealthResponse = {
    datasourceId: string;
    datasourceName: string;
    engine: DatasourceEngine;
    credentialProfile: string;
    checkedAt: string;
    status: SystemHealthStatus;
    message?: string;
    nodeCount: number;
    healthyNodeCount: number;
    nodes: SystemHealthNode[];
    details: Record<string, unknown>;
};

export type ControlPlaneLatencySummary = {
    windowSeconds: number;
    sampleSize: number;
    succeededCount: number;
    failedCount: number;
    canceledCount: number;
    averageMs?: number | null;
    p50Ms?: number | null;
    p90Ms?: number | null;
    maxMs?: number | null;
    latestErrors: string[];
};

export type ControlPlanePoolStatus = {
    datasourceId: string;
    credentialProfile: string;
    activeConnections: number;
    idleConnections: number;
    totalConnections: number;
    maximumPoolSize: number;
    threadsAwaitingConnection: number;
};

export type ControlPlaneActiveQuery = {
    executionId: string;
    actor: string;
    datasourceId: string;
    credentialProfile: string;
    status: string;
    message: string;
    queryHash: string;
    sqlPreview: string;
    submittedAt: string;
    startedAt?: string | null;
    durationMs?: number | null;
    cancelRequested: boolean;
};

export type ControlPlaneDatasourceStatusResponse = {
    datasourceId: string;
    datasourceName: string;
    engine: DatasourceEngine;
    paused: boolean;
    fetchedAt: string;
    queuedCount: number;
    runningCount: number;
    pools: ControlPlanePoolStatus[];
    activeQueries: ControlPlaneActiveQuery[];
    latency: ControlPlaneLatencySummary;
};

export type ControlPlaneBulkActionResponse = {
    datasourceId: string;
    action: string;
    matched: number;
    succeeded: number;
    failed: number;
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

export type InspectedObjectType = 'TABLE' | 'VIEW';

export type ObjectInspectorSectionStatus =
    | 'OK'
    | 'UNSUPPORTED'
    | 'INSUFFICIENT_PRIVILEGES'
    | 'ERROR';

export type ObjectInspectorSectionKind = 'TEXT' | 'TABLE' | 'KEY_VALUES';

export type ObjectInspectorKeyValueResponse = {
    key: string;
    value: string | null;
};

export type ObjectInspectorTableResponse = {
    columns: string[];
    rows: Array<Array<string | null>>;
};

export type ObjectInspectorObjectRefResponse = {
    type: InspectedObjectType;
    schema: string;
    name: string;
};

export type ObjectInspectorSectionResponse = {
    id: string;
    title: string;
    status: ObjectInspectorSectionStatus;
    message?: string | null;
    kind?: ObjectInspectorSectionKind | null;
    text?: string | null;
    table?: ObjectInspectorTableResponse | null;
    keyValues?: ObjectInspectorKeyValueResponse[] | null;
};

export type ObjectInspectorResponse = {
    datasourceId: string;
    datasourceName: string;
    engine: DatasourceEngine;
    credentialProfile: string;
    inspectedAt: string;
    objectRef: ObjectInspectorObjectRefResponse;
    sections: ObjectInspectorSectionResponse[];
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
    requestedCredentialProfile: string;
    isExecuting: boolean;
    statusMessage: string;
    errorMessage: string;
    lastRunKind: 'query' | 'explain' | 'analyze' | 'script';
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
    scriptSummary?: QueryScriptSummary | null;
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

export type QueryRunMode = 'selection' | 'statement' | 'all' | 'script' | 'explain' | 'analyze';

export type ScriptTransactionMode = 'AUTOCOMMIT' | 'TRANSACTION';

export type QueryScriptStatementSummary = {
    index: number;
    status: string;
    sqlPreview: string;
    message: string;
};

export type QueryScriptSummary = {
    statementCount: number;
    stopOnError: boolean;
    transactionMode: ScriptTransactionMode | string;
    statements: QueryScriptStatementSummary[];
};
export type WorkspaceSection =
    | 'workbench'
    | 'history'
    | 'snippets'
    | 'audit'
    | 'health'
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
    | 'download'
    | 'play'
    | 'circle-play'
    | 'align-start-horizontal'
    | 'activity'
    | 'file-text'
    | 'shield-check'
    | 'settings';
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
    | 'health'
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
