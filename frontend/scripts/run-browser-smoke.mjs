import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';

const require = createRequire(import.meta.url);
const outputDir =
    process.env.BROWSER_SMOKE_PLAYWRIGHT_OUTPUT_DIR ||
    (await mkdtemp(path.join(os.tmpdir(), 'dwarvenpick-browser-smoke.')));

const runPlaywright = () =>
    new Promise((resolve, reject) => {
        const child = spawn(
            process.execPath,
            [require.resolve('@playwright/test/cli'), 'test', '--config', 'playwright.config.ts'],
            {
                cwd: process.cwd(),
                env: {
                    ...process.env,
                    BROWSER_SMOKE_PLAYWRIGHT_OUTPUT_DIR: outputDir
                },
                stdio: 'inherit'
            }
        );
        child.once('error', reject);
        child.once('exit', (code, signal) => {
            if (signal === 'SIGINT') {
                resolve(130);
                return;
            }
            if (signal === 'SIGTERM') {
                resolve(143);
                return;
            }
            resolve(code ?? 1);
        });
    });

let exitCode = 1;
try {
    exitCode = await runPlaywright();
} finally {
    await rm(outputDir, { recursive: true, force: true });
}
process.exitCode = exitCode;
