import assert from 'node:assert/strict';

// Decode the compiled external enum once into immutable native fault effects.
const faultEffects = Object.freeze({
    None: Object.freeze({ creation: false, nullContext: false, measure: false }),
    CreationFailure: Object.freeze({ creation: true, nullContext: false, measure: false }),
    NullContext: Object.freeze({ creation: false, nullContext: true, measure: false }),
    MeasureFailure: Object.freeze({ creation: false, nullContext: false, measure: true }),
});

/** Registers untimed Canvas controls with the ordinary complete Web verification run. */
export const buttonMeasurement = Object.freeze({
    id: 'button-measurement',
    contract: 'strata-web-button-measurement-controls-v1',
    inventoryFile: 'button-measurement-inventory.json',
    inventoryMethod: 'strataButtonMeasurementInventory',
    expectedControls: 36,
    execute: runCanvasControl,
    prepare(control, variant) {
        const fault = faultEffects[control.fault];
        assert.ok(fault, 'Unknown compiled Canvas fault');
        return { control, fault, fixedWidth: variant === 'candidate' };
    },
    validate(result, control, variant) {
        const fault = faultEffects[control.fault];
        assert.equal(result.fixture.checks.terminal_release, true);
        assert.equal(result.descriptorsRestored, true);
        assert.ok(result.work.length !== 0, `${control.token}: no work interval`);
        for (const step of result.work) {
            const groups = (variant === 'candidate' ? 0 : step.buttons) + step.texts;
            const expected = { canvas: groups, context: groups, font: groups, measure: groups };
            if (groups !== 0) {
                if (fault.creation) Object.assign(expected, { context: 0, font: 0, measure: 0 });
                if (fault.nullContext) Object.assign(expected, { font: 0, measure: 0 });
            }
            assert.deepEqual(step.counts, expected, `${control.token}: actual native Canvas attempts`);
        }
    },
});

/** Browser-side fault instrumentation is confined to one synchronous untimed fixture call. */
function runCanvasControl({ control, fault, fixedWidth }) {
    const entries = [
        [Document.prototype, 'createElement'],
        [HTMLCanvasElement.prototype, 'getContext'],
        [CanvasRenderingContext2D.prototype, 'font'],
        [CanvasRenderingContext2D.prototype, 'measureText'],
    ].map(([owner, name]) => ({ owner, name, descriptor: Object.getOwnPropertyDescriptor(owner, name) }));
    let failure = new Error(`Injected native Canvas failure: ${control.token}`);
    const work = [];
    let step = null;
    let paused = false;
    const active = () => step !== null && paused === false;
    const trace = (name, args) => {
        if (active()) { step.counts[name] += 1; step.calls.push({ operation: name, args }); }
    };
    const probe = {
        begin(buttons, texts, scope) {
            if (step !== null || paused) throw new Error('Overlapping Canvas probe');
            step = { buttons, texts, scope, counts: { canvas: 0, context: 0, font: 0, measure: 0 }, calls: [] };
        },
        end() { if (step === null) throw new Error('Missing Canvas probe'); work.push(step); step = null; },
        pause() { if (paused) throw new Error('Nested Canvas pause'); paused = true; },
        resume() { if (paused === false) throw new Error('Missing Canvas pause'); paused = false; },
        sameFailure(actual) { return actual === failure; },
        setFailure(actual) { if (step !== null) throw new Error('Fault changed during Canvas work'); failure = actual; },
    };
    let fixture;
    try {
        for (const entry of entries) if (entry.descriptor?.configurable !== true) throw new Error(`Native descriptor cannot be instrumented: ${entry.name}`);
        const [create, context, font, measure] = entries;
        Object.defineProperty(create.owner, create.name, { ...create.descriptor, value: function (tag, ...args) {
            if (String(tag).toLowerCase() === 'canvas') {
                trace('canvas', [tag]);
                if (active() && fault.creation) throw failure;
            }
            return Reflect.apply(create.descriptor.value, this, [tag, ...args]);
        } });
        Object.defineProperty(context.owner, context.name, { ...context.descriptor, value: function (kind, ...args) {
            if (kind === '2d') {
                trace('context', [kind]);
                if (active() && fault.nullContext) return null;
            }
            return Reflect.apply(context.descriptor.value, this, [kind, ...args]);
        } });
        Object.defineProperty(font.owner, font.name, { ...font.descriptor, set: function (value) {
            trace('font', [value]);
            return Reflect.apply(font.descriptor.set, this, [value]);
        } });
        Object.defineProperty(measure.owner, measure.name, { ...measure.descriptor, value: function (...args) {
            trace('measure', args);
            if (active() && fault.measure) throw failure;
            return Reflect.apply(measure.descriptor.value, this, args);
        } });
        fixture = JSON.parse(window.strataVerifyButtonMeasurement(control.name, fixedWidth, probe));
        if (step !== null || paused) throw new Error('Canvas probe failed to close');
    } finally {
        for (const entry of entries) if (entry.descriptor) Object.defineProperty(entry.owner, entry.name, entry.descriptor);
    }
    const descriptorsRestored = entries.every(({ owner, name, descriptor }) => {
        const current = Object.getOwnPropertyDescriptor(owner, name);
        return ['value', 'get', 'set', 'writable', 'enumerable', 'configurable'].every(key => current[key] === descriptor[key]);
    });
    return { fixture, work, descriptorsRestored };
}
