import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { collectBrowserPerformance, measureBrowserMatrix } from '../../jsMain/resources/browser-performance.mjs';

const conditions = { viewport: { width: 640, height: 480 }, warmup: 30, samples: 60 };
const scenarios = ['native', 'minecraft'].map(id => ({ id, url: `http://fixture/${id}`, targetUrl: 'http://fixture/application.js', phases: ['Initial', 'Idle'] }));
const distribution = { samples: 60, p50_ns: 1, p95_ns: 2, p99_ns: 3, total_ns: 120, max_ns: 3 };

function engine(name, { fail = false, onOperation = async () => {}, inventory, targetBytes = 'synthetic application', versionDrift = false } = {}) {
    const launches = [];
    return {
        launches,
        name: () => name,
        async launch() {
            const lifetime = { closed: false, pages: [] };
            launches.push(lifetime);
            return {
                version: () => versionDrift ? `synthetic-browser-${launches.length}` : 'synthetic-browser',
                async newPage() {
                    const page = { closed: false };
                    lifetime.pages.push(page);
                    return {
                        on() {},
                        async goto() {},
                        async waitForResponse(predicate) {
                            const response = { url: () => 'http://fixture/application.js', ok: () => true, body: async () => new TextEncoder().encode(targetBytes) };
                            assert.ok(predicate(response));
                            return response;
                        },
                        async waitForFunction() {},
                        async evaluate(callback, phase) {
                            if (callback.toString().includes('strataPerformanceInventory')) return inventory;
                            if (phase === undefined) return true;
                            await onOperation();
                            if (fail) throw new Error('fixture operation failed');
                            return {
                                operation_wall: distribution,
                                action_to_animation_frame: distribution,
                                run_id: 'forged', engine: 'forged', repetition: 99,
                            };
                        },
                        async close() { page.closed = true; },
                    };
                },
                async close() { lifetime.closed = true; },
            };
        },
    };
}

test('each independent invocation launches and closes its own browser and owns one identity across phases', async () => {
    const engines = ['chromium', 'firefox', 'webkit'].map(name => engine(name));
    const intervals = await measureBrowserMatrix({ engines, scenarios, conditions });
    assert.equal(intervals.length, 36);
    assert.equal(new Set(intervals.map(row => row.run_id)).size, 9);
    for (const browser of engines) {
        assert.equal(browser.launches.length, 3);
        assert.ok(browser.launches.every(lifetime => lifetime.closed && lifetime.pages.length === 2 && lifetime.pages.every(page => page.closed)));
        for (let repetition = 0; repetition < 3; repetition += 1) {
            const rows = intervals.filter(row => row.engine === browser.name() && row.repetition === repetition);
            assert.equal(rows.length, 4);
            assert.equal(new Set(rows.map(row => row.run_id)).size, 1);
            assert.ok(rows.every(row => /^[0-9a-f-]{36}$/.test(row.run_id)));
        }
    }
});

async function artifacts(t) {
    const directory = await mkdtemp(join(tmpdir(), 'strata-browser-driver-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const collectorPath = join(directory, 'collector.js');
    const targetPath = join(directory, 'application.js');
    const outputPath = join(directory, 'evidence.json');
    await writeFile(collectorPath, 'synthetic collector');
    await writeFile(targetPath, 'synthetic application');
    return { collectorPath, targetPath, outputPath };
}

test('collection owns artifact identities and preserves the complete immutable report', async t => {
    const paths = await artifacts(t);
    const report = await collectBrowserPerformance({
        ...paths, engines: [engine('chromium')], scenarios, conditions,
        collectorIdentity: 'forged', targetIdentity: 'forged',
    });
    assert.equal(report.status, 'passed');
    assert.equal(report.intervals.length, 12);
    assert.deepEqual(JSON.parse(await readFile(paths.outputPath, 'utf8')), report);
    for (const row of report.intervals) {
        assert.equal(row.collector_identity.artifact_sha256, createHash('sha256').update('synthetic collector').digest('hex'));
        assert.match(row.collector_identity.driver_sha256, /^[0-9a-f]{64}$/);
        assert.equal(row.target_identity, createHash('sha256').update('synthetic application').digest('hex'));
    }
});

test('existing evidence rejects before launching and remains unchanged', async t => {
    const paths = await artifacts(t);
    await writeFile(paths.outputPath, 'preserved evidence');
    const browser = engine('chromium');
    await assert.rejects(collectBrowserPerformance({ ...paths, engines: [browser], scenarios, conditions }), /already exists/);
    assert.equal(browser.launches.length, 0);
    assert.equal(await readFile(paths.outputPath, 'utf8'), 'preserved evidence');
});

test('changing either input rejects without writing successful evidence', async t => {
    for (const key of ['collectorPath', 'targetPath']) {
        const paths = await artifacts(t);
        const browser = engine('chromium', { onOperation: () => writeFile(paths[key], 'changed input') });
        await assert.rejects(collectBrowserPerformance({ ...paths, engines: [browser], scenarios, conditions }), /input changed/);
        await assert.rejects(readFile(paths.outputPath), { code: 'ENOENT' });
        assert.ok(browser.launches.every(lifetime => lifetime.closed));
    }
});

test('operation failure cannot leave a successful report', async t => {
    const paths = await artifacts(t);
    await assert.rejects(collectBrowserPerformance({ ...paths, engines: [engine('chromium', { fail: true })], scenarios, conditions }), /operation failed/);
    await assert.rejects(readFile(paths.outputPath), { code: 'ENOENT' });
});

test('an output created during collection cannot be overwritten', async t => {
    const paths = await artifacts(t);
    const browser = engine('chromium', { onOperation: () => writeFile(paths.outputPath, 'concurrent evidence') });
    await assert.rejects(collectBrowserPerformance({ ...paths, engines: [browser], scenarios, conditions }), { code: 'EEXIST' });
    assert.equal(await readFile(paths.outputPath, 'utf8'), 'concurrent evidence');
});

test('declared input artifacts belong to the kit and cannot change during collection', async t => {
    const paths = await artifacts(t);
    const manifest = join(paths.outputPath, '..', 'inventory.json');
    await writeFile(manifest, 'registered fixture inputs');
    const inputPaths = { fixtures: manifest };
    const output = await collectBrowserPerformance({ ...paths, inputPaths, engines: [engine('chromium')], scenarios, conditions });
    const hash = createHash('sha256').update('registered fixture inputs').digest('hex');
    assert.ok(output.intervals.every(row => row.input_identities.fixtures === hash));
    const changedOutput = join(paths.outputPath, '..', 'changed.json');
    await assert.rejects(collectBrowserPerformance({
        ...paths, outputPath: changedOutput, inputPaths,
        engines: [engine('chromium', { onOperation: () => writeFile(manifest, 'changed fixtures') })], scenarios, conditions,
    }), /input changed/);
    await assert.rejects(readFile(changedOutput), { code: 'ENOENT' });
});

test('the loaded fixture inventory must match the declared inputs before sampling', async () => {
    const inventory = { supported: ['Row'], unavailable: ['Canvas'], phases: ['Initial', 'Idle'] };
    const registered = scenarios.map(scenario => ({ ...scenario, inventory }));
    const accepted = await measureBrowserMatrix({ engines: [engine('chromium', { inventory })], scenarios: registered, conditions });
    assert.equal(accepted.length, 12);
    const browser = engine('chromium', { inventory: { ...inventory, supported: [] } });
    await assert.rejects(measureBrowserMatrix({ engines: [browser], scenarios: registered, conditions }), /inventory differs/);
    assert.equal(browser.launches.length, 1);
    assert.ok(browser.launches[0].closed && browser.launches[0].pages.every(page => page.closed));
});

test('the response actually loaded by the browser must match the captured target bundle', async t => {
    const paths = await artifacts(t);
    const browser = engine('chromium', { targetBytes: 'another served bundle' });
    await assert.rejects(collectBrowserPerformance({ ...paths, engines: [browser], scenarios, conditions }), /another application bundle/);
    await assert.rejects(readFile(paths.outputPath), { code: 'ENOENT' });
    assert.equal(browser.launches.length, 1);
    assert.ok(browser.launches[0].closed && browser.launches[0].pages.every(page => page.closed));
});

test('browser version drift rejects the group and closes the changed invocation', async t => {
    const paths = await artifacts(t);
    const browser = engine('chromium', { versionDrift: true });
    await assert.rejects(collectBrowserPerformance({ ...paths, engines: [browser], scenarios, conditions }), /version changed/);
    await assert.rejects(readFile(paths.outputPath), { code: 'ENOENT' });
    assert.equal(browser.launches.length, 2);
    assert.ok(browser.launches.every(lifetime => lifetime.closed));
    assert.equal(browser.launches[1].pages.length, 0);
});

test('operation failure closes the current page and browser without starting another invocation', async () => {
    const browser = engine('chromium', { fail: true });
    await assert.rejects(measureBrowserMatrix({ engines: [browser], scenarios, conditions }), /fixture operation failed/);
    assert.equal(browser.launches.length, 1);
    assert.ok(browser.launches[0].closed && browser.launches[0].pages.every(page => page.closed));
});

test('changed default conditions and duplicate registrations reject before launching a browser', async () => {
    const browser = engine('chromium');
    for (const request of [
        { conditions: { ...conditions, samples: 1 } },
        { conditions: { ...conditions, warmup: 0 } },
        { repetitions: 1 },
        { scenarios: [scenarios[0], scenarios[0]] },
        { scenarios: [{ ...scenarios[0], phases: ['Idle', 'Idle'] }] },
        { engines: [browser, browser] },
    ]) {
        await assert.rejects(measureBrowserMatrix({ engines: [browser], scenarios, conditions, ...request }));
    }
    assert.equal(browser.launches.length, 0);
});
