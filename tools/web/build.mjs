import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(repository, 'build/js/package.json'));
const { chromium, firefox, webkit } = require('playwright');
const [mode, buildArgument] = process.argv.slice(2);
assert.ok(mode === 'build' || mode === 'verify', 'Expected build or verify mode');
assert.ok(buildArgument, 'Expected an application build directory');
const build = resolve(buildArgument);
const site = resolve(build, 'site');
const directory = mode === 'build' ? resolve(build, 'dist/js/productionExecutable') : site;
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png' };
const server = createServer(async (request, response) => {
    try {
        const name = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
        const file = resolve(directory, `.${name === '/' ? '/index.html' : name}`);
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
            await page.goto(`${url}/?strata-prerender`);
            await page.waitForFunction(() => typeof window.strataInitialDocument === 'string');
            const html = await page.evaluate(() => window.strataInitialDocument);
            assert.ok(html.includes('Strata runtime parity'));
            await mkdir(site, { recursive: true });
            await cp(directory, site, { recursive: true });
            await writeFile(resolve(site, 'index.html'), html);
            console.log(`Built initial document and application bundle: ${site}`);
        } finally { await browser.close(); }
    } else {
        const expected = JSON.parse(await readFile(resolve(build, 'parity/jvm.json'), 'utf8'));
        const receipts = [];
        for (const engine of [chromium, firefox, webkit]) {
            const browser = await engine.launch();
            try {
                const staticPage = await browser.newPage({ javaScriptEnabled: false });
                await staticPage.goto(url);
                assert.deepEqual(await staticPage.locator('#strata-root > *').allTextContents(), expected[0]);
                await staticPage.close();
                const page = await browser.newPage({ viewport: { width: 640, height: 480 } });
                const errors = [];
                page.on('pageerror', error => errors.push(error.message));
                await page.goto(url);
                await page.locator('[data-mounted="true"]').waitFor();
                const snapshot = () => page.locator('#strata-root > *').allTextContents();
                const observed = [await snapshot()];
                await page.evaluate(() => {
                    window.originalNodes = Object.fromEntries([...document.querySelectorAll('#strata-root > *')].map(node => [node.textContent, node]));
                });
                for (const action of ['Advance', 'Toggle', 'Reorder', 'Toggle']) {
                    await page.getByRole('button', { name: action, exact: true }).click();
                    const next = expected[observed.length];
                    await page.waitForFunction(labels => JSON.stringify([...document.querySelectorAll('#strata-root > *')].map(node => node.textContent)) === JSON.stringify(labels), next);
                    observed.push(await snapshot());
                }
                assert.deepEqual(observed, expected);
                assert.equal(await page.evaluate(() => ['Alpha', 'Beta', 'Advance'].every(label => [...document.querySelectorAll('#strata-root > *')].find(node => node.textContent === label) === window.originalNodes[label])), true);
                assert.deepEqual(errors, []);
                const evidence = resolve(build, 'parity');
                await mkdir(evidence, { recursive: true });
                await page.screenshot({ path: resolve(evidence, `${engine.name()}.png`) });
                receipts.push({ engine: engine.name(), version: browser.version(), snapshots: observed });
                console.log(`Verified initial HTML, adoption, conditionals, native actions and keyed reorder: ${engine.name()}`);
            } finally { await browser.close(); }
        }
        await writeFile(resolve(build, 'parity/browsers.json'), JSON.stringify(receipts, null, 2));
    }
} finally { await new Promise(resolve => server.close(resolve)); }
