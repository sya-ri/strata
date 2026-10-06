import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, stat, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Captures the supplied linked artifact, loaded driver source and complete target bundle outside sampling. */
async function captureArtifacts(collectorPath, targetPath, inputPaths) {
    return {
        collectorIdentity: {
            artifact_sha256: await fileHash(collectorPath),
            driver_sha256: await fileHash(fileURLToPath(import.meta.url)),
        },
        targetIdentity: await fileHash(targetPath),
        inputIdentities: Object.fromEntries(await Promise.all(Object.entries(inputPaths).map(async ([name, path]) => [name, await fileHash(path)]))),
    };
}

async function fileHash(path) {
    assert.ok((await stat(path)).isFile(), 'Performance input must be a regular file');
    const hash = createHash('sha256');
    for await (const chunk of createReadStream(path)) hash.update(chunk);
    return hash.digest('hex');
}

/** Owns provenance and immutable report output as well as the complete browser collection matrix. */
export async function collectBrowserPerformance({ collectorPath, targetPath, outputPath, inputPaths = {}, ...matrix }) {
    const existing = await stat(outputPath).catch(error => {
        if (error.code === 'ENOENT') return null;
        throw error;
    });
    assert.equal(existing, null, 'Performance evidence path already exists');
    const paths = { ...inputPaths };
    assert.ok(Object.keys(paths).every(name => name.trim().length));
    const identity = await captureArtifacts(collectorPath, targetPath, paths);
    assert.ok(matrix.scenarios.every(scenario => typeof scenario.targetUrl === 'string' && scenario.targetUrl.length), 'Register the actual application script URL for each browser fixture');
    const intervals = await measureBrowserMatrix({ ...matrix, ...identity });
    assert.deepEqual(await captureArtifacts(collectorPath, targetPath, paths), identity, 'Performance input changed during collection');
    const report = { status: 'passed', scope: 'Synchronous operation wall time and action-to-animation-frame latency; CPU/allocation/GPU completion unavailable', intervals };
    await mkdir(dirname(outputPath), { recursive: true });
    await writeFile(outputPath, JSON.stringify(report, null, 2), { flag: 'wx' });
    return report;
}

/** Standard Playwright orchestration; applications expose only real fixture operations. */
export async function measureBrowserMatrix({ engines, scenarios, conditions, collectorIdentity, targetIdentity, inputIdentities, repetitions = 3 }) {
    assert.equal(repetitions, 3, 'Independent performance evidence requires three repetitions');
    assert.equal(conditions.warmup, 30, 'The browser collector uses its default warm-up');
    assert.equal(conditions.samples, 60, 'The browser collector uses its default sample count');
    assert.ok(scenarios.length && new Set(scenarios.map(item => item.id)).size === scenarios.length);
    assert.ok(engines.length && new Set(engines.map(engine => engine.name())).size === engines.length);
    for (const scenario of scenarios) {
        assert.ok(scenario.phases.length && new Set(scenario.phases).size === scenario.phases.length);
    }
    const intervals = [];
    for (const engine of engines) {
        const engineName = engine.name();
        let expectedVersion;
        for (let repetition = 0; repetition < repetitions; repetition += 1) {
            const runId = randomUUID();
            const browser = await engine.launch();
            try {
                const version = browser.version();
                if (expectedVersion === undefined) expectedVersion = version;
                assert.equal(version, expectedVersion, 'Browser version changed between independent invocations');
                for (const scenario of scenarios) {
                    const page = await browser.newPage({ viewport: conditions.viewport });
                    const errors = [];
                    page.on('pageerror', error => errors.push(error.message));
                    try {
                        const target = targetIdentity === undefined ? Promise.resolve() : page.waitForResponse(response => response.url() === scenario.targetUrl).then(async response => {
                            assert.ok(response.ok(), 'Application script loading failed');
                            assert.equal(createHash('sha256').update(await response.body()).digest('hex'), targetIdentity, 'The browser loaded another application bundle');
                        });
                        await Promise.all([target, page.goto(scenario.url)]);
                        await page.waitForFunction(() => typeof window.strataMeasurePerformance === 'function');
                        if (scenario.inventory !== undefined) {
                            assert.deepEqual(await page.evaluate(() => JSON.parse(window.strataPerformanceInventory())), scenario.inventory, 'Loaded fixture inventory differs from the registered inputs');
                        }
                        assert.equal(await page.evaluate(() => window.strataVerifyPerformanceCollector()), true, 'Loaded collector failure/cleanup contract');
                        for (const phase of scenario.phases) {
                            const evidence = await page.evaluate(async token => JSON.parse(await window.strataMeasurePerformance(token)), phase);
                            for (const distribution of [evidence.operation_wall, evidence.action_to_animation_frame]) {
                                assert.equal(distribution.samples, 60);
                                for (const field of ['p50_ns', 'p95_ns', 'p99_ns', 'total_ns', 'max_ns']) {
                                    assert.ok(Number.isSafeInteger(distribution[field]) && 0 <= distribution[field], field);
                                }
                                assert.ok(distribution.p50_ns <= distribution.p95_ns && distribution.p95_ns <= distribution.p99_ns && distribution.p99_ns <= distribution.max_ns);
                            }
                            assert.deepEqual(errors, [], 'A failed browser fixture cannot produce successful evidence');
                            intervals.push({ ...evidence, run_id: runId, scenario: scenario.id, phase, repetition, engine: engineName, browser_version: version, conditions, collector_identity: collectorIdentity, target_identity: targetIdentity, input_identities: inputIdentities });
                            console.log(`Measured ${engineName} ${scenario.id}/${phase}, repetition ${repetition + 1}`);
                        }
                    } finally { await page.close(); }
                }
            } finally { await browser.close(); }
        }
    }
    assert.equal(intervals.length, engines.length * repetitions * scenarios.reduce((sum, item) => sum + item.phases.length, 0));
    return intervals;
}
