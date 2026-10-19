@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Synchronizes one screen's detached editable-focus identity with native text-input mode on its construction thread.
 * Gain occurs after retained and lifecycle transactions; loss drops identity before its potentially synchronous native callback.
 */
internal class FabricMinecraftTextInputFocus(
    private val notifyNative: (Boolean) -> Unit,
) {
    private val ownerThread = Thread.currentThread()
    private var current: RuntimeTextInputFocus? = null

    /**
     * Reports whether native preedit belongs to an active editable interval after checking the owner thread.
     */
    val isActive: Boolean
        get() {
            checkOwner()
            return current != null
        }

    /**
     * Applies a distinct committed identity, notifying loss before gain when both are non-null.
     * A failed gain drops the identity and attempts native loss before the exact primary failure escapes.
     */
    fun synchronize(focus: RuntimeTextInputFocus?) {
        checkOwner()
        if (current === focus) return
        clear()
        if (focus == null) return
        current = focus
        val failure = runCatching { notifyNative(true) }.exceptionOrNull() ?: return
        val cleanup = runCatching { clear() }.exceptionOrNull()
        if (cleanup != null) FabricMinecraftFailures.addSuppressed(failure, cleanup)
        throw failure
    }

    /**
     * Ends the interval before native loss, making repeated or recursive loss inert even when notification fails.
     */
    fun clear() {
        checkOwner()
        if (current == null) return
        current = null
        notifyNative(false)
    }

    private fun checkOwner() {
        check(Thread.currentThread() === ownerThread) { "Native text-input focus requires its owner thread." }
    }
}
