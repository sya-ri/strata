package dev.s7a.strata.runtime

import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.runtime.platform.identitySet
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.DerivedStateSource
import dev.s7a.strata.state.StateSource

/**
 * One current root or derived observation in a tree-owned dependency graph.
 * Dependency and consumer bookkeeping is owner-thread confined. Root callbacks only enqueue snapshots.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObservedSourceBinding(
    val source: StateSource<*>,
    val upstream: ObservedSourceBinding? = null,
) : AutoCloseable {
    private val derivation = source as? DerivedStateSource<*>
    private val binding = if (derivation == null) UiSessionBinding<Any?>({}, {}, {}) else null
    private var derivedValue: Any? = null

    /**
     * Owner-thread reference count, including repeated source argument positions.
     */
    var references = 0

    /**
     * Current derived dependents; no historical dependency edges are retained.
     */
    val dependents = LinkedHashSet<ObservedSourceBinding>()

    /**
     * Identity-indexed UI consumers notified only when this binding's value changes.
     */
    val consumers: MutableSet<StateObserverNode> = identitySet()

    /**
     * Last frame-committed value; reads are confined to the owning tree.
     */
    val value: Any?
        get() = if (binding == null) derivedValue else binding.committedValue

    init {
        if (binding == null) {
            derivedValue = checkNotNull(derivation).derive(checkNotNull(upstream).value)
        } else {
            val rootBinding = binding
            runCatching {
                val subscription = source.subscribe(rootBinding::enqueue)
                rootBinding.install(subscription)
                rootBinding.commitInitial(subscription.initialSnapshot)
            }.getOrElse { failure ->
                binding.disable()
                val cleanup = binding.closeSubscription()
                if (cleanup != null && cleanup !== failure) failure.addSuppressed(cleanup)
                throw failure
            }
        }
    }

    /**
     * Freezes pending observations without invoking value comparisons.
     */
    fun capture() {
        binding?.capturePending()
    }

    /**
     * Commits only the captured value and reports whether its value changed.
     */
    fun commit(): Boolean = binding?.applyPending() ?: false

    /**
     * Recomputes one derived value after its upstream changed; equal outputs stop propagation.
     */
    fun derive(): Boolean {
        val next = checkNotNull(derivation).derive(checkNotNull(upstream).value)
        val previous = derivedValue
        derivedValue = next
        return previous != next
    }

    override fun close() {
        derivedValue = null
        dependents.clear()
        consumers.clear()
        binding?.disable()
        binding?.closeSubscription()?.let { throw it }
    }
}
