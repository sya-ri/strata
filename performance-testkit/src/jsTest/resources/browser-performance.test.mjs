import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { collectBrowserPerformance, measureBrowserMatrix } from '../../jsMain/resources/browser-performance.mjs';
import { compareBrowserPerformance } from '../../jsMain/resources/browser-performance-reports.mjs';

const conditions = { viewport: { width: 640, height: 480 }, warmup: 30, samples: 60 };
const scenarios = ['native', 'minecraft'].map(id => ({ id, url: `http://fixture/${id}`, targetUrl: 'http://fixture/application.js', phases: ['Initial', 'Idle'] }));
const distribution = { samples: 60, p50_ns: 1, p95_ns: 2, p99_ns: 3, total_ns: 120, max_ns: 3 };

function engine(name, { fail = false, onOperation = async () => {}, inventory, targetBytes = 'synthetic application', versionDrift = false, sample = distribution, workCounts } = {}) {
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
                            const observed = typeof sample === 'function' ? sample(launches.length) : sample;
                            return {
                                operation_wall: observed,
                                action_to_animation_frame: observed,
                                cpu_ns: null, allocated_bytes: null,
                                ...(workCounts === undefined ? {} : { work_counts: workCounts, work_scope: 'synthetic untimed work', operation_scope: 'synthetic operation' }),
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

/** Produces synthetic parser inputs through the actual driver; these are never browser performance evidence. */
async function comparisonFixture(t) {
    const paths = await artifacts(t);
    const directory = dirname(paths.outputPath);
    const candidateTargetPath = join(directory, 'candidate.js');
    const inputPath = join(directory, 'fixture.txt');
    await writeFile(candidateTargetPath, 'synthetic candidate');
    await writeFile(inputPath, 'unchanged synthetic fixture');
    const inputPaths = { fixture: inputPath };
    const engines = ['chromium', 'firefox'];
    const baselinePath = paths.outputPath;
    const candidatePath = join(directory, 'candidate.json');
    const zero = Object.fromEntries(Object.keys(distribution).map(name => [name, name === 'samples' ? 60 : 0]));
    await collectBrowserPerformance({ ...paths, inputPaths, engines: engines.map(name => engine(name, { sample: zero, workCounts: { setters: 100 } })), scenarios, conditions });
    const varied = invocation => ({ ...distribution, p50_ns: [3, 1, 2][invocation - 1], p95_ns: 4, p99_ns: 5, max_ns: 5 });
    await collectBrowserPerformance({ ...paths, targetPath: candidateTargetPath, outputPath: candidatePath, inputPaths, engines: engines.map(name => engine(name, { targetBytes: 'synthetic candidate', sample: varied, workCounts: { setters: 1 } })), scenarios, conditions });
    return {
        baselinePath, candidatePath, collectorPath: paths.collectorPath,
        driverPath: fileURLToPath(new URL('../../jsMain/resources/browser-performance.mjs', import.meta.url)),
        baselineTargetPath: paths.targetPath, candidateTargetPath, inputPaths, engines, scenarios, conditions,
        outputPath: join(directory, 'comparison.json'),
    };
}

test('browser comparison revalidates actual bytes and preserves complete independent rows with unavailable ratios', async t => {
    const request = await comparisonFixture(t);
    const report = await compareBrowserPerformance(request);
    assert.deepEqual(JSON.parse(await readFile(request.outputPath, 'utf8')), report);
    assert.equal(report.rows.length, 8);
    assert.equal(Object.keys(report.before.invocations).length, 6);
    assert.equal(Object.keys(report.after.invocations).length, 6);
    const row = report.rows[0];
    assert.deepEqual(row.operation_wall.p50_ns.baseline_runs, [0, 0, 0]);
    assert.deepEqual(row.operation_wall.p50_ns.candidate_runs, [3, 1, 2]);
    assert.equal(row.operation_wall.p50_ns.candidate, 2);
    assert.equal(row.operation_wall.p50_ns.delta, 2);
    assert.equal(row.operation_wall.p50_ns.candidate_to_baseline, null);
    assert.equal(row.work_counts.setters.delta, -99);
    assert.equal(row.work_counts.setters.candidate_to_baseline, 0.01);
    assert.equal(row.cpu_ns, null);
    assert.equal(row.allocated_bytes, null);
    await assert.rejects(compareBrowserPerformance(request), /already exists/);
    assert.deepEqual(JSON.parse(await readFile(request.outputPath, 'utf8')), report);
});

test('browser comparison rejects incomplete, reused, changed or invalid raw evidence without output', async t => {
    const request = await comparisonFixture(t);
    const original = JSON.parse(await readFile(request.candidatePath, 'utf8'));
    const baseline = JSON.parse(await readFile(request.baselinePath, 'utf8'));
    const cases = {
        missing: report => report.intervals.pop(),
        duplicate: report => { report.intervals[1] = report.intervals[0]; },
        unexpected: report => { report.intervals[0].scenario = 'unregistered'; },
        repetition: report => { report.intervals[0].repetition = 3; },
        invocation: report => { report.intervals[0].run_id = baseline.intervals[0].run_id; report.intervals.filter(row => row.engine === report.intervals[0].engine && row.repetition === 0).forEach(row => { row.run_id = report.intervals[0].run_id; }); },
        withinInvocation: report => { report.intervals[0].run_id = report.intervals.at(-1).run_id; },
        version: report => { report.intervals.filter(row => row.engine === 'chromium').forEach(row => { row.browser_version = 'changed synthetic browser'; }); },
        versionDrift: report => { report.intervals[0].browser_version = 'changed once'; },
        conditions: report => { report.intervals[0].conditions.warmup = 0; },
        collector: report => { report.intervals[0].collector_identity.artifact_sha256 = 'wrong'; },
        driver: report => { report.intervals[0].collector_identity.driver_sha256 = 'wrong'; },
        target: report => { report.intervals[0].target_identity = 'wrong'; },
        input: report => { report.intervals[0].input_identities.fixture = 'wrong'; },
        samples: report => { report.intervals[0].operation_wall.samples = 59; },
        quantiles: report => { report.intervals[0].operation_wall.p99_ns = 0; },
        invalidNumber: report => { report.intervals[0].operation_wall.total_ns = -1; },
        cpu: report => { report.intervals[0].cpu_ns = 0; },
        allocation: report => { delete report.intervals[0].allocated_bytes; },
        work: report => { report.intervals[0].work_counts.setters = 2; },
        workInventory: report => { report.intervals.forEach(row => { row.work_counts = { different: 1 }; }); },
        scope: report => { report.intervals[0].operation_scope = 'changed scope'; },
        allScopes: report => { report.intervals.forEach(row => { row.operation_scope = 'another operation'; }); },
        failed: report => { report.status = 'failed'; },
    };
    for (const [name, change] of Object.entries(cases)) {
        const report = structuredClone(original);
        change(report);
        await writeFile(request.candidatePath, JSON.stringify(report));
        const outputPath = `${request.outputPath}-${name}`;
        await assert.rejects(compareBrowserPerformance({ ...request, outputPath }), name);
        await assert.rejects(readFile(outputPath), { code: 'ENOENT' });
    }
});

test('browser comparison rejects changed actual files and declaration matrices', async t => {
    const request = await comparisonFixture(t);
    for (const [name, path] of Object.entries({ collector: request.collectorPath, driver: join(dirname(request.outputPath), 'other-driver.mjs'), target: request.candidateTargetPath, input: request.inputPaths.fixture })) {
        const previous = name === 'driver' ? null : await readFile(path);
        await writeFile(path, 'changed actual bytes');
        const outputPath = `${request.outputPath}-${name}`;
        await assert.rejects(compareBrowserPerformance({ ...request, outputPath, ...(name === 'driver' ? { driverPath: path } : {}) }));
        await assert.rejects(readFile(outputPath), { code: 'ENOENT' });
        if (previous !== null) await writeFile(path, previous);
    }
    for (const changed of [
        { inputPaths: {} },
        { engines: ['chromium'] },
        { scenarios: [{ ...scenarios[0], phases: ['Initial'] }] },
        { conditions: { ...conditions, samples: 1 } },
    ]) await assert.rejects(compareBrowserPerformance({ ...request, ...changed }));
    await assert.rejects(readFile(request.outputPath), { code: 'ENOENT' });
});
