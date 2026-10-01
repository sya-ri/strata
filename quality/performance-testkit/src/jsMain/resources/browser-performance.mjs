import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

/** Standard Playwright orchestration; applications expose only real fixture operations. */
export async function measureBrowserMatrix({ engines, scenarios, conditions, collectorIdentity, targetIdentity, repetitions = 3 }) {
    assert.equal(repetitions, 3, 'Independent performance evidence requires three repetitions');
    assert.ok(scenarios.length && new Set(scenarios.map(item => item.id)).size === scenarios.length);
    const intervals = [];
    for (const engine of engines) {
        const browser = await engine.launch();
        try {
            for (let repetition = 0; repetition < repetitions; repetition += 1) {
                for (const scenario of scenarios) {
                    const page = await browser.newPage({ viewport: conditions.viewport });
                    const errors = [];
                    page.on('pageerror', error => errors.push(error.message));
                    try {
                        await page.goto(scenario.url);
                        await page.waitForFunction(() => typeof window.strataMeasurePerformance === 'function');
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
                            intervals.push({ run_id: randomUUID(), scenario: scenario.id, phase, repetition, engine: engine.name(), browser_version: browser.version(), conditions, collector_identity: collectorIdentity, target_identity: targetIdentity, ...evidence });
                            console.log(`Measured ${engine.name()} ${scenario.id}/${phase}, repetition ${repetition + 1}`);
                        }
                    } finally { await page.close(); }
                }
            }
        } finally { await browser.close(); }
    }
    assert.equal(intervals.length, engines.length * repetitions * scenarios.reduce((sum, item) => sum + item.phases.length, 0));
    return intervals;
}
