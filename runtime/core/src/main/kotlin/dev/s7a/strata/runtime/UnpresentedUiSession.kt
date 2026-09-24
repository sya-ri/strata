package dev.s7a.strata.runtime

import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus

/**
 * Event receiver for an engine/tree used without a presentation adapter; terminal handles release their owner.
 */
internal class UnpresentedUiSession(
    close: () -> Unit,
) : UiSession {
    private val thread = ThreadGuard()
    private var release: (() -> Unit)? = close
    private var terminal: UiCloseReason? = null
    override val presentation: UiPresentation? get() = null
    override val status: UiSessionStatus get() = terminal?.let(UiSessionStatus::Closed) ?: UiSessionStatus.Ready()
    override val inputPolicy: UiInputPolicy get() = UiInputPolicy.BlockAll
    override val interactionMode: UiInteractionMode get() = UiInteractionMode.None

    override fun switch(presentation: UiPresentation): UiOperationResult = unsupported()

    override fun setInputPolicy(policy: UiInputPolicy): UiOperationResult = unsupported()

    override fun setInteractionMode(mode: UiInteractionMode): UiOperationResult = unsupported()

    override fun close() {
        thread.check()
        val cleanup = release ?: return
        finish(UiCloseReason.Closed)
        cleanup()
    }

    /**
     * Retires the callback before terminal node notifications can invoke application code.
     */
    fun finish(reason: UiCloseReason) {
        thread.check()
        terminal = terminal ?: reason
        release = null
    }

    private fun unsupported(): UiOperationResult {
        thread.check()
        return UiOperationResult.Rejected(if (terminal == null) UiRejection.Unsupported else UiRejection.Closed)
    }
}
