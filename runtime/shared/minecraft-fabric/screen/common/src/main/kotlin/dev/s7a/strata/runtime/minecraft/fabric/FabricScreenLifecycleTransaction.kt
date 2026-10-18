package dev.s7a.strata.runtime.minecraft.fabric

import java.util.ArrayDeque

/**
 * Serializes creator-thread native lifecycle changes requested while common host work is active.
 * Attach and detach preserve declaration order; terminal close supersedes pending nonterminal work.
 * Close releases attach, detach and close callbacks before invocation; navigation survives until its independent attempt.
 * Reentrant host work and cross-thread requests are rejected, and later distinct failures are suppressed on the first failure.
 */
@Suppress("TooManyFunctions") // Deferred transitions share one creator-thread queue and terminal cutoff.
internal class FabricScreenLifecycleTransaction(
    attach: () -> Unit,
    detach: () -> Unit,
    close: () -> Unit,
    navigate: () -> Unit,
) {
    private val ownerThread = Thread.currentThread()
    private val pending = ArrayDeque<Action>()
    private var attachCallback: (() -> Unit)? = attach
    private var detachCallback: (() -> Unit)? = detach
    private var closeCallback: (() -> Unit)? = close
    private var navigateCallback: (() -> Unit)? = navigate
    private var active = false
    private var terminalRequested = false
    private var closeAttempted = false
    private var navigationRequested = false
    private var navigationAttempted = false

    private enum class Action { ATTACH, DETACH, CLOSE, CLOSE_THEN_NAVIGATE, NAVIGATE }

    /**
     * Reports active host work or deferred callbacks after validating the creator thread.
     */
    fun isActive(): Boolean {
        requireOwnerThread()
        return active
    }

    /**
     * Runs non-reentrant host work and drains queued transitions before releasing the active guard.
     * Returns its exact result after success, or throws the first failure after every deferred callback is attempted.
     */
    fun <T> run(action: () -> T): T {
        requireOwnerThread()
        check(active.not()) { "Fabric Minecraft screen host operations are non-reentrant." }
        active = true
        val result = runCatching(action)
        val failure =
            try {
                addFailure(result.exceptionOrNull(), drain())
            } finally {
                active = false
            }
        if (failure != null) throw failure
        return result.getOrThrow()
    }

    /**
     * Queues attachment from an active creator-thread transaction and rejects terminal reuse.
     */
    fun requestAttach() {
        requireRequestContext()
        check(terminalRequested.not()) { "A closing Fabric Minecraft screen cannot be added again." }
        enqueueDistinct(Action.ATTACH)
    }

    /**
     * Queues transient detachment unless terminal cleanup already supersedes it.
     */
    fun requestDetach() {
        requireRequestContext()
        if (terminalRequested) return
        enqueueDistinct(Action.DETACH)
    }

    /**
     * Supersedes nonterminal queued work with one cleanup attempt, without navigation.
     */
    fun requestClose() {
        requireRequestContext()
        if (terminalRequested) return
        terminalRequested = true
        pending.clear()
        pending.addLast(Action.CLOSE)
    }

    /**
     * Queues terminal cleanup followed by one independent parent navigation attempt.
     */
    fun requestCloseThenNavigate() {
        requireRequestContext()
        if (navigationRequested || navigationAttempted) return
        navigationRequested = true
        pending.clear()
        if (closeAttempted) {
            pending.addLast(Action.NAVIGATE)
            return
        }
        terminalRequested = true
        pending.addLast(Action.CLOSE_THEN_NAVIGATE)
    }

    /**
     * Reports pending detachment or terminal work that prevents continued use of the old screen.
     */
    fun hasPendingExit(): Boolean {
        requireRequestContext()
        return pending.any { action -> action != Action.ATTACH }
    }

    private fun drain(): Throwable? {
        var primary: Throwable? = null
        while (pending.isNotEmpty()) {
            val action = pending.removeFirst()
            primary = addFailure(primary, runCatching { execute(action) }.exceptionOrNull())
        }
        return primary
    }

    private fun execute(action: Action) {
        when (action) {
            Action.ATTACH -> requireCallback(attachCallback, "attach").invoke()
            Action.DETACH -> requireCallback(detachCallback, "detach").invoke()
            Action.CLOSE -> closeOnce()
            Action.CLOSE_THEN_NAVIGATE -> closeThenNavigate()
            Action.NAVIGATE -> navigateOnce()
        }
    }

    private fun closeThenNavigate() {
        val closed = runCatching { closeOnce() }
        val navigated = runCatching { navigateOnce() }
        val failure = addFailure(closed.exceptionOrNull(), navigated.exceptionOrNull())
        if (failure != null) throw failure
    }

    private fun closeOnce() {
        if (closeAttempted) return
        closeAttempted = true
        terminalRequested = true
        val callback = closeCallback
        attachCallback = null
        detachCallback = null
        closeCallback = null
        requireCallback(callback, "close").invoke()
    }

    private fun navigateOnce() {
        if (navigationAttempted) return
        navigationAttempted = true
        navigationRequested = false
        val callback = navigateCallback
        navigateCallback = null
        requireCallback(callback, "navigate").invoke()
    }

    private fun enqueueDistinct(action: Action) {
        if (pending.peekLast() != action) pending.addLast(action)
    }

    private fun requireRequestContext() {
        requireOwnerThread()
        check(active) { "Fabric Minecraft lifecycle requests require an active host transaction." }
    }

    private fun requireOwnerThread() {
        check(Thread.currentThread() === ownerThread) { "Fabric Minecraft lifecycle transactions are confined to their creator thread." }
    }

    private fun requireCallback(
        callback: (() -> Unit)?,
        name: String,
    ): () -> Unit = checkNotNull(callback) { "Fabric Minecraft $name callback was already released." }

    private fun addFailure(
        primary: Throwable?,
        secondary: Throwable?,
    ): Throwable? {
        if (secondary == null) return primary
        if (primary == null) return secondary
        FabricMinecraftFailures.addSuppressed(primary, secondary)
        return primary
    }
}
