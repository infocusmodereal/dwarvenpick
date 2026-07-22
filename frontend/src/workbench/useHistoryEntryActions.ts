import { useCallback } from 'react';
import type { Dispatch, MutableRefObject, SetStateAction } from 'react';
import { buildHistoryWorkspaceTab } from './queryHistoryContext';
import type {
    CatalogDatasourceResponse,
    QueryHistoryEntryResponse,
    WorkspaceSection,
    WorkspaceTab
} from './types';

type UseHistoryEntryActionsOptions = {
    executeSqlForTab: (tabId: string, sqlText: string, modeLabel: 'all') => Promise<void>;
    onError: (message: string) => void;
    onFeedback: (message: string) => void;
    setActiveSection: Dispatch<SetStateAction<WorkspaceSection>>;
    setActiveTabId: Dispatch<SetStateAction<string>>;
    setWorkspaceTabs: Dispatch<SetStateAction<WorkspaceTab[]>>;
    visibleDatasources: CatalogDatasourceResponse[];
    workspaceTabsRef: MutableRefObject<WorkspaceTab[]>;
};

export const useHistoryEntryActions = ({
    executeSqlForTab,
    onError,
    onFeedback,
    setActiveSection,
    setActiveTabId,
    setWorkspaceTabs,
    visibleDatasources,
    workspaceTabsRef
}: UseHistoryEntryActionsOptions) => {
    const openHistoryEntry = useCallback(
        (entry: QueryHistoryEntryResponse, runImmediately: boolean) => {
            const sqlText = entry.queryText?.trim();
            if (!sqlText) {
                onError('This history entry has no reusable SQL text.');
                return;
            }

            const resolvedDatasource =
                visibleDatasources.find((datasource) => datasource.id === entry.datasourceId) ??
                visibleDatasources[0];
            if (!resolvedDatasource) {
                onError('No permitted connection is available for rerun.');
                return;
            }

            const { tab: createdTab, datasourceFallback } = buildHistoryWorkspaceTab(
                entry,
                resolvedDatasource.id,
                resolvedDatasource.credentialProfiles,
                `History ${workspaceTabsRef.current.length + 1}`,
                sqlText
            );
            setWorkspaceTabs((currentTabs) => [...currentTabs, createdTab]);
            setActiveTabId(createdTab.id);
            setActiveSection('workbench');

            if (datasourceFallback) {
                onFeedback(
                    'The historical connection is no longer available. We opened the query with your current connection and cleared its schema and credential profile.'
                );
            }

            if (runImmediately && !datasourceFallback) {
                window.setTimeout(() => {
                    void executeSqlForTab(createdTab.id, sqlText, 'all');
                }, 0);
            }
        },
        [
            executeSqlForTab,
            onError,
            onFeedback,
            setActiveSection,
            setActiveTabId,
            setWorkspaceTabs,
            visibleDatasources,
            workspaceTabsRef
        ]
    );

    return { openHistoryEntry };
};
