import type { QueryHistoryEntryResponse, WorkspaceTab } from './types';
import { buildWorkspaceTab } from './useWorkspaceTabs';

export type HistoryWorkspaceTab = {
    tab: WorkspaceTab;
    datasourceFallback: boolean;
};

export const buildHistoryWorkspaceTab = (
    entry: QueryHistoryEntryResponse,
    resolvedDatasourceId: string,
    availableCredentialProfiles: string[],
    title: string,
    sqlText: string
): HistoryWorkspaceTab => {
    const tab = buildWorkspaceTab(resolvedDatasourceId, title, sqlText);
    const datasourceFallback = resolvedDatasourceId !== entry.datasourceId;

    if (!datasourceFallback) {
        tab.schema = entry.defaultSchema?.trim() ?? '';
        const historicalProfile = entry.credentialProfile.trim();
        tab.requestedCredentialProfile = availableCredentialProfiles.includes(historicalProfile)
            ? historicalProfile
            : '';
    }
    tab.queryJustification = entry.justification?.trim() ?? '';

    return { tab, datasourceFallback };
};
