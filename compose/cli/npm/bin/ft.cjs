#!/usr/bin/env node
const { version } = require('../package.json');
process.env.FT_PACKAGE_VERSION = version;
import('../lib/ledger-cli.mjs').catch((error) => {
    process.stderr.write(`ft: launch failed (${error.code || error.name})\n`);
    process.exitCode = 1;
});
