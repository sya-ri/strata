package dev.s7a.strata.runtime

import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns node binding, parent-first attachment, and descendant-first cleanup.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class LifecycleManager(
    private val registry: NodeOwnershipRegistry,
    private val threadGuard: ThreadGuard,
    private val dirtyTracker: DirtyTracker,
) {
    /**
     * Binds [retained] immediately after its creation and claims its node identity.
     *
     * @param retained the detached retained node to bind.
     * @throws IllegalStateException when the node is already owned or retired.
     */
    fun bind(retained: RetainedEntry) {
        registry.claim(retained.node)
        val binding =
            runCatching {
                retained.node.bindRuntime { mask ->
                    threadGuard.check()
                    check(retained.cleanupStarted.not()) { "Node invalidation is unavailable during cleanup." }
                    dirtyTracker.record(retained, mask)
                }
            }.onFailure { registry.release(retained.node) }.getOrThrow()
        retained.bindingRelease = binding
    }

    /**
     * Attaches every not-yet-reached component and modifier in parent-first order.
     *
     * @param retained the root of the subtree to attach.
     */
    fun attach(retained: RetainedNode) {
        retained.modifiers.forEach { modifier -> attachModifier(modifier) }
        if (retained.attachAttempted) {
            return
        }
        retained.attachAttempted = true
        val lifecycle = retained.node as? LifecycleNode
        lifecycle?.attach()
        retained.children.forEach(::attach)
    }

    /**
     * Attaches one newly installed modifier without revisiting its component owner.
     *
     * @param retained the modifier to attach.
     */
    fun attachModifier(retained: RetainedModifier) {
        if (retained.attachAttempted) {
            return
        }
        retained.attachAttempted = true
        (retained.node as? LifecycleNode)?.attach()
    }

    /**
     * Attaches newly installed nodes while preserving existing lifecycle state.
     *
     * @param retained the installed root to scan.
     */
    fun attachPending(retained: RetainedNode) {
        if (retained.attachAttempted.not()) {
            attach(retained)
            return
        }
        retained.modifiers.forEach { modifier -> attachModifier(modifier) }
        retained.children.forEach(::attachPending)
    }

    /**
     * Cleans [retained] in reverse declaration order, returning the first failure.
     *
     * Every node is pre-marked before the first callback so invalidation from any cleanup callback is rejected.
     * Binding and identity ownership remain held through detach and dispose, then become permanently retired.
     *
     * @param retained the subtree to clean.
     * @return the first cleanup failure, with later distinct failures suppressed on it.
     */
    fun cleanup(retained: RetainedNode): Throwable? {
        markCleanupStarted(retained)
        val failures = FailureAccumulator()
        cleanupNode(retained, failures)
        return failures.first
    }

    /**
     * Cleans one removed modifier and leaves its component owner untouched.
     *
     * @param retained the removed modifier entry.
     * @return the first cleanup failure, if any.
     */
    fun cleanupModifier(retained: RetainedModifier): Throwable? {
        markCleanupStarted(retained)
        val failures = FailureAccumulator()
        cleanupNode(retained, failures)
        return failures.first
    }

    private fun markCleanupStarted(retained: RetainedNode) {
        retained.cleanupStarted = true
        retained.modifiers.forEach(::markCleanupStarted)
        retained.children.forEach(::markCleanupStarted)
    }

    private fun markCleanupStarted(retained: RetainedModifier) {
        retained.cleanupStarted = true
    }

    private fun cleanupNode(
        retained: RetainedNode,
        failures: FailureAccumulator,
    ) {
        retained.children.asReversed().forEach { child -> cleanupNode(child, failures) }
        cleanupNodeEntry(retained, failures)
        retained.modifiers.asReversed().forEach { modifier -> cleanupNode(modifier, failures) }
    }

    private fun cleanupNode(
        retained: RetainedModifier,
        failures: FailureAccumulator,
    ) {
        cleanupNodeEntry(retained, failures)
    }

    private fun cleanupNodeEntry(
        retained: RetainedEntry,
        failures: FailureAccumulator,
    ) {
        val lifecycle = retained.node as? LifecycleNode
        if (lifecycle != null && retained.attachAttempted && retained.detachAttempted.not()) {
            retained.detachAttempted = true
            failures.capture { lifecycle.detach() }
        }
        if (retained.disposeAttempted.not()) {
            retained.disposeAttempted = true
            if (lifecycle != null) {
                failures.capture { lifecycle.dispose() }
            }
        }
        val release = retained.bindingRelease
        retained.bindingRelease = null
        if (release != null) {
            failures.capture { release() }
        }
        registry.release(retained.node)
    }
}
