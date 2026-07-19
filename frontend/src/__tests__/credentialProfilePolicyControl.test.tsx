import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import CredentialProfilePolicyControl from '../workbench/components/CredentialProfilePolicyControl';
import { isMissingRequiredQueryJustification } from '../workbench/credentialProfilePolicy';
import type { CatalogDatasourceResponse } from '../workbench/types';

const datasource: CatalogDatasourceResponse = {
    id: 'starrocks-dev-adhoc',
    name: 'StarRocks Dev Adhoc',
    engine: 'STARROCKS',
    credentialProfiles: ['read-only', 'read-write'],
    credentialProfilePolicies: [
        {
            credentialProfile: 'read-only',
            readOnly: true,
            canExport: false,
            maxRowsPerQuery: 1000,
            maxRuntimeSeconds: 60,
            concurrencyLimit: 2,
            sysadmin: false,
            justificationMode: 'NONE'
        },
        {
            credentialProfile: 'read-write',
            readOnly: false,
            canExport: false,
            maxRowsPerQuery: 500,
            maxRuntimeSeconds: 30,
            concurrencyLimit: 1,
            sysadmin: false,
            justificationMode: 'PROFILE_REQUIRED'
        }
    ]
};

const ProfileControlHarness = () => {
    const [profile, setProfile] = useState('');
    return (
        <CredentialProfilePolicyControl
            datasource={datasource}
            requestedCredentialProfile={profile}
            canOverride
            disabled={false}
            onProfileChange={setProfile}
        />
    );
};

describe('CredentialProfilePolicyControl', () => {
    it('shows the Auto policy and updates governance when an admin switches profiles', async () => {
        const user = userEvent.setup();
        render(<ProfileControlHarness />);

        const policy = screen.getByLabelText('Effective credential profile policy');
        expect(within(policy).getByText('read-only')).toBeInTheDocument();
        expect(within(policy).getByText('Write statements are blocked.')).toBeInTheDocument();
        expect(within(policy).getByText('Export blocked')).toBeInTheDocument();

        await user.selectOptions(
            screen.getByRole('combobox', { name: 'Credential Profile' }),
            'read-write'
        );

        expect(within(policy).getByText('Write-capable')).toBeInTheDocument();
        expect(
            screen.getByText('Justification required for every run, including SELECT.')
        ).toBeInTheDocument();
        expect(within(policy).getByText('Rows').nextElementSibling?.textContent).toBe('500');
    });

    it('formats unlimited sentinels without exposing raw integer values', () => {
        const unlimitedDatasource: CatalogDatasourceResponse = {
            ...datasource,
            credentialProfilePolicies: [
                {
                    ...datasource.credentialProfilePolicies![0],
                    maxRowsPerQuery: 2147483647,
                    maxRuntimeSeconds: 2147483647,
                    concurrencyLimit: 2147483647
                }
            ]
        };
        render(
            <CredentialProfilePolicyControl
                datasource={unlimitedDatasource}
                requestedCredentialProfile=""
                canOverride={false}
                disabled={false}
                onProfileChange={() => undefined}
            />
        );

        expect(screen.getAllByText('Unlimited')).toHaveLength(3);
        expect(screen.queryByText('2147483647')).not.toBeInTheDocument();
    });

    it('detects a blank justification only for a governed write-capable policy', () => {
        const readOnlyPolicy = datasource.credentialProfilePolicies![0];
        const writePolicy = datasource.credentialProfilePolicies![1];

        expect(isMissingRequiredQueryJustification(readOnlyPolicy, '')).toBe(false);
        expect(isMissingRequiredQueryJustification(writePolicy, '   ')).toBe(true);
        expect(isMissingRequiredQueryJustification(writePolicy, 'TOPS-123')).toBe(false);
    });

    it('shows the principal default policy without enabling non-admin profile switching', () => {
        render(
            <CredentialProfilePolicyControl
                datasource={datasource}
                requestedCredentialProfile=""
                canOverride={false}
                disabled={false}
                onProfileChange={() => undefined}
            />
        );

        expect(
            screen.queryByRole('combobox', { name: 'Credential Profile' })
        ).not.toBeInTheDocument();
        const policy = screen.getByLabelText('Effective credential profile policy');
        expect(within(policy).getByText('read-only')).toBeInTheDocument();
        expect(within(policy).getByText('Write statements are blocked.')).toBeInTheDocument();
    });
});
