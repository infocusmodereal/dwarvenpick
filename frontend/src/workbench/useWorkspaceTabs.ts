import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { workspaceTabsStorageKey } from './constants';
import type {
    CatalogDatasourceResponse,
    PersistentWorkspaceTab,
    ResourceScriptResponse,
    WorkspaceTab
} from './types';

const createTabId = (): string => {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }

    return `tab-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

export const buildWorkspaceTab = (
    datasourceId: string,
    title: string,
    queryText = 'SELECT 1;',
    resource?: ResourceScriptResponse | null
): WorkspaceTab => ({
    id: createTabId(),
    title,
    datasourceId,
    schema: '',
    resourceId: resource?.resourceId,
    resourceTitle: resource?.title,
    resourceScope: resource?.scope,
    resourceGroupId: resource?.groupId ?? undefined,
    resourceFolderPath: resource?.folderPath,
    resourceTags: resource?.tags ?? [],
    resourceAllowGroupEdit: resource?.allowGroupEdit,
    resourceOwner: resource?.owner,
    requestedCredentialProfile: '',
    queryJustification: '',
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
    credentialProfile: '',
    scriptSummary: null
});

export const toPersistentTab = (tab: WorkspaceTab): PersistentWorkspaceTab => ({
    id: tab.id,
    title: tab.title,
    datasourceId: tab.datasourceId,
    schema: tab.schema,
    queryText: tab.queryText,
    resourceId: tab.resourceId,
    resourceTitle: tab.resourceTitle,
    resourceScope: tab.resourceScope,
    resourceGroupId: tab.resourceGroupId,
    resourceFolderPath: tab.resourceFolderPath,
    resourceTags: tab.resourceTags,
    resourceAllowGroupEdit: tab.resourceAllowGroupEdit,
    resourceOwner: tab.resourceOwner
});

type PersistedTabsPayload = {
    activeTabId: string;
    tabs: PersistentWorkspaceTab[];
};

const readPersistedTabs = (): PersistedTabsPayload | null => {
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

export const hydrateStoredWorkspaceTabs = (
    datasources: CatalogDatasourceResponse[],
    stored: PersistedTabsPayload | null = readPersistedTabs()
): { activeTabId: string; tabs: WorkspaceTab[] } => {
    const allowedDatasourceIds = new Set(datasources.map((datasource) => datasource.id));
    const fallbackDatasourceId = datasources[0]?.id ?? '';

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
                resourceId:
                    typeof tab.resourceId === 'string' && tab.resourceId.trim()
                        ? tab.resourceId
                        : undefined,
                resourceTitle:
                    typeof tab.resourceTitle === 'string' && tab.resourceTitle.trim()
                        ? tab.resourceTitle
                        : undefined,
                resourceScope:
                    tab.resourceScope === 'PRIVATE' || tab.resourceScope === 'SHARED'
                        ? tab.resourceScope
                        : undefined,
                resourceGroupId:
                    typeof tab.resourceGroupId === 'string' && tab.resourceGroupId.trim()
                        ? tab.resourceGroupId
                        : undefined,
                resourceFolderPath:
                    typeof tab.resourceFolderPath === 'string' ? tab.resourceFolderPath : undefined,
                resourceTags: Array.isArray(tab.resourceTags) ? tab.resourceTags : [],
                resourceAllowGroupEdit:
                    typeof tab.resourceAllowGroupEdit === 'boolean'
                        ? tab.resourceAllowGroupEdit
                        : undefined,
                resourceOwner:
                    typeof tab.resourceOwner === 'string' && tab.resourceOwner.trim()
                        ? tab.resourceOwner
                        : undefined,
                requestedCredentialProfile: '',
                queryJustification: '',
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
                credentialProfile: '',
                scriptSummary: null
            })) ?? [];

    const tabs =
        hydratedTabs.length > 0
            ? hydratedTabs
            : [buildWorkspaceTab(fallbackDatasourceId, 'Query 1')];
    const activeCandidate = stored?.activeTabId ?? '';
    const activeTabId = tabs.some((tab) => tab.id === activeCandidate)
        ? activeCandidate
        : tabs[0].id;

    return { activeTabId, tabs };
};

export const useWorkspaceTabs = (visibleDatasources: CatalogDatasourceResponse[]) => {
    const [workspaceTabs, setWorkspaceTabs] = useState<WorkspaceTab[]>([]);
    const [activeTabId, setActiveTabId] = useState('');
    const [tabsHydrated, setTabsHydrated] = useState(false);
    const workspaceTabsRef = useRef<WorkspaceTab[]>([]);

    const activeTab = useMemo(
        () => workspaceTabs.find((tab) => tab.id === activeTabId) ?? null,
        [activeTabId, workspaceTabs]
    );

    useEffect(() => {
        workspaceTabsRef.current = workspaceTabs;
    }, [workspaceTabs]);

    const hydrateWorkspaceTabs = useCallback((datasources: CatalogDatasourceResponse[]) => {
        const hydrated = hydrateStoredWorkspaceTabs(datasources);
        setWorkspaceTabs(hydrated.tabs);
        setActiveTabId(hydrated.activeTabId);
        setTabsHydrated(true);
    }, []);

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

    return {
        activeTab,
        activeTabId,
        hydrateWorkspaceTabs,
        setActiveTabId,
        setWorkspaceTabs,
        tabsHydrated,
        updateWorkspaceTab,
        workspaceTabs,
        workspaceTabsRef
    };
};
