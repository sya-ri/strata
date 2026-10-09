/** Setter instrumentation surrounds one untimed work probe and is restored before any meter is created. */
export function installDomPerformanceBridge() {
    const original = {};
    let workCounts;
    const protocol = () => window.strataDomPerformance;
    window.strataDomWorkCounts = () => {
        if (workCounts === undefined) throw new Error('Untimed work has not completed');
        return { ...workCounts };
    };
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
