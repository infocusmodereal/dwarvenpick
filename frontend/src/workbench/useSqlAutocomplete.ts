import { useEffect, useState, type RefObject } from 'react';
import type { editor, languages } from 'monaco-editor';
import { sqlKeywordSuggestions } from './constants';
import type {
    AutocompleteDiagnostics,
    CatalogDatasourceResponse,
    DatasourceSchemaBrowserResponse
} from './types';

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

type Options = {
    datasourceId?: string;
    editorRef: RefObject<editor.IStandaloneCodeEditor | null>;
    monacoRef: RefObject<typeof import('monaco-editor') | null>;
    monacoReady: boolean;
    schemaBrowser: DatasourceSchemaBrowserResponse | null;
    visibleDatasources: CatalogDatasourceResponse[];
};

export function useSqlAutocomplete({
    datasourceId,
    editorRef,
    monacoRef,
    monacoReady,
    schemaBrowser,
    visibleDatasources
}: Options) {
    const [autocompleteDiagnostics, setAutocompleteDiagnostics] = useState(
        buildInitialAutocompleteDiagnostics
    );
    useEffect(() => {
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

        type CompletionSeed = Omit<languages.CompletionItem, 'range'>;
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

        if (schemaBrowser && schemaBrowser.datasourceId === datasourceId) {
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
        const labelToString = (label: CompletionSeed['label']): string =>
            typeof label === 'string' ? label : label.label;
        completionItems.sort((left, right) => {
            const leftSortKey = left.sortText ?? '';
            const rightSortKey = right.sortText ?? '';
            if (leftSortKey !== rightSortKey) {
                return leftSortKey.localeCompare(rightSortKey);
            }

            return labelToString(left.label)
                .toLowerCase()
                .localeCompare(labelToString(right.label).toLowerCase());
        });
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

                            const linePrefix = model.getValueInRange({
                                startLineNumber: position.lineNumber,
                                startColumn: 1,
                                endLineNumber: position.lineNumber,
                                endColumn: position.column
                            });
                            const lastTypedChar = linePrefix.slice(-1);
                            const inDotContext = lastTypedChar === '.';
                            const inFromContext = /\b(from|join)\s+$/i.test(linePrefix);

                            const contextualPool =
                                inDotContext || inFromContext
                                    ? completionItems.filter((candidate) => {
                                          const sortKey = candidate.sortText ?? '';
                                          if (inDotContext) {
                                              return (
                                                  sortKey.startsWith('1-') ||
                                                  sortKey.startsWith('2-')
                                              );
                                          }

                                          return sortKey.startsWith('1-');
                                      })
                                    : completionItems;

                            const candidates = normalizedWord
                                ? contextualPool.filter((candidate) =>
                                      labelToString(candidate.label)
                                          .toLowerCase()
                                          .includes(normalizedWord)
                                  )
                                : contextualPool;
                            const filteredSuggestions = candidates.slice(0, 500);
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
            providerDisposables.forEach((provider) => provider.dispose());
        };
    }, [datasourceId, editorRef, monacoReady, monacoRef, schemaBrowser, visibleDatasources]);

    return { autocompleteDiagnostics, setAutocompleteDiagnostics };
}
