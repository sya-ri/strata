// Use the same bounded test timeout for Node and browser correctness suites.
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 30000 });
