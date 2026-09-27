import { firstPageToken } from './constants';
import type { QueryRunMode, WorkspaceTab } from './types';

export const queryRunStatusMessage = (modeLabel: QueryRunMode): string => {
    switch (modeLabel) {
        case 'selection':
            return 'Running selected SQL...';
        case 'statement':
            return 'Running statement at cursor...';
        case 'script':
            return 'Running script...';
        case 'explain':
            return 'Running EXPLAIN...';
        case 'analyze':
            return 'Running analysis...';
        default:
            return 'Running full tab SQL...';
    }
};

export const prepareTabForQueryExecution = (
    tab: WorkspaceTab,
    modeLabel: QueryRunMode,
    runKind: WorkspaceTab['lastRunKind']
): WorkspaceTab => ({
    ...tab,
    planExecution: undefined,
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
    maxExportRows: undefined,
    maxRuntimeSeconds: 0,
    credentialProfile: '',
    scriptSummary: null,
    statusMessage: queryRunStatusMessage(modeLabel),
    errorMessage: ''
});

// Explain/Analyze has its own execution lifecycle; never replace the query's rows or metadata.
export const executionForId = (tab: WorkspaceTab, executionId: string): WorkspaceTab | undefined =>
    tab.planExecution?.executionId === executionId
        ? tab.planExecution
        : tab.executionId === executionId
          ? tab
          : undefined;

export const updateExecutionForId = (
    tab: WorkspaceTab,
    executionId: string,
    updater: (execution: WorkspaceTab) => WorkspaceTab
): WorkspaceTab => {
    if (tab.planExecution?.executionId === executionId)
        return { ...tab, planExecution: updater(tab.planExecution) };
    return tab.executionId === executionId ? updater(tab) : tab;
};
