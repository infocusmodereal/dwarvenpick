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
    scriptSummary: null,
    statusMessage: queryRunStatusMessage(modeLabel),
    errorMessage: ''
});
