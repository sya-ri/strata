package dev.s7a.strata.dsl

import dev.s7a.strata.element.Element
import java.util.Collections
import kotlin.jvm.JvmSynthetic

/**
 * Callback-lifetime scope used to emit immutable element descriptions.
 * A scope is confined to the thread that constructs it and remains valid only while its [buildUi] callback is running.
 * The scope temporarily retains references to caller-owned immutable [Element] instances and the builder returns the exact single root instance.
 * The protected constructor is reserved for scope implementations in this module and package, as required by Kotlin's sealed hierarchy rules.
 * Application code cannot construct or implement this sealed scope.
 */
@StrataDsl
public sealed class UiScope protected constructor() {
    private val ownerThread: Thread = Thread.currentThread()
    private val emittedElements: MutableList<Element> = ArrayList()
    private var active: Boolean = true

    /**
     * Emits one immutable element description.
     * The element is retained by this callback-lifetime scope without being copied, wrapped, registered, or dispatched by component type.
     * The call must run on the scope's constructing thread while its [buildUi] callback is active.
     *
     * @param element immutable description to emit.
     * @throws IllegalStateException when called from another thread or after the callback has completed.
     */
    public fun element(element: Element) {
        checkUsable()
        emittedElements += element
    }

    /**
     * Checks the callback-lifetime and owner-thread capability of this scope.
     *
     * Internal scope extensions use this guard before creating behavior tied to a scope.
     *
     * @throws IllegalStateException when called from another thread or after the callback has completed.
     */
    @JvmSynthetic
    internal fun checkUsable() {
        check(Thread.currentThread() === ownerThread) {
            "UiScope can only be used from its constructing thread."
        }
        check(active) {
            "UiScope cannot be used after its callback has completed."
        }
    }

    /**
     * Copies all descriptions emitted by this scope in declaration order.
     *
     * The copy remains valid after this scope is closed, which releases the scope's references to the descriptions.
     *
     * @return an unmodifiable snapshot for a new parent description.
     */
    @JvmSynthetic
    internal fun childElementsSnapshot(): List<Element> {
        checkUsable()
        return Collections.unmodifiableList(emittedElements.toList())
    }

    /**
     * Validates root cardinality and returns the exact single emitted description.
     *
     * @return the sole emitted element instance.
     * @throws IllegalArgumentException when no element or multiple elements were emitted.
     */
    @JvmSynthetic
    internal fun rootElement(): Element =
        when (emittedElements.size) {
            0 -> throw IllegalArgumentException(
                "buildUi requires exactly one root element, but no elements were emitted.",
            )

            1 -> emittedElements[0]

            else -> throw IllegalArgumentException(
                "buildUi requires exactly one root element, but multiple elements were emitted.",
            )
        }

    /**
     * Closes this scope so later emission attempts fail and releases its temporary element references.
     * The builder invokes this method after normal return, cardinality failure, or callback failure.
     */
    @JvmSynthetic
    internal fun close() {
        active = false
        emittedElements.clear()
    }

    private class RootScope : UiScope()

    /**
     * Internal construction hooks for the top-level builder.
     */
    internal companion object {
        /**
         * Creates the private root implementation used by [buildUi].
         *
         * @return a fresh callback-lifetime scope.
         */
        @JvmSynthetic
        internal fun createRoot(): UiScope = RootScope()
    }
}

/**
 * Builds one immutable root element from a synchronous declarative callback.
 * The callback runs immediately on the calling thread and must emit exactly one root through [UiScope.element].
 * The returned value is the exact emitted instance, not a wrapper or copied description.
 * The scope is closed in all outcomes, including callback failure and cardinality failure.
 * A callback [Throwable] is propagated unchanged and takes precedence over root-count validation.
 *
 * @param content synchronous callback that emits the root description.
 * @return the exact element emitted by the callback.
 * @throws IllegalArgumentException when the callback emits zero or multiple roots.
 * @throws Throwable when the callback fails; the original throwable is propagated unchanged.
 */
public fun buildUi(content: UiScope.() -> Unit): Element {
    val scope = UiScope.createRoot()
    try {
        scope.content()
        return scope.rootElement()
    } finally {
        scope.close()
    }
}
