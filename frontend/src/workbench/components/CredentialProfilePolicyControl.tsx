import { selectEffectiveCredentialProfilePolicy } from '../credentialProfilePolicy';
import type { CatalogDatasourceResponse } from '../types';
import { ExplorerIcon } from './WorkbenchIcons';

type CredentialProfilePolicyControlProps = {
    datasource?: CatalogDatasourceResponse;
    requestedCredentialProfile: string;
    canOverride: boolean;
    disabled: boolean;
    onProfileChange: (credentialProfile: string) => void;
};

const formatPolicyLimit = (value: number, suffix = ''): string =>
    value === 2147483647 ? 'Unlimited' : `${value.toLocaleString()}${suffix}`;

export default function CredentialProfilePolicyControl({
    datasource,
    requestedCredentialProfile,
    canOverride,
    disabled,
    onProfileChange
}: CredentialProfilePolicyControlProps) {
    const policy = selectEffectiveCredentialProfilePolicy(datasource, requestedCredentialProfile);

    return (
        <>
            {canOverride ? (
                <>
                    <div className="explorer-toolbar-label-row">
                        <span className="tile-heading-icon" aria-hidden>
                            <ExplorerIcon glyph="role" />
                        </span>
                        <label
                            htmlFor="tab-credential-profile"
                            className="explorer-toolbar-label-text"
                        >
                            Credential Profile
                        </label>
                    </div>
                    <div className="explorer-toolbar-control-row">
                        <div className="select-wrap">
                            <select
                                id="tab-credential-profile"
                                aria-label="Credential Profile"
                                value={requestedCredentialProfile}
                                onChange={(event) => onProfileChange(event.target.value)}
                                disabled={disabled}
                            >
                                <option value="">Auto (RBAC)</option>
                                {(datasource?.credentialProfiles ?? []).map((profile) => (
                                    <option key={`credential-profile-${profile}`} value={profile}>
                                        {profile}
                                    </option>
                                ))}
                            </select>
                        </div>
                    </div>
                </>
            ) : null}
            <div className="explorer-toolbar-label-row">
                <span className="tile-heading-icon" aria-hidden>
                    <ExplorerIcon glyph="role" />
                </span>
                <span className="explorer-toolbar-label-text">Effective Policy</span>
            </div>
            <div
                className="credential-profile-policy"
                aria-label="Effective credential profile policy"
            >
                {policy ? (
                    <>
                        <div className="credential-profile-policy-flags">
                            <strong>{policy.credentialProfile}</strong>
                            <span>{policy.readOnly ? 'Read-only' : 'Write-capable'}</span>
                            <span>{policy.canExport ? 'Export allowed' : 'Export blocked'}</span>
                            {policy.sysadmin ? <span>Elevated health access</span> : null}
                        </div>
                        <dl>
                            <div>
                                <dt>Rows</dt>
                                <dd>{formatPolicyLimit(policy.maxRowsPerQuery)}</dd>
                            </div>
                            <div>
                                <dt>Runtime</dt>
                                <dd>{formatPolicyLimit(policy.maxRuntimeSeconds, 's')}</dd>
                            </div>
                            <div>
                                <dt>Concurrency</dt>
                                <dd>{formatPolicyLimit(policy.concurrencyLimit)}</dd>
                            </div>
                        </dl>
                        <p>
                            {policy.readOnly
                                ? 'Write statements are blocked.'
                                : policy.justificationMode === 'PROFILE_REQUIRED'
                                  ? 'Justification required for every run, including SELECT.'
                                  : 'Justification is not required by the server.'}
                        </p>
                    </>
                ) : (
                    <p>Effective profile policy is unavailable.</p>
                )}
            </div>
        </>
    );
}
