package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.concurrent.atomic.AtomicReference

/**
 * Base retained node with phase invalidation only.
 *
 * A newly constructed node is detached and unowned.
 * The runtime may claim it once, keeps that claim through detachment and disposal, and retires it permanently after cleanup.
 * Concrete behavior is expressed by capability interfaces.
 * Nodes acquire external resources from lifecycle attachment, never from their constructors.
 */
public abstract class Node {
    private val binding = AtomicReference<BindingState>(BindingState.Unclaimed)

    /**
     * Marks affected phases dirty for a node-local state change.
     *
     * The runtime callback enforces owner-thread validation before recording the mask.
     * That callback also rejects invalidation after cleanup has started.
     * This includes the interval while [LifecycleNode.detach] or [LifecycleNode.dispose] is running.
     *
     * Invalidation is legal only while this node is runtime-bound and cleanup has not started.
     * It throws before binding, during cleanup, and after disposal.
     *
     * @param mask phases affected by the state change.
     * @throws IllegalStateException when this node is not runtime-bound or cleanup has started.
     * It is also thrown when the runtime callback rejects the calling thread.
     */
    protected fun invalidate(mask: DirtyMask) {
        val active = binding.get() as? ActiveBinding
        checkNotNull(active) { "Node invalidation requires a runtime-bound node." }.callback.invoke(mask)
    }

    /**
     * Binds this never-owned node to a runtime invalidation callback.
     *
     * The privileged runtime invokes the returned idempotent release only after disposal has been attempted.
     * Release retires this node permanently, and another runtime claim is impossible after retirement.
     *
     * @param callback the runtime callback that records invalidation and validates its thread.
     * @return an idempotent release function that retires this node after disposal is attempted.
     * @throws IllegalStateException when this node is already bound or retired.
     */
    @InternalStrataRuntimeApi
    public fun bindRuntime(callback: (DirtyMask) -> Unit): () -> Unit {
        val active = ActiveBinding(callback)
        check(binding.compareAndSet(BindingState.Unclaimed, active)) {
            "Node is already bound or retired."
        }
        return {
            binding.compareAndSet(active, BindingState.Retired)
        }
    }

    private sealed interface BindingState {
        /**
         * A node that has never been bound.
         */
        data object Unclaimed : BindingState

        /**
         * A node whose lifecycle and invalidation binding has permanently ended.
         */
        data object Retired : BindingState
    }

    /**
     * The one active callback claim for a node.
     *
     * @property callback runtime invalidation callback.
     */
    private class ActiveBinding(
        val callback: (DirtyMask) -> Unit,
    ) : BindingState
}
