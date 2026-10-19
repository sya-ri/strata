@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one asynchronous skin lookup completion and the immutable snapshot committed by its client-thread binding.
 * Only [publish] may run off the owner thread; close makes every later completion inert and drops queued and retained results.
 */
internal class FabricMinecraftSkinBindingLifecycle {
    /**
     * One terminal result from the asynchronous native skin lookup.
     */
    sealed interface SkinCompletion

    /**
     * A resolved descriptor retained until its owner-thread snapshot or terminal release.
     */
    class SkinReady(
        val skin: FabricMinecraftSkinReference,
    ) : SkinCompletion

    /**
     * A lookup that completed without a usable native skin.
     */
    data object SkinFailed : SkinCompletion

    private sealed interface State

    private data object LookingUp : State

    private class Completed(
        val completion: SkinCompletion,
    ) : State

    private data object Resolved : State

    private data object Closed : State

    private val state = AtomicReference<State>(LookingUp)
    private var snapshot: MinecraftPlayerSkinBinding.Snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending

    /**
     * Atomically admits the sole asynchronous completion while lookup ownership remains active.
     */
    fun publish(completion: SkinCompletion): Boolean = state.compareAndSet(LookingUp, Completed(completion))

    /**
     * Transfers an admitted completion to the owner exactly once, or returns null when pending, drained or closed.
     */
    fun drainCompletion(): SkinCompletion? {
        val current = state.get()
        return if (current is Completed && state.compareAndSet(current, Resolved)) current.completion else null
    }

    /**
     * Commits a distinct immutable owner-thread snapshot only after an active completion was drained.
     */
    fun commitSnapshot(next: MinecraftPlayerSkinBinding.Snapshot): Boolean {
        if (state.get() !== Resolved || snapshot == next) return false
        snapshot = next
        return true
    }

    /**
     * Borrows the latest owner-thread snapshot, which terminal release resets to pending.
     */
    fun retainedSnapshot(): MinecraftPlayerSkinBinding.Snapshot = snapshot

    /**
     * Abandons lookup ownership once and drops every queued descriptor and committed snapshot.
     */
    fun close(): Boolean {
        if (state.getAndSet(Closed) === Closed) return false
        snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending
        return true
    }

    /**
     * Reports the permanent terminal cutoff without changing ownership.
     */
    val isClosed: Boolean get() = state.get() === Closed
}
