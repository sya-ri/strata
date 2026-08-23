package dev.s7a.strata.runtime.spi

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Narrow owner-thread bridge for a retained runtime UI session.
 *
 * The construction thread owns this session, and lifecycle and frame operations are synchronous on that thread.
 * Operations reject reentrancy except that recursive [close] from its own cleanup is an idempotent no-op.
 * The first successful [frame] gates pointer dispatch.
 * This synchronous bridge does not expose local-state declarations, source bindings, or task launch.
 * Its content is evaluated during the first [attach], while later synchronous changes are driven by retained node invalidation before another [frame].
 * The bridge retains the content lambda while created, attached, or detached and releases it before cleanup callbacks after failure or close.
 *
 * Content, reconciliation, pipeline, input, and cleanup failures preserve the exact primary [Throwable] and suppression order defined by the core session.
 * A failed session has already performed cleanup; closing it records the closed terminal state without repeating cleanup.
 */
@InternalStrataRuntimeApi
public sealed interface RuntimeUiSession : AutoCloseable {
    /**
     * Attaches this session on its owner thread.
     *
     * A created or detached session becomes attached, and the first attach evaluates and reconciles the content once.
     * Attachment starts a new frame lifetime, so the first frame after reattachment reruns the retained pipeline even when content and constraints are unchanged.
     * Invalid lifecycle transitions leave state unchanged; content and reconciliation failures poison the session and perform cleanup before rethrowing the exact primary failure.
     *
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or from an invalid lifecycle state.
     * @throws Throwable when content, reconciliation, lifecycle, or cleanup fails; the first failure remains primary with later distinct failures suppressed.
     */
    public fun attach()

    /**
     * Detaches this attached session on its owner thread while retaining its tree and content ownership.
     *
     * Reattachment preserves the retained tree and requires another successful frame before input resumes.
     * Detachment releases the last immutable frame and resets layout-dependent input ownership.
     * Invalid lifecycle transitions leave state unchanged.
     *
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or when the session is not attached.
     */
    public fun detach()

    /**
     * Produces one immutable frame synchronously on the owner thread.
     *
     * Pending content changes are reconciled before measurement, layout, paint, and semantics run in order on the retained tree.
     * When content was not rebuilt and both constraints and the whole-tree revision are unchanged, implementations may return the same immutable frame instance without rerunning the pipeline.
     * The returned frame owns detached unmodifiable snapshots and does not retain the session or source collections.
     * A pipeline failure poisons the session and performs cleanup before rethrowing the exact primary failure.
     *
     * @param constraints the root measurement constraints for this frame.
     * @return an immutable frame snapshot from the successful owner-thread pass.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while the session is not attached.
     * @throws Throwable when content, reconciliation, measurement, layout, paint, semantics, or cleanup fails; the first failure remains primary with later distinct failures suppressed.
     */
    public fun frame(constraints: Constraints): RuntimeUiFrame

    /**
     * Produces one immutable frame after delivering an explicit monotonic timestamp to time-aware nodes.
     *
     * Equal timestamps are legal, and nodes invalidate output only when their discrete presentation changes.
     *
     * @param constraints root measurement constraints.
     * @param time timestamp from the owning platform clock.
     * @return immutable frame snapshot, potentially the previous instance when no output changed.
     */
    public fun frame(
        constraints: Constraints,
        time: FrameTime,
    ): RuntimeUiFrame

    /**
     * Dispatches one pointer event synchronously through the most recently committed attached tree on the owner thread.
     *
     * Input before the first successful frame, or after a newly reconciled frame has not committed, returns [InputResult.Ignored].
     * A pointer pipeline failure poisons the session and performs cleanup before rethrowing the exact primary failure.
     *
     * @param event the pointer event in session coordinates.
     * @return the retained tree result, or [InputResult.Ignored] when no successful frame is committed.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while the session is not attached.
     * @throws Throwable when pointer dispatch or cleanup fails; the first failure remains primary with later distinct failures suppressed.
     */
    public fun dispatchPointer(event: PointerEvent): InputResult

    /**
     * Dispatches one keyboard event synchronously to the focused component in the most recently committed frame.
     *
     * @param event immutable keyboard event.
     * @return focused retained result, or [InputResult.Ignored] without a committed frame or focus target.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while detached.
     * @throws Throwable when focused behavior or cleanup fails; the first failure remains primary.
     */
    public fun dispatchKeyboard(event: KeyboardEvent): InputResult

    /**
     * Dispatches one committed-character or preedit event synchronously to the focused component in the most recently committed frame.
     *
     * @param event immutable text-input event.
     * @return focused retained result, or [InputResult.Ignored] without a committed frame or focus target.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while detached.
     * @throws Throwable when focused behavior or cleanup fails; the first failure remains primary.
     */
    public fun dispatchTextInput(event: TextInputEvent): InputResult

    /**
     * Closes this session synchronously on its owner thread.
     *
     * Close is idempotent after completion and transitions to closed before finalizer work begins.
     * Cleanup visits bindings and retained tree ownership exactly once, continues after failures, and rethrows the exact first cleanup failure with later distinct failures suppressed.
     * Closing a failed session only records the closed state because failure cleanup already ran.
     *
     * @throws IllegalStateException when called from the wrong thread or from another active operation; recursive close from close cleanup is a no-op.
     * @throws Throwable when finalizer cleanup fails; the first failure remains primary with later distinct failures suppressed.
     */
    override fun close()
}
