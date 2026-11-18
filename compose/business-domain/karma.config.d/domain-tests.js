// Exact decimal and binary64 boundary vectors use the same budget as the Node targets.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 120000 });
config.pingTimeout = 120000;
config.browserDisconnectTimeout = 120000;
config.browserNoActivityTimeout = 120000;
config.failOnEmptyTestSuite = true;
