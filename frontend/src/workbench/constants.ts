import type { DatasourceEngine, PoolSettings, TlsSettings } from './types';

export const defaultPoolSettings: PoolSettings = {
    maximumPoolSize: 5,
    minimumIdle: 1,
    connectionTimeoutMs: 30_000,
    idleTimeoutMs: 600_000
};

export const defaultTlsSettings: TlsSettings = {
    mode: 'DISABLE',
    verifyServerCertificate: true,
    allowSelfSigned: false
};

export const workspaceTabsStorageKey = 'dwarvenpick.workspace.tabs.v1';
export const queryStatusPollingIntervalMs = 500;
export const queryStatusPollingMaxAttempts = 600;
export const firstPageToken = '';
export const resultRowHeightPx = 31;
export const resultViewportHeightPx = 320;

export const builtInAuditActions = [
    'auth.local.login',
    'auth.ldap.login',
    'auth.ldap.group_sync',
    'auth.logout',
    'auth.local.user_create',
    'auth.password_reset',
    'query.execute',
    'query.cancel',
    'query.export',
    'snippet.create',
    'snippet.update',
    'snippet.delete'
];

export const builtInAuditOutcomes = [
    'success',
    'failed',
    'denied',
    'limited',
    'queued',
    'running',
    'succeeded',
    'canceled',
    'noop'
];

export const protectedGroupIds = new Set(['platform-admins', 'analytics-users']);

export const sqlKeywordSuggestions = [
    'SELECT',
    'DISTINCT',
    'FROM',
    'WHERE',
    'GROUP BY',
    'HAVING',
    'ORDER BY',
    'LIMIT',
    'OFFSET',
    'JOIN',
    'LEFT JOIN',
    'RIGHT JOIN',
    'INNER JOIN',
    'FULL OUTER JOIN',
    'CROSS JOIN',
    'UNION',
    'UNION ALL',
    'WITH',
    'INSERT INTO',
    'UPDATE',
    'DELETE FROM',
    'CREATE TABLE',
    'ALTER TABLE',
    'DROP TABLE',
    'CREATE VIEW',
    'DROP VIEW',
    'CREATE INDEX',
    'DROP INDEX',
    'PRIMARY KEY',
    'FOREIGN KEY',
    'VALUES',
    'SET',
    'AS',
    'AND',
    'OR',
    'NOT',
    'IN',
    'BETWEEN',
    'LIKE',
    'IS NULL',
    'IS NOT NULL',
    'CASE',
    'WHEN',
    'THEN',
    'ELSE',
    'END',
    'EXPLAIN',
    'COUNT',
    'SUM',
    'AVG',
    'MIN',
    'MAX'
];

export const defaultPortByEngine: Record<DatasourceEngine, number> = {
    POSTGRESQL: 5432,
    MYSQL: 3306,
    MARIADB: 3306,
    TRINO: 8088,
    STARROCKS: 9030,
    VERTICA: 5433
};
