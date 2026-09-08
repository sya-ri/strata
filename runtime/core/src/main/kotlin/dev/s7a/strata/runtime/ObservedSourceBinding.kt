package dev.s7a.strata.runtime

import dev.s7a.strata.state.StateSource

/**
 * One shared source subscription retaining committed, pending, and captured revisions plus its subscription carrier until release.
 */
@Suppress("TooGenericExceptionCaught") // Source subscription failure must release every acquired resource before escaping.
internal class ObservedSourceBinding(
    source: StateSource<*>,
) : AutoCloseable {
    private val binding = UiSessionBinding<Any?>({}, {}, {})

    /**
     * Owner-thread reference count, including repeated source argument positions.
     */
    var references = 0

    /**
     * Last frame-committed value; reads are confined to the owning tree.
     */
    val value: Any? by binding

    init {
        try {
            val subscription = source.subscribe(binding::enqueue)
            binding.install(subscription)
            binding.commitInitial(subscription.initialSnapshot)
        } catch (failure: Throwable) {
            binding.disable()
            val cleanup = binding.closeSubscription()
            if (cleanup != null && cleanup !== failure) failure.addSuppressed(cleanup)
            throw failure
        }
    }

    /**
     * Freezes pending observations without invoking value comparisons.
     */
    fun capture() = binding.capturePending()

    /**
     * Commits only the captured value and reports whether its value changed.
     */
    fun commit(): Boolean = binding.applyPending()

    override fun close() {
        binding.disable()
        binding.closeSubscription()?.let { throw it }
    }
}
