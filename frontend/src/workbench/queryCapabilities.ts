/** SQL tooling capabilities live outside presentation components. Analyze preserves existing dialect semantics. */
export type QueryCapabilities = {
    explain: boolean;
    analyze: boolean;
    analyzeExecutes: boolean;
    analysisPrefix: string;
};
const explainOnly: QueryCapabilities = {
    explain: true,
    analyze: true,
    analyzeExecutes: false,
    analysisPrefix: 'EXPLAIN'
};
const capabilities: Record<string, QueryCapabilities> = {
    POSTGRESQL: {
        ...explainOnly,
        analyzeExecutes: true,
        analysisPrefix: 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)'
    },
    MYSQL: { ...explainOnly, analysisPrefix: 'EXPLAIN FORMAT=JSON' },
    MARIADB: { ...explainOnly, analysisPrefix: 'EXPLAIN FORMAT=JSON' },
    TRINO: { ...explainOnly, analyzeExecutes: true, analysisPrefix: 'EXPLAIN ANALYZE' },
    STARROCKS: explainOnly,
    VERTICA: explainOnly
};
export const queryCapabilities = (engine: string): QueryCapabilities =>
    capabilities[engine] ?? {
        explain: false,
        analyze: false,
        analyzeExecutes: false,
        analysisPrefix: ''
    };
export const analysisSql = (sql: string, capability: QueryCapabilities): string => {
    const normalized = sql.replace(/;+\s*$/, '').trim();
    return /^explain\b/i.test(normalized) || !normalized
        ? normalized
        : `${capability.analysisPrefix} ${normalized}`;
};
