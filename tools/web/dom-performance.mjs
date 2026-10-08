import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { readFile, stat, writeFile } from 'node:fs/promises';
import { dirname, extname, isAbsolute, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { collectBrowserPerformance } from '../../performance-testkit/src/jsMain/resources/browser-performance.mjs';

// This fixture-owned adapter leaves the shipped build/collector/default matrix unchanged.
// All cases come from the loaded compiled inventory; the common kit owns clocks, repetitions and evidence output.
const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(repository, 'build/js/package.json'));
const { chromium, firefox, webkit } = require('playwright');
const [siteArgument, collectorArgument, outputArgument, manifestArgument, inventoryArgument] = process.argv.slice(2);
assert.ok(siteArgument && collectorArgument && outputArgument && manifestArgument && inventoryArgument,
    'Supply site, linked JS collector artifact, new output, immutable extra-file manifest and frozen compiled inventory paths');
const site = resolve(siteArgument);
const manifest = resolve(manifestArgument);
const inventoryPath = resolve(inventoryArgument);
const existingOutput = await stat(resolve(outputArgument)).catch(error => {
    if (error.code === 'ENOENT') return null;
    throw error;
});
assert.equal(existingOutput, null, 'Performance evidence path already exists');
const extras = JSON.parse(await readFile(manifest, 'utf8'));
assert.ok(Object.keys(extras).length);
for (const [label, path] of Object.entries(extras)) {
    assert.ok(label.trim().length && typeof path === 'string' && isAbsolute(path), 'Fixture inputs require nonempty labels and absolute file paths');
    assert.ok((await stat(path)).isFile(), 'Fixture input must be a preserved regular file');
}
const inputPaths = { 'dom-collector-adapter': fileURLToPath(import.meta.url), 'fixture-extra-manifest': manifest, 'compiled-dom-inventory': inventoryPath, ...extras };
assert.equal(Object.keys(inputPaths).length, Object.keys(extras).length + 3, 'Fixture labels must not replace owned adapter/manifest/inventory inputs');
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };
const server = createServer(async (request, response) => {
    try {
        const name = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
        const file = resolve(site, `.${name === '/' ? '/index.html' : name}`);
        if (file.startsWith(site + sep) === false) { response.writeHead(403).end(); return; }
        response.writeHead(200, { 'Content-Type': mime[extname(file)] ?? 'application/octet-stream' }).end(await readFile(file));
    } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${server.address().port}`;

try {
    const browser = await chromium.launch();
    let inventory;
    try {
        const page = await browser.newPage();
        await page.goto(url);
        inventory = JSON.parse(await page.evaluate(() => window.strataDomPerformance.inventory()));
        assert.equal(await page.evaluate(() => window.strataDomPerformance.verifyAll()), true, 'Compiled targeted work admission');
    } finally { await browser.close(); }
    const saved = await readFile(inventoryPath, 'utf8').catch(error => {
        if (error.code === 'ENOENT') return null;
        throw error;
    });
    if (saved === null) await writeFile(inventoryPath, JSON.stringify(inventory, null, 2), { flag: 'wx' });
    else assert.deepEqual(JSON.parse(saved), inventory, 'Loaded fixture differs from the frozen compiled inventory');
    assert.equal(inventory.schema, 'strata-dom-writes-v1');
    const scenarios = ['', 'minecraft.html'].flatMap(route => inventory.cases.map(item => {
        const parameters = new URLSearchParams({ 'strata-dom-mode': item.mode, 'strata-dom-count': String(item.count) });
        return { id: `${item.mode}-${item.count}-${route || 'native'}`, url: `${url}/${route}?${parameters}`, targetUrl: `${url}/application.js`, phases: [item.phase], inventory };
    }));
    await collectBrowserPerformance({
        collectorPath: resolve(collectorArgument), targetPath: resolve(site, 'application.js'), outputPath: resolve(outputArgument), inputPaths,
        engines: [chromium, firefox, webkit].map(bridgeEngine), scenarios,
        conditions: { viewport: { width: 640, height: 480 }, warmup: 30, samples: 60, input_identity: inventory.schema, publication: inventory.publication, operation: inventory.operation },
    });
} finally { await new Promise(resolve => server.close(resolve)); }

/** Adapts page-local protocol names without changing the common collector or its generated fixture matrix. */
function bridgeEngine(engine) {
    return {
        name: () => engine.name(),
        launch: async () => {
            const browser = await engine.launch();
            return {
                version: () => browser.version(),
                close: () => browser.close(),
                newPage: async options => {
                    const page = await browser.newPage(options);
                    await page.addInitScript(installBridge);
                    return page;
                },
            };
        },
    };
}

/** Setter instrumentation surrounds one untimed work probe and is restored before any meter is created. */
function installBridge() {
    const original = {};
    let workCounts;
    const protocol = () => window.strataDomPerformance;
    const hooks = {
        strataPerformanceInventory: () => protocol().inventory(),
        strataVerifyPerformanceCollector: async () => {
            if (await original.strataVerifyPerformanceCollector() !== true) throw new Error('Original collector verification failed');
            let instrumentation;
            try {
                const root = protocol().openWork();
                protocol().publishWork();
                instrumentation = instrument(root);
                protocol().applyWork();
                workCounts = instrumentation.capture();
                instrumentation.restore();
                instrumentation = undefined;
                if (protocol().verifyWork() !== true) throw new Error('Untimed changed-frame work differs from full rendering');
                return true;
            } finally {
                try { instrumentation?.restore(); }
                finally { protocol().closeWork(); }
            }
        },
        strataMeasurePerformance: async phase => {
            if (workCounts === undefined) throw new Error('Untimed work must complete before sampling');
            const sample = JSON.parse(await protocol().measure(phase));
            return JSON.stringify({ ...sample, work_counts: workCounts, operation_scope: 'synchronous changed-frame WebUiHost.render; publication excluded', work_scope: 'JavaScript setter attempts in one untimed application; observers and native internal work excluded' });
        },
    };
    for (const [name, hook] of Object.entries(hooks)) {
        Object.defineProperty(window, name, { configurable: true, get: () => hook, set: value => { original[name] = value; } });
    }

    function instrument(root) {
        const counts = { css_assignments: 0, css_set_property: 0, attributes: 0, text_content: 0, button_properties: 0, progress_properties: 0, insertions: 0, removals: 0, root_setter_attempts: 0 };
        const changed = new Set();
        const restore = [];
        const owned = element => element === root || root.contains(element);
        function record(element, key, setter = true) {
            if (owned(element) === false) return;
            counts[key] += 1;
            if (setter) {
                if (element === root) counts.root_setter_attempts += 1;
                else changed.add(element);
            }
        }
        function ownerOf(prototype, name) {
            let owner = prototype;
            while (owner !== null && Object.getOwnPropertyDescriptor(owner, name) === undefined) owner = Object.getPrototypeOf(owner);
            if (owner === null) throw new Error(`Missing native descriptor: ${name}`);
            const descriptor = Object.getOwnPropertyDescriptor(owner, name);
            if (descriptor.configurable !== true) throw new Error(`Native descriptor cannot be instrumented: ${name}`);
            return { owner, descriptor };
        }
        function replace(owner, name, descriptor, next) {
            Object.defineProperty(owner, name, next);
            restore.push(() => Object.defineProperty(owner, name, descriptor));
        }
        function property(prototype, name, key) {
            const { owner, descriptor } = ownerOf(prototype, name);
            if (typeof descriptor.set !== 'function') throw new Error(`Missing native setter: ${name}`);
            replace(owner, name, descriptor, { ...descriptor, set(value) { record(this, key); descriptor.set.call(this, value); } });
        }
        function method(prototype, name, key, setter = true) {
            const { owner, descriptor } = ownerOf(prototype, name);
            replace(owner, name, descriptor, { ...descriptor, value(...args) { record(this, key, setter); return descriptor.value.apply(this, args); } });
        }
        function release() {
            const failures = [];
            for (const action of restore.splice(0).reverse()) {
                try { action(); } catch (failure) { failures.push(failure); }
            }
            changed.clear();
            if (failures.length) throw new AggregateError(failures, 'Native descriptor restoration failed');
        }
        try {
            const { owner, descriptor } = ownerOf(HTMLElement.prototype, 'style');
            const proxies = new WeakMap();
            replace(owner, 'style', descriptor, { ...descriptor, get() {
                const native = descriptor.get.call(this);
                if (owned(this) === false) return native;
                if (proxies.has(native)) return proxies.get(native);
                const element = this;
                const proxy = new Proxy(native, {
                    get(target, key) {
                        const value = Reflect.get(target, key, target);
                        if (typeof value !== 'function') return value;
                        return (...args) => {
                            if (key === 'setProperty') record(element, 'css_set_property');
                            return value.apply(target, args);
                        };
                    },
                    set(target, key, value) {
                        record(element, 'css_assignments');
                        return Reflect.set(target, key, value, target);
                    },
                });
                proxies.set(native, proxy);
                return proxy;
            } });
            method(Element.prototype, 'setAttribute', 'attributes');
            property(Node.prototype, 'textContent', 'text_content');
            for (const name of ['type', 'disabled']) property(HTMLButtonElement.prototype, name, 'button_properties');
            for (const name of ['max', 'value']) property(HTMLProgressElement.prototype, name, 'progress_properties');
            method(Node.prototype, 'insertBefore', 'insertions', false);
            method(Node.prototype, 'removeChild', 'removals', false);
        } catch (failure) {
            try { release(); }
            catch (cleanup) { throw new AggregateError([failure, cleanup], 'Native instrumentation and restoration failed'); }
            throw failure;
        }
        return {
            capture: () => ({ ...counts, elements_with_setters: changed.size, rendered_elements: root.children.length }),
            restore: release,
        };
    }
}
