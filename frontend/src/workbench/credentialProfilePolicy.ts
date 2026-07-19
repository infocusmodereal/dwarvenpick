import type { CatalogDatasourceResponse, EffectiveCredentialProfilePolicyResponse } from './types';

export const selectEffectiveCredentialProfilePolicy = (
    datasource: CatalogDatasourceResponse | undefined,
    requestedCredentialProfile: string
): EffectiveCredentialProfilePolicyResponse | undefined => {
    const policies = datasource?.credentialProfilePolicies ?? [];
    if (requestedCredentialProfile) {
        return policies.find((policy) => policy.credentialProfile === requestedCredentialProfile);
    }
    return policies[0];
};

export const isMissingRequiredQueryJustification = (
    policy: EffectiveCredentialProfilePolicyResponse | undefined,
    justification: string
): boolean => policy?.justificationMode === 'PROFILE_REQUIRED' && justification.trim().length === 0;
