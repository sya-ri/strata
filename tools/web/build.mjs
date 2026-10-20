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
const [mode, buildArgument, collectorArgument, performanceOutputArgument, inputManifestArgument] = process.argv.slice(2);
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
            inputPaths: await performanceInputs(inventoryPath),
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
                receipts.push(await verifyInteractions(browser, engine, false));
                receipts.push(await verifyInteractions(browser, engine, true));
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
    page.on('pageerror', error => errors.push(error.stack ?? error.message));
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
    console.log(`Verified initial HTML, adoption, conditionals, native actions and keyed reorder: ${engine.name()} / ${theme}`);
    await page.close();
    return receipt;
}

async function verifyInteractions(browser, engine, touch) {
    const context = await browser.newContext({ viewport: touch ? { width: 360, height: 640 } : { width: 800, height: 700 }, hasTouch: touch, deviceScaleFactor: touch ? 2 : 1 });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.stack ?? error.message));
    try {
        await page.goto(`${url}/interactions.html`);
        await page.locator('[data-mounted="true"]').waitFor();
        const snapshot = () => page.evaluate(() => JSON.parse(window.strataInteractions.snapshot()));
        const field = page.locator('input');
        const area = page.locator('textarea');
        await field.fill('日本語🙂');
        await area.fill('日本語\nsecond line');
        assert.equal((await snapshot()).field, '日本語🙂');
        assert.equal((await snapshot()).area, '日本語\nsecond line');
        await page.evaluate(() => { window.retainedField = document.querySelector('input'); window.retainedArea = document.querySelector('textarea'); });
        await field.focus();
        await field.evaluate(element => {
            element.setSelectionRange(1, 2);
            element.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true, data: 'に' }));
            element.value = '日本語🙂にほん';
            element.dispatchEvent(new InputEvent('input', { bubbles: true, isComposing: true, data: 'にほん' }));
            window.strataInteractions.update();
        });
        await page.getByText('Revision 1', { exact: true }).waitFor();
        assert.equal(await field.inputValue(), '日本語🙂にほん');
        assert.equal((await snapshot()).field, '日本語🙂');
        await field.evaluate(element => element.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: '日本' })));
        assert.equal((await snapshot()).field, '日本語🙂にほん');
        await page.keyboard.press('Tab');
        assert.equal(await area.evaluate(element => document.activeElement === element), true, 'Core Tab traversal synchronizes the native editor');
        await page.keyboard.press('Shift+Tab');
        assert.equal(await field.evaluate(element => document.activeElement === element), true);
        const root = await page.locator('#strata-root').boundingBox();
        assert.ok(root);
        await page.mouse.move(root.x + 60, root.y + 180);
        await page.mouse.wheel(0, 72);
        await page.waitForFunction(() => JSON.parse(window.strataInteractions.snapshot()).scroll > 0);
        await page.mouse.move(root.x + 60, root.y + 320);
        await page.mouse.wheel(0, 80);
        await page.waitForFunction(() => JSON.parse(window.strataInteractions.snapshot()).list > 0);
        await page.mouse.move(root.x + 180, root.y + 180);
        await page.mouse.wheel(0, -18);
        await page.waitForFunction(() => JSON.parse(window.strataInteractions.snapshot()).zoom > 2);
        const beforePan = await snapshot();
        await page.mouse.move(root.x + 160, root.y + 170);
        await page.mouse.down();
        await page.mouse.move(root.x + 180, root.y + 180, { steps: 5 });
        await page.mouse.up();
        if (touch) {
            const beforeTouch = await snapshot();
            await touchSequence(page, root, [
                ['pointerdown', 11, 60, 180], ['pointermove', 11, 60, 160], ['pointerup', 11, 60, 160],
                ['pointerdown', 12, 60, 320], ['pointermove', 12, 60, 295], ['pointerup', 12, 60, 295],
                ['pointerdown', 13, 160, 190], ['pointerdown', 14, 200, 190],
                ['pointermove', 14, 220, 190], ['pointerup', 13, 160, 190], ['pointerup', 14, 220, 190],
            ]);
            const afterTouch = await snapshot();
            assert.ok(afterTouch.scroll > beforeTouch.scroll);
            assert.ok(afterTouch.list > beforeTouch.list);
            assert.ok(afterTouch.zoom > beforeTouch.zoom);
            assert.equal(afterTouch.actions, 0, 'Swiping an actionable row must not synthesize a tap');
        }
        const retained = await snapshot();
        assert.notEqual(retained.centerX, beforePan.centerX);
        await page.evaluate(() => { window.strataInteractions.update(); window.strataInteractions.resize(320, 500); });
        await page.getByText('Revision 2', { exact: true }).waitFor();
        const resized = await snapshot();
        for (const key of ['field', 'area', 'scroll', 'list', 'zoom', 'centerX', 'centerY']) assert.equal(resized[key], retained[key], key);
        assert.equal(await page.evaluate(() => document.querySelector('input') === window.retainedField && document.querySelector('textarea') === window.retainedArea), true);
        const action = page.getByRole('button', { name: 'Action surface', exact: true });
        await action.focus();
        await action.evaluate(element => { window.actionFocusLosses = 0; element.addEventListener('focusout', () => window.actionFocusLosses += 1); window.strataInteractions.update(); });
        await page.getByText('Revision 3', { exact: true }).waitFor();
        assert.equal(await page.evaluate(() => window.actionFocusLosses), 0, 'Painting must not transiently disable a focused semantic surface');
        await area.fill('draft after semantic focus');
        assert.equal((await snapshot()).area, 'draft after semantic focus');
        assert.equal(await area.evaluate(element => document.activeElement === element), true);
        await action.focus();
        assert.equal(await action.evaluate(element => getComputedStyle(element).outlineStyle), 'none');
        if (touch) {
            const bounds = await action.boundingBox();
            await page.touchscreen.tap(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        } else await action.click();
        assert.equal((await snapshot()).actions, 1);
        await page.keyboard.press('Enter');
        assert.equal((await snapshot()).actions, 2);
        await action.press('Space');
        assert.equal((await snapshot()).actions, 3);
        await action.evaluate(element => element.click());
        assert.equal((await snapshot()).actions, 4, 'Accessibility activation must dispatch exactly one common action');
        assert.ok(await page.locator('canvas').count() > 0);
        const family = await page.getByText('A日🙂', { exact: true }).evaluate(element => getComputedStyle(element).fontFamily);
        assert.ok(family.includes('StrataFixture'), 'The resource font must be the loaded fixture face');
        assert.equal((await snapshot()).fontWidth, 21, 'Independent advance widths from the original OpenType fixture');
        assert.deepEqual(errors, []);
        await mkdir(resolve(build, 'parity'), { recursive: true });
        await page.screenshot({ path: resolve(build, 'parity', `${engine.name()}-interactions-${touch ? 'touch' : 'desktop'}.png`) });
        await page.evaluate(() => window.strataInteractions.close());
        assert.equal(await page.locator('#strata-root > *').count(), 0);
        assert.equal((await snapshot()).subscriptions, 0);
        assert.deepEqual(errors, []);
        return { engine: engine.name(), version: browser.version(), scenario: 'retained-interactions', touchEmulation: touch, syntheticNavigation: touch, osIme: 'not tested', physicalDevice: 'not tested' };
    } catch (failure) {
        console.error("Interaction browser errors:", errors);
        console.error("Interaction active element:", await page.evaluate(() => document.activeElement?.outerHTML));
        await mkdir(resolve(build, 'parity'), { recursive: true });
        await writeFile(resolve(build, 'parity', `${engine.name()}-interactions-failure.html`), await page.locator("body").innerHTML());
        throw failure;
    } finally { await context.close(); }
}

async function touchSequence(page, root, events) {
    await page.evaluate(({ root, events }) => {
        const target = document.getElementById('strata-root');
        for (const [type, pointerId, x, y] of events) target.dispatchEvent(new PointerEvent(type, {
            pointerId, pointerType: 'touch', clientX: root.x + x, clientY: root.y + y,
            button: 0, buttons: type === 'pointerup' ? 0 : 1, bubbles: true, cancelable: true,
        }));
    }, { root, events });
}

/** Adds frozen caller inputs while retaining the compiled standard workload and collector defaults. */
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
