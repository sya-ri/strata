package dev.s7a.strata.spi

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.buildComponentTree
import dev.s7a.strata.element.Element

/**
 * Privileged dynamic boundary between platform-neutral screen callbacks and one runtime implementation.
 *
 * Evaluation is synchronous, owner-thread confined, and restores an enclosing runtime when nested evaluation completes or fails.
 * Application code must not invoke this bridge.
 */
@InternalStrataRuntimeApi
public object ComponentRuntimeBridge {
    private val active = ThreadLocal<ComponentRuntime?>()

    /**
     * Evaluates one screen callback with [runtime] implicitly available to profile-backed component functions.
     *
     * @param runtime owner-thread runtime implementation active only during [content].
     * @param content callback that must emit exactly one root component.
     * @return the exact emitted root element.
     * @throws IllegalArgumentException when [content] emits zero or multiple roots.
     * @throws Throwable when [content] or component resolution fails; the exact failure is propagated.
     */
    public fun evaluate(
        runtime: ComponentRuntime,
        content: UiScope.() -> Unit,
    ): Element {
        val previous = active.get()
        active.set(runtime)
        return try {
            buildComponentTree(content)
        } finally {
            if (previous == null) {
                active.remove()
            } else {
                active.set(previous)
            }
        }
    }

    /**
     * Returns the runtime active for the current owner-thread screen callback.
     *
     * @return current runtime implementation.
     * @throws IllegalStateException when called outside runtime evaluation.
     */
    @JvmSynthetic
    internal fun current(): ComponentRuntime =
        checkNotNull(active.get()) {
            "Profile-backed components require an active runtime screen callback on this thread."
        }

    /**
     * Returns the current runtime for deferred component construction, or null during raw structural evaluation.
     */
    @JvmSynthetic
    internal fun currentOrNull(): ComponentRuntime? = active.get()
}
