package dev.s7a.strata.runtime.minecraft

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Coordinates owner-thread hover identity for all pointer buttons in one host.
 *
 * A move transaction starts empty, receives every enabled hit from core reverse hit testing, and commits an identity diff after dispatch.
 * Entered and exited live nodes invalidate only their paint phase; terminal abandonment releases references without invalidating nodes.
 */
internal class MinecraftButtonHoverCoordinator private constructor() {
    /**
     * Private node capability used to keep hover identity independent from concrete element storage.
     */
    internal interface Target {
        /**
         * Reports whether this target can enter the current hover set.
         *
         * @return true when the target is enabled and live.
         */
        @JvmSynthetic
        fun isEnabledForHover(): Boolean

        /**
         * Applies one committed hover value to the live target.
         *
         * @param value whether the target is hovered.
         */
        @JvmSynthetic
        fun setHoveredFromCoordinator(value: Boolean)
    }

    private val ownerThread = Thread.currentThread()
    private val current: MutableSet<Target> = identitySet()
    private val candidate: MutableSet<Target> = identitySet()
    private var moving = false
    private var active = true

    /**
     * Begins one owner-thread move transaction with an empty candidate set.
     *
     * @throws IllegalStateException when called from another thread, after abandonment, or while a move is active.
     */
    @JvmSynthetic
    internal fun beginMove() {
        checkOwner()
        check(active) { "Minecraft button hover coordination is closed." }
        check(moving.not()) { "Minecraft button hover coordination is already moving." }
        candidate.clear()
        moving = true
    }

    /**
     * Offers one enabled live button hit to the active move transaction.
     *
     * @param node hit button node owned by this coordinator.
     * @throws IllegalStateException when called from another thread or outside a move transaction.
     */
    @JvmSynthetic
    internal fun offer(node: Target) {
        checkOwner()
        check(active) { "Minecraft button hover coordination is closed." }
        check(moving) { "Minecraft button hover coordination has no active move." }
        if (node.isEnabledForHover()) {
            candidate += node
        }
    }

    /**
     * Commits the candidate identity set and invalidates live entered or exited nodes.
     *
     * @throws IllegalStateException when called from another thread or outside a move transaction.
     */
    @JvmSynthetic
    internal fun finishMove() {
        checkOwner()
        check(active) { "Minecraft button hover coordination is closed." }
        check(moving) { "Minecraft button hover coordination has no active move." }
        current.forEach { node ->
            if (candidate.contains(node).not()) {
                node.setHoveredFromCoordinator(false)
            }
        }
        candidate.forEach { node ->
            if (current.contains(node).not()) {
                node.setHoveredFromCoordinator(true)
            }
        }
        current.clear()
        current.addAll(candidate)
        candidate.clear()
        moving = false
    }

    /**
     * Clears current hover while the retained tree is still bound.
     *
     * @throws IllegalStateException when called from another thread or after abandonment.
     */
    @JvmSynthetic
    internal fun clearHover() {
        checkOwner()
        check(active) { "Minecraft button hover coordination is closed." }
        current.forEach { node -> node.setHoveredFromCoordinator(false) }
        current.clear()
        candidate.clear()
        moving = false
    }

    /**
     * Forgets one node without invalidating it, used by updates and terminal disposal.
     *
     * @param node node whose identity is no longer retained by this coordinator.
     * @throws IllegalStateException when called from another thread.
     */
    @JvmSynthetic
    internal fun forget(node: Target) {
        checkOwner()
        current.remove(node)
        candidate.remove(node)
    }

    /**
     * Abandons the coordinator and releases all node references without further invalidation.
     *
     * @throws IllegalStateException when called from another thread.
     */
    @JvmSynthetic
    internal fun abandon() {
        checkOwner()
        active = false
        moving = false
        current.clear()
        candidate.clear()
    }

    private fun checkOwner() {
        check(Thread.currentThread() === ownerThread) { "Minecraft button hover coordination requires its owner thread." }
    }

    private fun <T : Any> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * Owns the private construction entry point for one host coordinator.
     */
    companion object {
        /**
         * Creates one owner-thread coordinator for one host.
         *
         * @return a new empty hover coordinator.
         */
        @JvmSynthetic
        internal fun create(): MinecraftButtonHoverCoordinator = MinecraftButtonHoverCoordinator()
    }
}
