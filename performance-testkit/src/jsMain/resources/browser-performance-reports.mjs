import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const distributions = ['operation_wall', 'action_to_animation_frame'];
const measurements = ['p50_ns', 'p95_ns', 'p99_ns', 'total_ns', 'max_ns'];
const scopes = ['operation_scope', 'work_scope'];
const rowKey = row => JSON.stringify([row.engine, row.scenario, row.phase]);

/**
 * Revalidates the collector's existing raw format and writes one immutable, complete browser comparison.
 * Callers declare the unchanged engines, scenarios/phases, conditions and actual collector, bundle and input paths.
 * Each row retains three independent observations per side; unavailable CPU/allocation and zero-baseline ratios stay null.
 */
export async function compareBrowserPerformance({ baselinePath, candidatePath, collectorPath, driverPath, baselineTargetPath, candidateTargetPath, inputPaths = {}, engines, scenarios, conditions, outputPath }) {
    const existing = await stat(outputPath).catch(error => {
        if (error.code === 'ENOENT') return null;
        throw error;
    });
    assert.equal(existing, null, 'Browser comparison output already exists');
    assert.equal(conditions.warmup, 30);
    assert.equal(conditions.samples, 60);
    assert.ok(Number.isSafeInteger(conditions.viewport.width) && 0 < conditions.viewport.width && Number.isSafeInteger(conditions.viewport.height) && 0 < conditions.viewport.height);
    assert.ok(engines.length && engines.every(name => typeof name === 'string' && name.length) && new Set(engines).size === engines.length);
    assert.ok(scenarios.length && scenarios.every(item => typeof item.id === 'string' && item.id.length) && new Set(scenarios.map(item => item.id)).size === scenarios.length);
    for (const item of scenarios) assert.ok(item.phases.length && item.phases.every(phase => typeof phase === 'string' && phase.length) && new Set(item.phases).size === item.phases.length);
    const matrix = engines.flatMap(engine => scenarios.flatMap(item => item.phases.map(phase => ({ engine, scenario: item.id, phase }))));
    const files = { processor: fileURLToPath(import.meta.url), collector: collectorPath, driver: driverPath, baseline: baselinePath, candidate: candidatePath, baselineTarget: baselineTargetPath, candidateTarget: candidateTargetPath };
    const identities = await hashes(files);
    const inputs = await hashes(inputPaths);
    const collector = { artifact_sha256: identities.collector, driver_sha256: identities.driver };
    const before = validate(await document(baselinePath, identities.baseline), matrix, conditions, collector, identities.baselineTarget, inputs);
    const after = validate(await document(candidatePath, identities.candidate), matrix, conditions, collector, identities.candidateTarget, inputs);
    assert.equal(before.scope, after.scope, 'Changed measurement scope');
    assert.equal(new Set([...before.invocations.values(), ...after.invocations.values()]).size, engines.length * 6, 'Reused browser invocation across runtime variants');
    const rows = matrix.map(identity => {
        const old = before.rows.get(rowKey(identity));
        const next = after.rows.get(rowKey(identity));
        assert.equal(old[0].browser_version, next[0].browser_version, 'Changed browser version across variants');
        for (const field of scopes) assert.deepEqual(old[0][field], next[0][field], 'Changed fixture operation scope');
        assert.deepEqual(Object.keys(old[0].work_counts ?? {}).sort(), Object.keys(next[0].work_counts ?? {}).sort(), 'Changed native work metric inventory');
        const timings = Object.fromEntries(distributions.map(name => [name, Object.fromEntries(measurements.map(field => [field, comparison(old.map(row => row[name][field]), next.map(row => row[name][field]))]))]));
        const work = old[0].work_counts === undefined ? null : Object.fromEntries(Object.keys(old[0].work_counts).map(name => [name, comparison(old.map(row => row.work_counts[name]), next.map(row => row.work_counts[name]))]));
        return { ...identity, browser_version: old[0].browser_version, ...Object.fromEntries(scopes.map(field => [field, old[0][field] ?? null])), ...timings, work_counts: work, cpu_ns: null, allocated_bytes: null };
    });
    const report = {
        contract: 'strata-browser-comparison-v1', status: 'passed', scope: before.scope,
        processor_sha256: identities.processor, collector_identity: collector, input_identities: inputs, conditions,
        before: { raw_sha256: identities.baseline, target_identity: identities.baselineTarget, invocations: Object.fromEntries(before.invocations) },
        after: { raw_sha256: identities.candidate, target_identity: identities.candidateTarget, invocations: Object.fromEntries(after.invocations) },
        rows,
    };
    assert.deepEqual(await hashes(files), identities, 'Browser comparison artifact changed during processing');
    assert.deepEqual(await hashes(inputPaths), inputs, 'Browser comparison input changed during processing');
    await mkdir(dirname(outputPath), { recursive: true });
    await writeFile(outputPath, JSON.stringify(report, null, 2), { flag: 'wx' });
    return report;
}

/** Verifies complete per-engine invocations without accepting fixture-returned identity or missing rows. */
function validate(report, matrix, conditions, collector, target, inputs) {
    assert.equal(report.status, 'passed', 'Failed browser report');
    assert.ok(typeof report.scope === 'string' && report.scope.length);
    assert.equal(report.intervals.length, matrix.length * 3, 'Incomplete browser matrix');
    const rows = new Map(matrix.map(row => [rowKey(row), []]));
    const invocations = new Map();
    const versions = new Map();
    for (const row of report.intervals) {
        const group = rows.get(rowKey(row));
        assert.ok(group, 'Unexpected browser case');
        assert.ok(Number.isSafeInteger(row.repetition) && 0 <= row.repetition && row.repetition < 3, 'Invalid repetition');
        assert.equal(group.some(other => other.repetition === row.repetition), false, 'Duplicate browser case');
        assert.match(row.run_id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
        const invocation = JSON.stringify([row.engine, row.repetition]);
        if (invocations.has(invocation)) assert.equal(row.run_id, invocations.get(invocation), 'Changed invocation identity within one repetition');
        else invocations.set(invocation, row.run_id);
        assert.ok(typeof row.browser_version === 'string' && row.browser_version.length);
        if (versions.has(row.engine)) assert.equal(row.browser_version, versions.get(row.engine), 'Changed browser version within one variant');
        else versions.set(row.engine, row.browser_version);
        assert.deepEqual(row.conditions, conditions, 'Changed browser conditions');
        assert.deepEqual(row.collector_identity, collector, 'Wrong actual collector or driver');
        assert.equal(row.target_identity, target, 'Wrong actual application bundle');
        assert.deepEqual(row.input_identities, inputs, 'Changed controlled browser inputs');
        assert.equal(row.cpu_ns, null, 'Browser CPU accounting is unavailable');
        assert.equal(row.allocated_bytes, null, 'Browser allocation accounting is unavailable');
        for (const name of distributions) {
            const value = row[name];
            assert.equal(value.samples, 60);
            for (const field of measurements) assert.ok(Number.isSafeInteger(value[field]) && 0 <= value[field], 'Invalid browser distribution');
            assert.ok(value.p50_ns <= value.p95_ns && value.p95_ns <= value.p99_ns && value.p99_ns <= value.max_ns && value.max_ns <= value.total_ns, 'Invalid browser quantile order');
        }
        if (row.work_counts !== undefined) {
            assert.ok(row.work_counts !== null && typeof row.work_counts === 'object' && Array.isArray(row.work_counts) === false && Object.keys(row.work_counts).length);
            assert.ok(Object.values(row.work_counts).every(value => Number.isSafeInteger(value) && 0 <= value), 'Invalid native work count');
        }
        for (const field of scopes) if (row[field] !== undefined) assert.ok(typeof row[field] === 'string' && row[field].length);
        group.push(row);
    }
    assert.equal(new Set(invocations.values()).size, invocations.size, 'Reused browser invocation within one variant');
    for (const group of rows.values()) {
        assert.equal(group.length, 3, 'Missing independent repetition');
        group.sort((left, right) => left.repetition - right.repetition);
        for (const row of group) {
            assert.deepEqual(row.work_counts, group[0].work_counts, 'Changed deterministic native work across repetitions');
            for (const field of scopes) assert.deepEqual(row[field], group[0][field], 'Changed fixture scope across repetitions');
        }
    }
    return { rows, invocations, scope: report.scope };
}

/** Compares per-run values, never pooled percentiles or averages of distinct workloads. */
function comparison(baselineRuns, candidateRuns) {
    const baseline = [...baselineRuns].sort((left, right) => left - right)[1];
    const candidate = [...candidateRuns].sort((left, right) => left - right)[1];
    return { baseline_runs: baselineRuns, candidate_runs: candidateRuns, baseline, candidate, delta: candidate - baseline, candidate_to_baseline: baseline === 0 ? null : candidate / baseline };
}

/** Reads actual regular-file bytes independently of caller-supplied report identities. */
async function hash(path) {
    assert.ok((await stat(path)).isFile(), 'Browser comparison artifact must be a regular file');
    const digest = createHash('sha256');
    for await (const chunk of createReadStream(path)) digest.update(chunk);
    return digest.digest('hex');
}

async function hashes(paths) {
    assert.ok(Object.keys(paths).every(name => name.trim().length));
    return Object.fromEntries(await Promise.all(Object.entries(paths).map(async ([name, path]) => [name, await hash(path)])));
}

/** Binds parsing to the exact bytes identified in the comparison, including during concurrent source replacement. */
async function document(path, identity) {
    const bytes = await readFile(path);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), identity, 'Raw browser report changed before parsing');
    return JSON.parse(bytes.toString('utf8'));
}

// The same shared processor is callable from Node with one explicit JSON request; it starts no browser or timer.
if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
    assert.equal(process.argv.length, 3, 'Supply one browser comparison request JSON file');
    await compareBrowserPerformance(JSON.parse(await readFile(process.argv[2], 'utf8')));
}
