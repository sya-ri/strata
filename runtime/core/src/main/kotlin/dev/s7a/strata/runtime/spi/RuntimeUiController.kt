package dev.s7a.strata.runtime.spi

import dev.s7a.strata.runtime.platform.currentThread
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus

/**
 * Owner-thread control state shared by native and remote sessions.
 * Drivers acknowledge completion with [applied] or [rejected]; returning from the driver is not an acknowledgement.
 * [transaction] defers controls until the outer event/lifecycle boundary returns.
 * Driver and cleanup captures are released before terminal cleanup calls user code.
 */
@InternalStrataRuntimeApi
@Suppress("TooManyFunctions") // One owner coordinates requests, acknowledgements, event boundaries, and terminal cleanup.
public class RuntimeUiController(
    presentation: UiPresentation,
    inputPolicy: UiInputPolicy = UiInputPolicy.BlockAll,
    apply: (RuntimeUiControl) -> Unit,
    close: (UiCloseReason) -> Unit,
) : UiSession {
    private val owner = currentThread()
    private var driver: ((RuntimeUiControl) -> Unit)? = apply
    private var cleanup: ((UiCloseReason) -> Unit)? = close
    private var desired = RuntimeUiControl(0, presentation, inputPolicy, initialInteraction(presentation))
    private var acknowledged: RuntimeUiControl? = null
    private var submitted: RuntimeUiControl? = null
    private var nextSequence = 1L
    private var depth = 0
    private var draining = false
    private var started = false
    private var dirty = true
    private var terminal: UiCloseReason? = null
    private var currentStatus: UiSessionStatus = UiSessionStatus.Opening

    override val presentation: UiPresentation?
        get() {
            checkOwner()
            return acknowledged?.presentation
        }

    override val status: UiSessionStatus
        get() {
            checkOwner()
            return currentStatus
        }

    override val inputPolicy: UiInputPolicy
        get() {
            checkOwner()
            return acknowledged?.inputPolicy ?: desired.inputPolicy
        }

    override val interactionMode: UiInteractionMode
        get() {
            checkOwner()
            return if (terminal != null) UiInteractionMode.None else acknowledged?.interactionMode ?: UiInteractionMode.None
        }

    /**
     * Begins the initial request after the driver and its native ownership have been installed.
     */
    public fun start() {
        checkOwner()
        if (started || terminal != null) return
        started = true
        drain()
    }

    override fun switch(presentation: UiPresentation): UiOperationResult =
        request {
            if (it.presentation == presentation) it else it.copy(presentation = presentation, interactionMode = initialInteraction(presentation))
        }

    override fun setInputPolicy(policy: UiInputPolicy): UiOperationResult = request { it.copy(inputPolicy = policy) }

    override fun setInteractionMode(mode: UiInteractionMode): UiOperationResult = request { it.copy(interactionMode = mode) }

    private fun request(change: (RuntimeUiControl) -> RuntimeUiControl): UiOperationResult {
        checkOwner()
        if (terminal != null) return UiOperationResult.Rejected(UiRejection.Closed)
        val replacement = change(desired)
        if (replacement == desired && (acknowledged != null || submitted != null)) return UiOperationResult.Accepted
        desired = replacement
        dirty = true
        if (acknowledged != null) currentStatus = UiSessionStatus.Switching(desired.presentation)
        drain()
        val rejection = (currentStatus as? UiSessionStatus.Ready)?.rejection
        return if (rejection == null) UiOperationResult.Accepted else UiOperationResult.Rejected(rejection)
    }

    /**
     * Acknowledges the most recent request after native wrapper/input changes complete.
     * Older responses, duplicates, and replies after close cannot change any applied state.
     */
    public fun applied(sequence: Long) {
        checkOwner()
        val request = submitted ?: return
        if (terminal != null || request.sequence != sequence || dirty) return
        acknowledged = request
        submitted = null
        currentStatus = UiSessionStatus.Ready()
    }

    /**
     * Rejects the current request, retaining the last applied configuration.
     */
    public fun rejected(
        sequence: Long,
        reason: UiRejection,
    ) {
        checkOwner()
        val request = submitted ?: return
        if (terminal != null || request.sequence != sequence || dirty) return
        submitted = null
        acknowledged?.let { desired = it }
        currentStatus = UiSessionStatus.Ready(reason)
    }

    /**
     * Runs an event or host operation, coalescing requests and prioritizing close at its outer boundary.
     */
    public fun <T> transaction(operation: () -> T): T {
        checkOwner()
        depth++
        val result = runCatching(operation)
        val failure = result.exceptionOrNull()
        if (failure != null) terminal = terminal ?: UiCloseReason.Failed
        depth--
        val cleanupFailure = runCatching(::drain).exceptionOrNull()
        if (failure != null) {
            if (cleanupFailure != null && cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            throw failure
        }
        if (cleanupFailure != null) throw cleanupFailure
        return result.getOrThrow()
    }

    override fun close() {
        terminate(UiCloseReason.Closed)
    }

    /**
     * Ends this handle for a platform lifecycle reason; repeated termination keeps the first reason.
     */
    public fun terminate(reason: UiCloseReason) {
        checkOwner()
        if (terminal != null) return
        terminal = reason
        currentStatus = UiSessionStatus.Closed(reason)
        drain()
    }

    @Suppress("TooGenericExceptionCaught") // Driver failures must release ownership even when application code throws an Error.
    private fun drain() {
        if (depth != 0 || draining) return
        draining = true
        try {
            var transitions = 0
            while (true) {
                val reason = terminal
                if (reason != null) {
                    release(reason)
                    return
                }
                if (started.not() || dirty.not()) return
                if (submitted == null && acknowledged == desired) {
                    dirty = false
                    currentStatus = UiSessionStatus.Ready()
                    return
                }
                check(transitions++ < 64) { "Too many UI transitions in one event." }
                check(nextSequence < Long.MAX_VALUE) { "UI control sequence is exhausted." }
                val request = desired.copy(sequence = nextSequence++)
                submitted = request
                desired = request
                dirty = false
                currentStatus = if (acknowledged == null) UiSessionStatus.Opening else UiSessionStatus.Switching(request.presentation)
                checkNotNull(driver).invoke(request)
            }
        } catch (failure: Throwable) {
            val reason = terminal ?: UiCloseReason.Failed
            terminal = reason
            runCatching { release(reason) }.exceptionOrNull()?.let { cleanupFailure ->
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        } finally {
            draining = false
        }
    }

    private fun release(reason: UiCloseReason) {
        currentStatus = UiSessionStatus.Closed(reason)
        driver = null
        submitted = null
        dirty = false
        val release = cleanup
        cleanup = null
        release?.invoke(reason)
    }

    private fun checkOwner() {
        check(currentThread() === owner) { "UI controls require their owner thread." }
    }

    private companion object {
        fun initialInteraction(presentation: UiPresentation): UiInteractionMode =
            when (presentation) {
                UiPresentation.Screen -> UiInteractionMode.Cursor
                UiPresentation.Hud -> UiInteractionMode.None
            }
    }
}
