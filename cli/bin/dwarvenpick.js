#!/usr/bin/env node
import { main } from '../src/main.js';

main().catch((error) => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = error.exitCode || 1;
});
