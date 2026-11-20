// Serve the same versioned compute Worker bundle that the Web app ships to browser integration tests.
const fs = require('fs');
const path = require('path');
const cashImportWorkerRoot = process.env.FT_CASH_IMPORT_WORKER_ROOT;
if (!cashImportWorkerRoot || !fs.existsSync(path.join(cashImportWorkerRoot, 'client.mjs'))) {
  throw new Error('Cash import Worker bundle is missing; run :webApp:stageCashImportWorker first');
}
config.files.push({ pattern: cashImportWorkerRoot + '/**/*', included: false, watched: false });
config.proxies['/cash-import-worker/'] = '/absolute' + cashImportWorkerRoot + '/';
