import { useEffect, useState } from 'react';

export type Branding = {
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
    const environment = Array.from(text('environment')).slice(0, 5).join('');
    const path = text('logoPath');
    const logoPath = company && /^\/(?!\/)[^\\]*$/.test(path) ? path : '';
    return company || environment ? { company, environment, logoPath } : null;
}

export function useDeploymentBranding() {
    const [branding, setBranding] = useState<Branding | null>(null);

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

    return branding;
}
