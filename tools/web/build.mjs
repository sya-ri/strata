import assert from 'node:assert/strict';
import { buttonMeasurement } from './button-measurement.mjs';
import { verifyRegisteredControls } from './verification-fixtures.mjs';
import { createServer } from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { collectBrowserPerformance } from '../../performance-testkit/src/jsMain/resources/browser-performance.mjs';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(repository, 'build/js/package.json'));
const { chromium, firefox, webkit } = require('playwright');
const verificationFixtures = [buttonMeasurement];
const [mode, buildArgument, collectorArgument, performanceOutputArgument, inputManifestArgument] = process.argv.slice(2);
assert.ok(['build', 'verify', 'performance'].includes(mode), 'Expected build, verify, or performance mode');
assert.ok(buildArgument, 'Expected an application build directory');
const build = resolve(buildArgument);
const site = resolve(build, 'site');
const directory = mode === 'build' ? resolve(build, 'dist/js/productionExecutable') : site;
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png' };
const server = createServer(async (request, response) => {
    try {
        const name = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
        const route = name === '/' || (mode === 'build' && name === '/minecraft.html') ? '/index.html' : name;
        const file = resolve(directory, `.${route}`);
        if (!file.startsWith(directory + sep)) { response.writeHead(403).end(); return; }
        const data = await readFile(file);
        response.writeHead(200, { 'Content-Type': mime[extname(file)] ?? 'application/octet-stream' }).end(data);
    } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${server.address().port}`;

try {
    if (mode === 'build') {
        const browser = await chromium.launch();
        try {
            const page = await browser.newPage();
            await mkdir(site, { recursive: true });
            await cp(directory, site, { recursive: true });
            for (const route of ['', 'minecraft.html']) {
                await page.goto(`${url}/${route}?strata-prerender`);
                await page.waitForFunction(() => typeof window.strataInitialDocument === 'string');
                const html = await page.evaluate(() => window.strataInitialDocument);
                assert.ok(html.includes('Strata runtime parity'));
                await writeFile(resolve(site, route || 'index.html'), html);
                const inventory = JSON.parse(await page.evaluate(() => window.strataPerformanceInventory()));
                if (route === '') await writeFile(resolve(site, 'performance-inventory.json'), JSON.stringify(inventory, null, 2));
                else assert.deepEqual(inventory, JSON.parse(await readFile(resolve(site, 'performance-inventory.json'), 'utf8')));
                for (const fixture of verificationFixtures) {
                    const controls = JSON.parse(await page.evaluate(method => window[method](), fixture.inventoryMethod));
                    const inventoryFile = resolve(site, fixture.inventoryFile);
                    if (route === '') await writeFile(inventoryFile, JSON.stringify(controls, null, 2));
                    else assert.deepEqual(controls, JSON.parse(await readFile(inventoryFile, 'utf8')));
                }
            }
            console.log(`Built initial document and application bundle: ${site}`);
        } finally { await browser.close(); }
    } else if (mode === 'performance') {
        assert.ok(collectorArgument, 'Expected the actual linked testkit JS artifact');
        assert.ok(performanceOutputArgument, 'Expected a new performance evidence file');
        const inventoryPath = resolve(site, 'performance-inventory.json');
        const inventory = JSON.parse(await readFile(inventoryPath, 'utf8'));
        assert.equal(inventory.supported.length + inventory.unavailable.length, 26, 'The compiled standard component inventory');
        assert.equal(new Set([...inventory.supported, ...inventory.unavailable]).size, 26);
        const scenarios = ['', 'minecraft.html'].flatMap(route => [
            { id: `reactive-${route || 'native'}`, url: `${url}/${route}`, targetUrl: `${url}/application.js`, phases: ['Initial', 'Idle', 'Update', 'Input', 'Resize', 'Release'], inventory },
            ...inventory.supported.map(component => ({ id: `component-${component}-${route || 'native'}`, url: `${url}/${route}?strata-component=${encodeURIComponent(component)}`, targetUrl: `${url}/application.js`, phases: inventory.phases, inventory })),
        ]);
        await collectBrowserPerformance({
            collectorPath: resolve(collectorArgument), targetPath: resolve(site, 'application.js'), outputPath: resolve(performanceOutputArgument),
            inputPaths: await performanceInputs(inventoryPath),
            engines: [chromium, firefox, webkit],
            scenarios,
            conditions: { viewport: { width: 640, height: 480 }, warmup: 30, samples: 60, input_identity: 'strata-standard-components-and-reactive-web-v1' },
        });
    } else {
        const variant = collectorArgument ?? 'candidate';
        assert.ok(['baseline', 'candidate'].includes(variant), 'Expected baseline or candidate runtime');
        const expected = JSON.parse(await readFile(resolve(build, 'parity/jvm.json'), 'utf8'));
        const receipts = [];
        for (const engine of [chromium, firefox, webkit]) {
            const browser = await engine.launch();
            try {
                for (const theme of ['native', 'minecraft']) {
                    receipts.push(await verifyTheme(browser, engine, theme, expected, variant));
                }
            } finally { await browser.close(); }
        }
        const sourceRevision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8' }).trim();
        const bundleSha256 = createHash('sha256').update(await readFile(resolve(site, 'application.js'))).digest('hex');
        await writeFile(resolve(build, 'parity/browsers.json'), JSON.stringify({ sourceRevision, bundleSha256, browsers: receipts }, null, 2));
    }
} finally { await new Promise(resolve => server.close(resolve)); }

async function verifyTheme(browser, engine, theme, expected, variant) {
    const address = theme === 'native' ? url : `${url}/minecraft.html`;
    const staticPage = await browser.newPage({ javaScriptEnabled: false });
    await staticPage.goto(address);
    assert.deepEqual(await staticPage.locator('#strata-root > :not(progress)').allTextContents(), expected[0]);
    assert.equal(await staticPage.locator('progress').getAttribute('value'), '0.25');
    await staticPage.close();
    const page = await browser.newPage({ viewport: { width: 640, height: 480 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(address);
    await page.locator('[data-mounted="true"]').waitFor();
    const snapshot = () => page.locator('#strata-root > :not(progress)').allTextContents();
    const observed = [await snapshot()];
    await page.evaluate(() => {
        window.originalNodes = Object.fromEntries([...document.querySelectorAll('#strata-root > :not(progress)')].map(node => [node.textContent, node]));
    });
    for (const action of ['Advance', 'Toggle', 'Reorder', 'Toggle']) {
        await page.getByRole('button', { name: action, exact: true }).click();
        const next = expected[observed.length];
        await page.waitForFunction(labels => JSON.stringify([...document.querySelectorAll('#strata-root > :not(progress)')].map(node => node.textContent)) === JSON.stringify(labels), next);
        observed.push(await snapshot());
    }
    assert.deepEqual(observed, expected);
    assert.equal(await page.locator('progress').getAttribute('value'), '0.75');
    assert.equal(await page.getByRole('button', { name: 'Unavailable', exact: true }).isDisabled(), true);
    assert.equal(await page.evaluate(() => ['Alpha', 'Beta', 'Advance'].every(label => [...document.querySelectorAll('#strata-root > :not(progress)')].find(node => node.textContent === label) === window.originalNodes[label])), true);
    assert.deepEqual(errors, []);
    assert.deepEqual(await page.evaluate(() => JSON.parse(window.strataPerformanceInventory())), JSON.parse(await readFile(resolve(site, 'performance-inventory.json'), 'utf8')));
    assert.equal(await page.evaluate(() => window.strataVerifyPerformanceCollector()), true, 'Compiled component admission and collector failure/cleanup contracts');
    assert.deepEqual(errors, []);
    const evidence = resolve(build, 'parity');
    await mkdir(evidence, { recursive: true });
    await page.screenshot({ path: resolve(evidence, `${engine.name()}-${theme}.png`) });
    const receipt = { theme, engine: engine.name(), version: browser.version(), snapshots: observed };
    const controlEvidence = resolve(evidence, `registered-controls-${randomUUID()}`);
    await mkdir(controlEvidence, { recursive: true });
    try {
        receipt.controls = await verifyRegisteredControls({ browser, page, engine, theme, site, evidence: controlEvidence, variant, fixtures: verificationFixtures });
    } finally { await page.close(); }
    console.log(`Verified initial HTML, adoption, conditionals, native actions and keyed reorder: ${engine.name()} / ${theme}`);
    return receipt;
}

/** Adds caller-supplied frozen fixture inputs without changing the standard workload. */
async function performanceInputs(inventoryPath) {
    const inputs = { 'standard-component-inventory': inventoryPath };
    if (inputManifestArgument === undefined) return inputs;
    const manifestPath = resolve(inputManifestArgument);
    const extra = JSON.parse(await readFile(manifestPath, 'utf8'));
    assert.ok(extra !== null && typeof extra === 'object' && Array.isArray(extra) === false, 'Expected a fixture input path map');
    inputs['fixture-input-manifest'] = manifestPath;
    for (const [name, path] of Object.entries(extra)) {
        assert.ok(Object.hasOwn(inputs, name) === false, 'Reserved fixture input name');
        assert.ok(typeof path === 'string' && path.length !== 0, 'Expected a fixture input path');
        Object.defineProperty(inputs, name, { value: resolve(dirname(manifestPath), path), enumerable: true });
    }
    return inputs;
}
