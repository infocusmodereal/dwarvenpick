import { forwardRef, useEffect, useId, useRef, useState } from 'react';
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

const CredentialProfilePolicyControl = forwardRef<
    HTMLInputElement,
    CredentialProfilePolicyControlProps
>(function CredentialProfilePolicyControl(
    {
        datasource,
        requestedCredentialProfile,
        queryJustification,
        canOverride,
        disabled,
        onProfileChange,
        onJustificationChange
    },
    forwardedJustificationRef
) {
    const [isPolicyExpanded, setIsPolicyExpanded] = useState(false);
    const policyDetailsId = useId();
    const policy = selectEffectiveCredentialProfilePolicy(datasource, requestedCredentialProfile);
    const requiresJustification = policy?.justificationMode === 'PROFILE_REQUIRED';
    const justificationInputRef = useRef<HTMLInputElement | null>(null);
    const wasJustificationRequired = useRef(false);

    useEffect(() => {
        if (requiresJustification && !wasJustificationRequired.current) {
            setIsPolicyExpanded(true);
            justificationInputRef.current?.focus();
        }
        wasJustificationRequired.current = requiresJustification;
    }, [requiresJustification]);

    const setJustificationRef = (node: HTMLInputElement | null) => {
        justificationInputRef.current = node;
        if (typeof forwardedJustificationRef === 'function') {
            forwardedJustificationRef(node);
        } else if (forwardedJustificationRef) {
            forwardedJustificationRef.current = node;
        }
    };

    return (
        <div className="access-policy-control">
            <button
                type="button"
                className="effective-policy-disclosure"
                aria-label={isPolicyExpanded ? 'Hide access and policy' : 'Show access and policy'}
                aria-expanded={isPolicyExpanded}
                aria-controls={policyDetailsId}
                onClick={() => setIsPolicyExpanded((expanded) => !expanded)}
            >
                <span className="tile-heading-icon" aria-hidden>
                    <IconGlyph icon="key-round" />
                </span>
                <span className="explorer-toolbar-label-text">Access &amp; Policy</span>
                <span className="effective-policy-disclosure-label" aria-hidden>
                    {isPolicyExpanded ? 'Hide' : 'Show'}
                </span>
            </button>

            {isPolicyExpanded ? (
                <div
                    id={policyDetailsId}
                    className="access-policy-details"
                    aria-label="Access and effective credential profile policy"
                >
                    {canOverride ? (
                        <div className="explorer-control-group">
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
                                        <option
                                            key={`credential-profile-${profile}`}
                                            value={profile}
                                        >
                                            {profile}
                                        </option>
                                    ))}
                                </select>
                            </div>
                        </div>
                    ) : null}

                    <div
                        className="credential-profile-policy"
                        aria-label="Effective credential profile policy"
                    >
                        {policy ? (
                            <>
                                <div className="credential-profile-policy-title">
                                    <IconGlyph icon="shield-check" />
                                    <strong>Effective Policy</strong>
                                </div>
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
                </div>
            ) : null}

            {requiresJustification ? (
                <div className="explorer-control-group justification-control">
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
                    <input
                        id="tab-query-justification"
                        type="text"
                        value={queryJustification}
                        onChange={(event) => onJustificationChange(event.target.value)}
                        placeholder="Change ticket or reason"
                        required
                        disabled={disabled}
                        ref={setJustificationRef}
                    />
                </div>
            ) : null}
        </div>
    );
});

export default CredentialProfilePolicyControl;
