import { useEffect, useState } from 'react';

type Branding = {
    company: string;
    environment: string;
    logoPath: string;
};

function readBranding(value: unknown): Branding | null {
    if (!value || typeof value !== 'object') return null;
    const text = (key: string) => {
        const field: unknown = Reflect.get(value, key);
        return typeof field === 'string' ? field.trim() : '';
    };
    const company = text('company');
    const environment = text('environment');
    const path = text('logoPath');
    const logoPath = company && /^\/(?!\/)[^\\]*$/.test(path) ? path : '';
    return company || environment ? { company, environment, logoPath } : null;
}

export default function DeploymentBanner({ collapsed }: { collapsed: boolean }) {
    const [branding, setBranding] = useState<Branding | null>(null);
    const [logoFailed, setLogoFailed] = useState(false);

    useEffect(() => {
        const controller = new AbortController();
        async function load() {
            const response = await fetch('/branding/deployment.json', {
                signal: controller.signal,
                cache: 'no-cache'
            });
            if (response.ok) setBranding(readBranding(await response.json()));
        }
        // Branding is optional, including on deployments serving older static assets.
        void load().catch(() => {});
        return () => controller.abort();
    }, []);

    if (!branding) return null;
    const { company, environment, logoPath } = branding;
    return (
        <div
            className="deployment-banner"
            title={[company, environment].filter(Boolean).join(' · ')}
        >
            {company &&
                (!collapsed || !environment) &&
                (logoPath && !logoFailed && !collapsed ? (
                    <img src={logoPath} alt={company} onError={() => setLogoFailed(true)} />
                ) : (
                    <span className="deployment-company">{company}</span>
                ))}
            {environment && <span className="deployment-environment">{environment}</span>}
        </div>
    );
}
