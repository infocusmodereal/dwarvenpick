import type { QueryRunMode, ScriptTransactionMode, WorkspaceTab } from './types';

type QueryPayloadOptions = {
    includeCredentialProfile: boolean;
    includeJustification: boolean;
    modeLabel: QueryRunMode;
    scriptStopOnError: boolean;
    scriptTransactionMode: ScriptTransactionMode;
};

export const buildQueryExecutionPayload = (
    tab: WorkspaceTab,
    datasourceId: string,
    sql: string,
    options: QueryPayloadOptions
): Record<string, unknown> => {
    const requestPayload: Record<string, unknown> = {
        datasourceId,
        sql
    };

    const requestedCredentialProfile = tab.requestedCredentialProfile.trim();
    if (options.includeCredentialProfile && requestedCredentialProfile) {
        requestPayload.credentialProfile = requestedCredentialProfile;
    }

    const queryJustification = tab.queryJustification.trim();
    if (options.includeJustification && queryJustification) {
        requestPayload.justification = queryJustification;
    }

    const defaultSchema = tab.schema.trim();
    if (defaultSchema) {
        requestPayload.defaultSchema = defaultSchema;
    }

    if (options.modeLabel === 'script') {
        requestPayload.scriptMode = true;
        requestPayload.stopOnError = options.scriptStopOnError;
        requestPayload.transactionMode = options.scriptTransactionMode;
    }

    return requestPayload;
};

export const buildQueryValidationPayload = (
    tab: WorkspaceTab,
    datasourceId: string,
    sql: string,
    includeCredentialProfile: boolean
): Record<string, unknown> => {
    const requestPayload: Record<string, unknown> = {
        datasourceId,
        sql
    };

    const requestedCredentialProfile = tab.requestedCredentialProfile.trim();
    if (includeCredentialProfile && requestedCredentialProfile) {
        requestPayload.credentialProfile = requestedCredentialProfile;
    }

    const defaultSchema = tab.schema.trim();
    if (defaultSchema) {
        requestPayload.defaultSchema = defaultSchema;
    }

    return requestPayload;
};
