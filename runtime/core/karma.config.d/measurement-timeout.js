// Preserve complete finite measurement matrices and 1,000 retained cycles within a bounded deadline.
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });
