// Serve the real application resources to browser integration tests, including nested Workers.
config.failOnEmptyTestSuite = true;
const ledgerResourceRoot = require('path').resolve(config.basePath, '../../../../webApp/src/webMain/resources');
const ledgerFaultWorker = require('path').resolve(config.basePath, '../../../../shared/src/webTest/resources/ledger-worker-fault.js');
config.files.push({ pattern: ledgerFaultWorker, included: false, watched: false });
config.proxies['/ledger-storage/ledger-worker-fault.js'] = '/absolute' + ledgerFaultWorker;
const locatorProbe = require('path').resolve(config.basePath, '../../../../shared/src/webTest/resources/ledger-session-locator-probe.mjs');
config.files.push({ pattern: locatorProbe, included: false, watched: false });
config.proxies['/ledger-storage/ledger-session-locator-probe.mjs'] = '/absolute' + locatorProbe;
const leaseProbe = require('path').resolve(config.basePath, '../../../../shared/src/webTest/resources/ledger-lease-probe.mjs');
config.files.push({ pattern: leaseProbe, included: false, watched: false });
config.proxies['/ledger-storage/ledger-lease-probe.mjs'] = '/absolute' + leaseProbe;
config.files.push({ pattern: ledgerResourceRoot + '/ledger-storage/**/*', included: false, watched: false });
config.files.push({ pattern: ledgerResourceRoot + '/ledger-key-vault.mjs', included: false, watched: false });
config.proxies['/ledger-storage/'] = '/absolute' + ledgerResourceRoot + '/ledger-storage/';
config.proxies['/ledger-key-vault.mjs'] = '/absolute' + ledgerResourceRoot + '/ledger-key-vault.mjs';
config.webpack.externals = [].concat(config.webpack.externals || [], {
  '/ledger-storage/connection.mjs': 'import /ledger-storage/connection.mjs',
  '/ledger-storage/session-locator.mjs': 'import /ledger-storage/session-locator.mjs',
  '/ledger-storage/ledger-session-locator-probe.mjs': 'import /ledger-storage/ledger-session-locator-probe.mjs',
  '/ledger-storage/ledger-lease-probe.mjs': 'import /ledger-storage/ledger-lease-probe.mjs',
  '/ledger-key-vault.mjs': 'import /ledger-key-vault.mjs',
});
config.webpack.output = config.webpack.output || {};
config.webpack.output.environment = Object.assign({}, config.webpack.output.environment, { dynamicImport: true });
config.customHeaders = (config.customHeaders || []).concat([
  { match: '.*', name: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
  { match: '.*', name: 'Cross-Origin-Embedder-Policy', value: 'require-corp' },
]);
