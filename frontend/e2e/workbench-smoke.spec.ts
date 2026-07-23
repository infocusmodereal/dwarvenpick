import { mkdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { expect, test, type Page } from '@playwright/test';

import { resolveBrowserSmokeConfig } from '../../scripts/browser/config.mjs';

type CheckEvidence = {
    name: string;
    status: 'passed' | 'failed';
    durationMs: number;
};

type ReleaseIdentity = Record<string, string>;

const RELEASE_IDENTITY_FIELDS = [
    'version',
    'sourceRef',
    'sourceSha',
    'imageTag',
    'buildTag'
] as const;

const sanitizeReleaseIdentity = (payload: unknown): ReleaseIdentity => {
    if (!payload || typeof payload !== 'object') {
        return {};
    }

    return RELEASE_IDENTITY_FIELDS.reduce<ReleaseIdentity>((identity, field) => {
        const value = (payload as Record<string, unknown>)[field];
        if (typeof value === 'string' && value.trim()) {
            identity[field] = value.trim();
        }
        return identity;
    }, {});
};

const readResultRowCount = async (page: Page): Promise<number> => {
    const rowStat = page
        .locator('.result-stats-grid .result-stat')
        .filter({ has: page.getByText('Rows', { exact: true }) })
        .locator('strong');
    const rawValue = (await rowStat.textContent())?.replaceAll(',', '').trim() || '';
    const rowCount = Number.parseInt(rawValue, 10);
    expect(Number.isFinite(rowCount)).toBe(true);
    return rowCount;
};

test('governed workbench browser smoke', async ({ page }) => {
    const config = resolveBrowserSmokeConfig();
    const checks: CheckEvidence[] = [];
    const startedAt = new Date().toISOString();
    let releaseIdentity: ReleaseIdentity = {};
    let observedRows = 0;
    let csvBytes = 0;
    let outcome: 'passed' | 'failed' = 'failed';

    const check = async (name: string, action: () => Promise<void>) => {
        const started = Date.now();
        try {
            await test.step(name, action);
            checks.push({ name, status: 'passed', durationMs: Date.now() - started });
        } catch (error) {
            checks.push({ name, status: 'failed', durationMs: Date.now() - started });
            throw error;
        }
    };

    try {
        await check('login', async () => {
            const authMethodsPromise = page.waitForResponse((response) => {
                const url = new URL(response.url());
                return (
                    url.pathname === '/api/auth/methods' && response.request().method() === 'GET'
                );
            });
            await page.goto('/');
            const authMethodsResponse = await authMethodsPromise;
            expect(authMethodsResponse.ok()).toBe(true);
            const authMethods = (await authMethodsResponse.json()) as { methods?: string[] };
            const passwordMethods = authMethods.methods?.filter(
                (method) => method === 'local' || method === 'ldap'
            );
            if (config.mode === 'local') {
                expect(passwordMethods).toContain('local');
            } else {
                expect(passwordMethods?.length).toBeGreaterThan(0);
            }
            await page.waitForLoadState('networkidle');
            await page.getByLabel('Username').fill(config.username);
            await page.getByLabel('Password').fill(config.password);
            const signIn = page.getByRole('button', { name: 'Sign In' });
            await expect(signIn).toBeEnabled();
            await signIn.click();
            await expect(page).toHaveURL(/\/workspace$/);
            await expect(page.getByLabel('Workbench')).toBeVisible();
        });

        await check('release identity', async () => {
            const response = await page.evaluate(async () => {
                const result = await window.fetch('/api/version', {
                    method: 'GET',
                    credentials: 'include'
                });
                return {
                    ok: result.ok,
                    payload: result.ok ? await result.json() : null
                };
            });
            expect(response.ok).toBe(true);
            releaseIdentity = sanitizeReleaseIdentity(response.payload);
            expect(releaseIdentity.version).toBeTruthy();
        });

        await check('connection selection', async () => {
            const connection = page.getByLabel('Active tab connection');
            await expect(connection).toBeEnabled();
            page.once('dialog', async (dialog) => {
                expect(dialog.type()).toBe('confirm');
                await dialog.accept();
            });
            const schemaResponsePromise = page.waitForResponse((response) => {
                const url = new URL(response.url());
                return (
                    url.pathname ===
                        `/api/datasources/${encodeURIComponent(config.datasource)}/schema-browser` &&
                    response.request().method() === 'GET'
                );
            });
            await connection.selectOption({ label: config.datasource });
            expect((await schemaResponsePromise).ok()).toBe(true);
            await expect(connection).toHaveValue(config.datasource);

            if (config.credentialProfile) {
                await page
                    .getByRole('button', { name: 'Show access and policy', exact: true })
                    .click();
                const profile = page.getByRole('combobox', {
                    name: 'Credential Profile',
                    exact: true
                });
                await expect(profile).toBeVisible();
                await profile.selectOption(config.credentialProfile);
                await expect(profile).toHaveValue(config.credentialProfile);
            }
        });

        await check('script options controls', async () => {
            await page.getByRole('button', { name: 'Options', exact: true }).click();
            const dialog = page.getByRole('dialog', { name: 'Script options' });
            await expect(dialog).toBeVisible();
            await expect(dialog.getByText('Script Options', { exact: true })).toBeVisible();
            await expect(dialog.getByText('Stop on error', { exact: true })).toBeVisible();
            const transactionMode = dialog.getByLabel('Transaction mode', { exact: true });
            await expect(transactionMode).toHaveValue('AUTOCOMMIT');
            await transactionMode.selectOption('TRANSACTION');
            await expect(transactionMode).toHaveValue('TRANSACTION');
            await page.getByRole('button', { name: 'Options', exact: true }).click();
            await expect(dialog).toBeHidden();
        });

        await check('safe query execution', async () => {
            const justification = page.getByLabel('Justification', { exact: true });
            if ((await justification.count()) > 0) {
                await expect(justification).toBeVisible();
                await justification.fill('Automated browser smoke validation');
            } else {
                await expect(justification).toHaveCount(0);
            }
            const editorInput = page.locator('.monaco-editor textarea.inputarea').first();
            await expect(editorInput).toBeVisible();
            await editorInput.focus();
            await expect(editorInput).toBeFocused();
            await page.keyboard.press('ControlOrMeta+A');
            await page.keyboard.insertText(config.query);
            const queryMarker = config.mode === 'local' ? 'order_item_id' : 'browser_smoke';
            await expect(page.locator('.monaco-editor .view-lines')).toContainText(queryMarker);

            const runQuery = page.getByRole('button', { name: 'Run', exact: true });
            await expect(runQuery).toBeEnabled();
            await expect(
                page.getByRole('button', { name: 'Format SQL', exact: true })
            ).toBeVisible();
            await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeVisible();
            await expect(page.getByText('Query Tools', { exact: true })).toBeVisible();
            await runQuery.click();
            await expect(page.locator('.result-stats-grid')).toContainText('SUCCEEDED');
            observedRows = await readResultRowCount(page);
            expect(observedRows).toBe(config.expectedRows);
        });

        await check('result paging and current-page sort', async () => {
            const results = page.locator('section.results');
            const pageSize = results.getByLabel('Rows per page');
            await pageSize.selectOption('10');

            const nextPage = results.getByRole('button', { name: 'Next Page' });
            const previousPage = results.getByRole('button', { name: 'Previous Page' });
            if (config.mode === 'local') {
                await expect(nextPage).toBeEnabled();
                await expect(previousPage).toBeDisabled();
                await nextPage.click();
                await expect(results.locator('td.result-row-index').first()).toHaveText('11');
                await expect(previousPage).toBeEnabled();
                await previousPage.click();
                await expect(results.locator('td.result-row-index').first()).toHaveText('1');
            } else {
                expect(observedRows).toBe(1);
                await expect(nextPage).toBeDisabled();
                await expect(previousPage).toBeDisabled();
            }

            const sortButton = results.getByRole('button', {
                name:
                    config.mode === 'local'
                        ? 'Sort current page by order_item_id'
                        : 'Sort current page by browser_smoke'
            });
            await sortButton.click();
            await expect(sortButton.locator('xpath=ancestor::th')).toHaveAttribute(
                'aria-sort',
                'ascending'
            );
        });

        await check('CSV export behavior', async () => {
            const results = page.locator('section.results');
            const exportButton = results.getByRole('button', { name: 'Export CSV' });

            if (config.expectedExport === 'denied') {
                const unexpectedDownload = page
                    .waitForEvent('download', { timeout: 1_000 })
                    .then(() => true)
                    .catch(() => false);
                await expect(exportButton).toBeEnabled();
                await exportButton.click();
                await page.getByRole('button', { name: 'Download CSV' }).click();
                await expect(page.getByRole('alert')).toBeVisible();
                expect(await unexpectedDownload).toBe(false);
                return;
            }

            await expect(exportButton).toBeEnabled();
            await exportButton.click();
            const downloadPromise = page.waitForEvent('download');
            await page.getByRole('button', { name: 'Download CSV' }).click();
            const download = await downloadPromise;
            const downloadPath = await download.path();
            expect(downloadPath).toBeTruthy();
            csvBytes = (await stat(downloadPath as string)).size;
            expect(csvBytes).toBeGreaterThan(0);
        });

        await check('query history refresh', async () => {
            await page.getByLabel('Query History').click();
            const history = page.locator('section.history-panel');
            await expect(history).toBeVisible();
            const refresh = history.getByRole('button', { name: 'Refresh history' });
            await expect(refresh).toBeEnabled();
            await refresh.click();
            await expect(history.locator('.query-history-table tbody tr').first()).toBeVisible();
            await expect(
                history
                    .locator('.query-history-table tbody')
                    .getByText('SUCCEEDED', { exact: true })
                    .first()
            ).toBeVisible();
        });

        await check('logout', async () => {
            await page.locator('.workspace-left-user-trigger').click();
            await page.getByRole('button', { name: 'Logout' }).click();
            await expect(page.getByRole('button', { name: 'Sign In' })).toBeVisible();
        });

        outcome = 'passed';
    } finally {
        await mkdir(config.reportDir, { recursive: true });
        await writeFile(
            path.join(config.reportDir, 'workbench-browser-smoke.json'),
            `${JSON.stringify(
                {
                    schemaVersion: 1,
                    scenario: 'governed-workbench',
                    mode: config.mode,
                    releaseIdentity,
                    startedAt,
                    completedAt: new Date().toISOString(),
                    outcome,
                    checks,
                    observations: {
                        resultRowCount: observedRows,
                        csvDownloaded: csvBytes > 0,
                        csvBytes
                    }
                },
                null,
                2
            )}\n`,
            { encoding: 'utf8', mode: 0o600 }
        );
    }
});
