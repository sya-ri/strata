import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { collectBrowserPerformance } from '../../performance-testkit/src/jsMain/resources/browser-performance.mjs';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(repository, 'build/js/package.json'));
const { chromium, firefox, webkit } = require('playwright');
const [mode, buildArgument, collectorArgument, performanceOutputArgument] = process.argv.slice(2);
assert.ok(mode === 'build' || mode === 'verify' || mode === 'performance', 'Expected build, verify, or performance mode');
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
            inputPaths: { 'standard-component-inventory': inventoryPath },
            engines: [chromium, firefox, webkit],
            scenarios,
            conditions: { viewport: { width: 640, height: 480 }, warmup: 30, samples: 60, input_identity: 'strata-standard-components-and-reactive-web-v1' },
        });
    } else {
        const expected = JSON.parse(await readFile(resolve(build, 'parity/jvm.json'), 'utf8'));
        const receipts = [];
        for (const engine of [chromium, firefox, webkit]) {
            const browser = await engine.launch();
            try {
                for (const theme of ['native', 'minecraft']) {
                    receipts.push(await verifyTheme(browser, engine, theme, expected));
                }
            } finally { await browser.close(); }
        }
        const sourceRevision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8' }).trim();
        const bundleSha256 = createHash('sha256').update(await readFile(resolve(site, 'application.js'))).digest('hex');
        await writeFile(resolve(build, 'parity/browsers.json'), JSON.stringify({ sourceRevision, bundleSha256, browsers: receipts }, null, 2));
    }
} finally { await new Promise(resolve => server.close(resolve)); }

async function verifyTheme(browser, engine, theme, expected) {
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
    receipt.domUpdates = await verifyDomUpdates(browser, page, engine, theme, evidence);
    assert.equal(await page.evaluate(() => window.strataDomPerformance.verifyAll()), true, 'Compiled changed-frame workloads match independent full rendering and release ownership');
    console.log(`Verified initial HTML, adoption, conditionals, native actions and keyed reorder: ${engine.name()} / ${theme}`);
    await page.close();
    return receipt;
}

async function verifyDomUpdates(browser, page, engine, theme, evidence) {
    const updates = JSON.parse(await page.evaluate(() => window.strataVerifyDomUpdates()));
    assert.equal(updates.length, 15, 'Three sizes retain localized/full/geometry/disabled controls, plus three clip/background controls');
    const styles = await page.locator('head > style').allTextContents();
    const comparison = await browser.newPage({ javaScriptEnabled: false, viewport: { width: 640, height: 480 } });
    try {
        for (const update of updates) {
            const prefix = `${engine.name()}-${theme}-${update.count}-${update.phase}`;
            const documentFor = html => `<!doctype html><html><head><style>body { margin: 0; }${styles.join('\n')}</style></head><body>${html}</body></html>`;
            await comparison.setContent(documentFor(update.currentHtml));
            await comparison.evaluate(() => window.scrollTo(0, 0));
            const actual = await comparison.screenshot({ path: resolve(evidence, `${prefix}-incremental.png`) });
            const actualLast = await comparison.locator('body > div > :last-child').screenshot({ path: resolve(evidence, `${prefix}-incremental-last.png`) });
            await comparison.setContent(documentFor(update.referenceHtml));
            await comparison.evaluate(() => window.scrollTo(0, 0));
            const expected = await comparison.screenshot({ path: resolve(evidence, `${prefix}-fresh.png`) });
            const expectedLast = await comparison.locator('body > div > :last-child').screenshot({ path: resolve(evidence, `${prefix}-fresh-last.png`) });
            assert.deepEqual(actual, expected, `Incremental pixels differ from fresh DOM: ${prefix}`);
            assert.deepEqual(actualLast, expectedLast, `Changed last-element pixels differ from fresh DOM: ${prefix}`);
        }
    } finally { await comparison.close(); }
    return updates.map(({ count, phase, mutations }) => ({ count, phase, mutations }));
}
