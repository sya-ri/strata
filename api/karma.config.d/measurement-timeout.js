// Preserve complete finite measurement matrices and 1,000 retained cycles within a bounded deadline.
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });
// Align transport liveness and reconnect deadlines with the bounded exhaustive-test deadline.
config.pingTimeout = 60000;
config.browserNoActivityTimeout = 60000;
config.browserDisconnectTimeout = 60000;
