import { describe, expect, it } from 'vitest';
import { runResourceSaveOnce } from '../workbench/resourceSaveGuard';

describe('resource save serialization', () => {
    it('skips overlapping writes to a tab and allows its next revision after completion', async () => {
        const pending = new Set<string>();
        let finish!: (value: boolean) => void;
        let revision = 1;
        const first = runResourceSaveOnce(pending, 'tab', async () => {
            const result = await new Promise<boolean>((resolve) => {
                finish = resolve;
            });
            revision = 2;
            return result;
        });
        expect(
            await runResourceSaveOnce(pending, 'tab', async () => {
                throw new Error('overlapping request');
            })
        ).toBe(false);
        expect(await runResourceSaveOnce(pending, 'other-tab', async () => true)).toBe(true);
        finish(true);
        expect(await first).toBe(true);
        expect(await runResourceSaveOnce(pending, 'tab', async () => revision === 2)).toBe(true);
    });

    it('releases the guard when a request fails', async () => {
        const pending = new Set<string>();
        await expect(
            runResourceSaveOnce(pending, 'tab', async () => {
                throw new Error('offline');
            })
        ).rejects.toThrow('offline');
        expect(pending.size).toBe(0);
    });
});
