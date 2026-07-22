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
            sysadmin: true,
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

const ProfileControlHarness = ({ disabled = false }: { disabled?: boolean }) => {
    const [profile, setProfile] = useState('');
    const [justification, setJustification] = useState('');
    return (
        <CredentialProfilePolicyControl
            datasource={datasource}
            requestedCredentialProfile={profile}
            queryJustification={justification}
            canOverride
            disabled={disabled}
            onProfileChange={setProfile}
            onJustificationChange={setJustification}
        />
    );
};

describe('CredentialProfilePolicyControl', () => {
    it('collapses policy details by default and expands with the keyboard', async () => {
        const user = userEvent.setup();
        render(<ProfileControlHarness />);

        const disclosure = screen.getByRole('button', { name: 'Show effective policy' });
        expect(disclosure).toHaveAttribute('aria-expanded', 'false');
        expect(
            screen.queryByLabelText('Effective credential profile policy')
        ).not.toBeInTheDocument();
        expect(screen.queryByRole('textbox', { name: 'Justification' })).not.toBeInTheDocument();

        disclosure.focus();
        await user.keyboard('{Enter}');

        expect(screen.getByRole('button', { name: 'Hide effective policy' })).toHaveAttribute(
            'aria-expanded',
            'true'
        );
        const policy = screen.getByLabelText('Effective credential profile policy');
        expect(within(policy).getByText('read-only')).toBeInTheDocument();
        expect(within(policy).getByText('Read-only')).toBeInTheDocument();
        expect(within(policy).getByText('Export blocked')).toBeInTheDocument();
        expect(within(policy).getByText('Elevated health access')).toBeInTheDocument();
        expect(within(policy).getByText('Write statements are blocked.')).toBeInTheDocument();
        expect(within(policy).getByText('Rows').nextElementSibling?.textContent).toBe('1,000');
        expect(within(policy).getByText('Runtime').nextElementSibling?.textContent).toBe('60s');
        expect(within(policy).getByText('Concurrency').nextElementSibling?.textContent).toBe('2');
    });

    it('shows required justification only for the governed write-capable profile', async () => {
        const user = userEvent.setup();
        render(<ProfileControlHarness />);

        await user.selectOptions(
            screen.getByRole('combobox', { name: 'Credential Profile' }),
            'read-write'
        );

        const justification = screen.getByRole('textbox', { name: 'Justification' });
        expect(justification).toBeRequired();
        expect(justification).toHaveAttribute('id', 'tab-query-justification');
        await user.type(justification, 'TOPS-123 approved change');
        expect(justification).toHaveValue('TOPS-123 approved change');

        await user.click(screen.getByRole('button', { name: 'Show effective policy' }));
        const policy = screen.getByLabelText('Effective credential profile policy');
        expect(within(policy).getByText('Write-capable')).toBeInTheDocument();
        expect(
            within(policy).getByText('Justification required for every run, including SELECT.')
        ).toBeInTheDocument();
        expect(within(policy).getByText('Rows').nextElementSibling?.textContent).toBe('500');

        await user.selectOptions(
            screen.getByRole('combobox', { name: 'Credential Profile' }),
            'read-only'
        );
        expect(screen.queryByRole('textbox', { name: 'Justification' })).not.toBeInTheDocument();
    });

    it('formats unlimited sentinels without exposing raw integer values', async () => {
        const user = userEvent.setup();
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
                queryJustification=""
                canOverride={false}
                disabled={false}
                onProfileChange={() => undefined}
                onJustificationChange={() => undefined}
            />
        );

        await user.click(screen.getByRole('button', { name: 'Show effective policy' }));
        expect(screen.getAllByText('Unlimited')).toHaveLength(3);
        expect(screen.queryByText('2147483647')).not.toBeInTheDocument();
    });

    it('disables governed controls while a query is executing', async () => {
        render(<ProfileControlHarness disabled />);

        expect(screen.getByRole('combobox', { name: 'Credential Profile' })).toBeDisabled();
    });

    it('detects a blank justification only for a governed write-capable policy', () => {
        const readOnlyPolicy = datasource.credentialProfilePolicies![0];
        const writePolicy = datasource.credentialProfilePolicies![1];

        expect(isMissingRequiredQueryJustification(readOnlyPolicy, '')).toBe(false);
        expect(isMissingRequiredQueryJustification(writePolicy, '   ')).toBe(true);
        expect(isMissingRequiredQueryJustification(writePolicy, 'TOPS-123')).toBe(false);
    });

    it('shows policy disclosure without enabling non-admin profile switching', () => {
        render(
            <CredentialProfilePolicyControl
                datasource={datasource}
                requestedCredentialProfile=""
                queryJustification=""
                canOverride={false}
                disabled={false}
                onProfileChange={() => undefined}
                onJustificationChange={() => undefined}
            />
        );

        expect(
            screen.queryByRole('combobox', { name: 'Credential Profile' })
        ).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Show effective policy' })).toBeInTheDocument();
    });
});
