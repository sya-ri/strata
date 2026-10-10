import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

/** Verifies every registered control with loaded-byte, raw-receipt and fresh-render pixel evidence. */
export async function verifyRegisteredControls({ browser, page, engine, theme, site, evidence, variant, fixtures }) {
    assert.equal(new Set(fixtures.map(fixture => fixture.id)).size, fixtures.length);
    const bundlePath = resolve(site, 'application.js');
    const hash = bytes => createHash('sha256').update(bytes).digest('hex');
    const bundleSha256 = hash(await readFile(bundlePath));
    const loadedSha256 = await page.evaluate(async () => {
        const response = await fetch('application.js', { cache: 'no-store' });
        if (response.ok === false) throw new Error('Application response failed');
        return [...new Uint8Array(await crypto.subtle.digest('SHA-256', await response.arrayBuffer()))].map(value => value.toString(16).padStart(2, '0')).join('');
    });
    assert.equal(loadedSha256, bundleSha256, 'Loaded application response identity');
    const registrations = [];
    for (const fixture of fixtures) {
        assert.match(fixture.id, /^[a-z0-9-]+$/);
        const inventoryPath = resolve(site, fixture.inventoryFile);
        const inventoryBytes = await readFile(inventoryPath);
        const inventory = JSON.parse(inventoryBytes);
        assert.equal(inventory.contract, fixture.contract);
        assert.equal(inventory.controls.length, fixture.expectedControls);
        assert.equal(new Set(inventory.controls.map(control => control.name)).size, fixture.expectedControls);
        assert.equal(new Set(inventory.controls.map(control => control.token)).size, fixture.expectedControls);
        assert.deepEqual(JSON.parse(await page.evaluate(method => window[method](), fixture.inventoryMethod)), inventory);
        const provenance = { engine: engine.name(), version: browser.version(), theme, variant, bundleSha256, loadedSha256, inventorySha256: hash(inventoryBytes), registration: fixture.id };
        const controls = [];
        for (const control of inventory.controls) {
            assert.match(control.token, /^[a-z0-9-]+$/);
            const prefix = `${engine.name()}-${theme}-${fixture.id}-${control.token}`;
            try {
                const result = await page.evaluate(fixture.execute, fixture.prepare(control, variant));
                await writeFile(resolve(evidence, `${prefix}.raw.json`), JSON.stringify({ ...result, ...provenance }, null, 2), { flag: 'wx' });
                assert.equal(result.fixture.ok, true, `${control.token}: ${result.fixture.error}`);
                assert.equal(result.fixture.control, control.token);
                fixture.validate(result, control, variant);
                const screenshots = [];
                for (const [index, snapshot] of result.fixture.snapshots.entries()) {
                    const pixels = await browser.newPage({ viewport: { width: 640, height: 480 } });
                    try {
                        const captures = [];
                        for (const side of ['current', 'reference']) {
                            await pixels.setContent(`<!doctype html><html><head>${snapshot.stylesHtml}<style>body { margin: 0; }</style></head><body>${snapshot[`${side}Html`]}</body></html>`);
                            await pixels.evaluate(() => document.fonts.ready);
                            if (snapshot.focusIndex !== null) await pixels.locator('body > div > *').nth(snapshot.focusIndex).focus();
                            const capture = await pixels.screenshot();
                            const name = `${prefix}-${index}-${side}.png`;
                            await writeFile(resolve(evidence, name), capture, { flag: 'wx' });
                            captures.push(capture);
                            screenshots.push({ phase: snapshot.phase, side, file: name, sha256: hash(capture) });
                        }
                        assert.deepEqual(captures[0], captures[1], `${control.token}/${snapshot.phase}: independent fresh-render pixels`);
                    } finally { await pixels.close(); }
                }
                controls.push({ ...result, screenshots });
            } catch (error) {
                await writeFile(resolve(evidence, `${prefix}.rejected.json`), JSON.stringify({ ...provenance, control, error: String(error), complete: false }, null, 2), { flag: 'wx' });
                throw error;
            }
        }
        assert.equal(hash(await readFile(inventoryPath)), provenance.inventorySha256, 'Control inventory drift');
        registrations.push({ ...provenance, inventory, controls });
    }
    assert.equal(hash(await readFile(bundlePath)), bundleSha256, 'Runtime bundle drift');
    await writeFile(resolve(evidence, 'controls.json'), JSON.stringify(registrations, null, 2), { flag: 'wx' });
    return registrations;
}
