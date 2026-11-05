import { useState } from 'react';
import type { Branding } from './useDeploymentBranding';

export default function DeploymentBanner({
    branding,
    collapsed
}: {
    branding: Branding | null;
    collapsed: boolean;
}) {
    const [logoFailed, setLogoFailed] = useState(false);
    if (!branding?.company) return null;
    const showLogo = Boolean(branding.logoPath) && !logoFailed;
    if (!showLogo && collapsed && branding.environment) return null;
    return (
        <div className="deployment-banner" title={branding.company}>
            {showLogo ? (
                <img
                    src={branding.logoPath}
                    alt={branding.company}
                    onError={() => setLogoFailed(true)}
                />
            ) : (
                <span className="deployment-company">{branding.company}</span>
            )}
        </div>
    );
}
