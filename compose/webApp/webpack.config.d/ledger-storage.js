// Keep the packaged same-origin lifecycle module as a native asynchronous import.
config.externals = [].concat(config.externals || [], {
  '/ledger-storage/connection.mjs': 'import /ledger-storage/connection.mjs',
  '/ledger-storage/session-locator.mjs': 'import /ledger-storage/session-locator.mjs',
});
config.output = config.output || {};
config.output.environment = Object.assign({}, config.output.environment, { dynamicImport: true });
