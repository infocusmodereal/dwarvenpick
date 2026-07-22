import { useId, useState } from 'react';
import { selectEffectiveCredentialProfilePolicy } from '../credentialProfilePolicy';
import type { CatalogDatasourceResponse } from '../types';
import { IconGlyph } from './WorkbenchIcons';

type CredentialProfilePolicyControlProps = {
    datasource?: CatalogDatasourceResponse;
    requestedCredentialProfile: string;
    queryJustification: string;
    canOverride: boolean;
    disabled: boolean;
    onProfileChange: (credentialProfile: string) => void;
    onJustificationChange: (justification: string) => void;
};

const formatPolicyLimit = (value: number, suffix = ''): string =>
    value === 2147483647 ? 'Unlimited' : `${value.toLocaleString()}${suffix}`;

export default function CredentialProfilePolicyControl({
    datasource,
    requestedCredentialProfile,
    queryJustification,
    canOverride,
    disabled,
    onProfileChange,
    onJustificationChange
}: CredentialProfilePolicyControlProps) {
    const [isPolicyExpanded, setIsPolicyExpanded] = useState(false);
    const policyDetailsId = useId();
    const policy = selectEffectiveCredentialProfilePolicy(datasource, requestedCredentialProfile);
    const requiresJustification = policy?.justificationMode === 'PROFILE_REQUIRED';

    return (
        <>
            {canOverride ? (
                <>
                    <div className="explorer-toolbar-label-row">
                        <span className="tile-heading-icon" aria-hidden>
                            <IconGlyph icon="key-round" />
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
            <button
                type="button"
                className="effective-policy-disclosure"
                aria-label={isPolicyExpanded ? 'Hide effective policy' : 'Show effective policy'}
                aria-expanded={isPolicyExpanded}
                aria-controls={policyDetailsId}
                onClick={() => setIsPolicyExpanded((expanded) => !expanded)}
            >
                <span className="tile-heading-icon" aria-hidden>
                    <IconGlyph icon="shield-check" />
                </span>
                <span className="explorer-toolbar-label-text">Effective Policy</span>
                <span className="effective-policy-summary">
                    {policy
                        ? `${policy.credentialProfile} · ${policy.readOnly ? 'read-only' : 'write-capable'}`
                        : 'Unavailable'}
                </span>
                <span className="effective-policy-disclosure-label" aria-hidden>
                    {isPolicyExpanded ? 'Hide' : 'Show'}
                </span>
            </button>
            {isPolicyExpanded ? (
                <div
                    id={policyDetailsId}
                    className="credential-profile-policy"
                    aria-label="Effective credential profile policy"
                >
                    {policy ? (
                        <>
                            <div className="credential-profile-policy-flags">
                                <strong>{policy.credentialProfile}</strong>
                                <span>{policy.readOnly ? 'Read-only' : 'Write-capable'}</span>
                                <span>
                                    {policy.canExport ? 'Export allowed' : 'Export blocked'}
                                </span>
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
                                    : requiresJustification
                                      ? 'Justification required for every run, including SELECT.'
                                      : 'Justification is not required by the server.'}
                            </p>
                        </>
                    ) : (
                        <p>Effective profile policy is unavailable.</p>
                    )}
                </div>
            ) : null}
            {requiresJustification ? (
                <>
                    <div className="explorer-toolbar-label-row">
                        <span className="tile-heading-icon" aria-hidden>
                            <IconGlyph icon="file-text" />
                        </span>
                        <label
                            htmlFor="tab-query-justification"
                            className="explorer-toolbar-label-text"
                        >
                            Justification
                        </label>
                    </div>
                    <div className="explorer-toolbar-control-row">
                        <input
                            id="tab-query-justification"
                            type="text"
                            value={queryJustification}
                            onChange={(event) => onJustificationChange(event.target.value)}
                            placeholder="Change ticket or reason"
                            required
                            disabled={disabled}
                        />
                    </div>
                </>
            ) : null}
        </>
    );
}
