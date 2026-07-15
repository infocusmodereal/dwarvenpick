import {
    resolveBrowserSmokeConfig,
    safeBrowserSmokeTargetSummary
} from './config.mjs';

const summary = safeBrowserSmokeTargetSummary(resolveBrowserSmokeConfig());
process.stdout.write(
    `Browser smoke target validated: mode=${summary.mode} origin=${summary.origin} ` +
        `datasource=${summary.datasource} export=${summary.expectedExport}\n`
);
