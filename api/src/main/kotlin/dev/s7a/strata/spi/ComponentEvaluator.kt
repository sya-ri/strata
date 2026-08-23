package dev.s7a.strata.spi

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.Element

/**
 * Retained evaluator for deferred profile-backed component content.
 *
 * An evaluator snapshots the active component profile without retaining a callback-lifetime [UiScope].
 * Calls are confined to the owner thread on which the evaluator was created, may occur after the original screen callback returns, and propagate construction or validation failures unchanged.
 * The element retaining a deferred callback owns this evaluator reference; releasing that element releases the snapshot and any platform reference reachable from it.
 */
@InternalStrataRuntimeApi
public fun interface ComponentEvaluator {
    /**
     * Evaluates one deferred callback with the captured profile active.
     *
     * @param content callback that must emit exactly one root element.
     * @return the exact emitted root.
     * @throws IllegalArgumentException when [content] emits zero or multiple roots.
     * @throws IllegalStateException when called from a thread other than its owner.
     */
    public fun evaluate(content: UiScope.() -> Unit): Element
}
