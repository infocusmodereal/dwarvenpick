import path from 'node:path';
import os from 'node:os';
import { defineConfig } from '@playwright/test';

const playwrightOutputDir = path.resolve(
    process.env.BROWSER_SMOKE_PLAYWRIGHT_OUTPUT_DIR ||
        path.join(os.tmpdir(), 'dwarvenpick-browser-smoke', String(process.pid))
);
export default defineConfig({
    testDir: './e2e',
    timeout: 120_000,
    expect: {
        timeout: 20_000
    },
    fullyParallel: false,
    workers: 1,
    retries: 0,
    reporter: 'line',
    outputDir: playwrightOutputDir,
    use: {
        baseURL: process.env.BASE_URL || 'http://localhost:3000',
        browserName: 'chromium',
        headless: true,
        acceptDownloads: true,
        trace: 'off',
        screenshot: 'off',
        video: 'off'
    }
});
