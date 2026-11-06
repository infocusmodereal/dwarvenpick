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
    HTMLTextAreaElement,
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
    const justificationInputRef = useRef<HTMLTextAreaElement | null>(null);
    const wasJustificationRequired = useRef(false);

    useEffect(() => {
        if (requiresJustification && !wasJustificationRequired.current) {
            setIsPolicyExpanded(true);
            justificationInputRef.current?.focus();
        }
        wasJustificationRequired.current = requiresJustification;
    }, [requiresJustification]);

    const setJustificationRef = (node: HTMLTextAreaElement | null) => {
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
                title={isPolicyExpanded ? 'Hide access and policy' : 'Show access and policy'}
                onClick={() => setIsPolicyExpanded((expanded) => !expanded)}
            >
                <span className="tile-heading-icon" aria-hidden>
                    <IconGlyph icon="shield-check" />
                </span>
                <span className="explorer-toolbar-label-text">Access &amp; policy</span>
                <span className="policy-chevron" aria-hidden>
                    <IconGlyph icon="chevron-down" />
                </span>
            </button>

            <div id={policyDetailsId} className="access-policy-details">
                {isPolicyExpanded &&
                    (canOverride ? (
                        <select
                            aria-label="Credential Profile"
                            value={requestedCredentialProfile}
                            onChange={(event) => onProfileChange(event.target.value)}
                            disabled={disabled}
                        >
                            <option value="">
                                {datasource?.credentialProfilePolicies?.[0]?.credentialProfile
                                    ? `Auto (RBAC): ${datasource.credentialProfilePolicies[0].credentialProfile}`
                                    : 'Auto (RBAC)'}
                            </option>
                            {(datasource?.credentialProfiles ?? []).map((profile) => (
                                <option key={profile} value={profile}>
                                    {profile}
                                </option>
                            ))}
                        </select>
                    ) : (
                        policy && <strong>{policy.credentialProfile}</strong>
                    ))}
                {requiresJustification && (
                    <div className="policy-reason">
                        <label htmlFor="tab-query-justification">
                            Justification <span aria-hidden>*</span>
                        </label>
                        <textarea
                            id="tab-query-justification"
                            placeholder="Change ticket or reason"
                            rows={2}
                            value={queryJustification}
                            onChange={(event) => onJustificationChange(event.target.value)}
                            required
                            disabled={disabled}
                            ref={setJustificationRef}
                        />
                    </div>
                )}
                {isPolicyExpanded && (
                    <details className="policy-secondary">
                        <summary>
                            <span className="policy-chevron" aria-hidden>
                                <IconGlyph icon="chevron-down" />
                            </span>
                            Permissions &amp; limits
                        </summary>
                        <div
                            className="credential-profile-policy"
                            aria-label="Effective credential profile policy"
                        >
                            {policy ? (
                                <>
                                    <div className="credential-profile-policy-title">
                                        <span>Effective profile</span>
                                        <strong>{policy.credentialProfile}</strong>
                                    </div>
                                    <dl className="policy-permissions">
                                        <div>
                                            <dt>SQL access</dt>
                                            <dd>
                                                {policy.readOnly ? 'Read-only' : 'Write-capable'}
                                            </dd>
                                        </div>
                                        <div>
                                            <dt>CSV</dt>
                                            <dd>
                                                {policy.canExport
                                                    ? 'Export allowed'
                                                    : 'Export blocked'}
                                            </dd>
                                        </div>
                                        {policy.sysadmin && (
                                            <div>
                                                <dt>Diagnostics</dt>
                                                <dd>Elevated health access</dd>
                                            </div>
                                        )}
                                    </dl>
                                    <h4>Query limits</h4>
                                    <dl className="policy-limits">
                                        <div>
                                            <dt>Rows</dt>
                                            <dd>{formatPolicyLimit(policy.maxRowsPerQuery)}</dd>
                                        </div>
                                        <div>
                                            <dt>Runtime</dt>
                                            <dd>
                                                {formatPolicyLimit(policy.maxRuntimeSeconds, 's')}
                                            </dd>
                                        </div>
                                        <div>
                                            <dt>Concurrency</dt>
                                            <dd>{formatPolicyLimit(policy.concurrencyLimit)}</dd>
                                        </div>
                                    </dl>
                                </>
                            ) : (
                                <p>Effective profile policy is unavailable.</p>
                            )}
                        </div>
                    </details>
                )}
            </div>
        </div>
    );
});

export default CredentialProfilePolicyControl;
