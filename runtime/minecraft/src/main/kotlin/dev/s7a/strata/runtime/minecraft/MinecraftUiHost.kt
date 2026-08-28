package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Owner-thread bridge for one Minecraft screen definition host.
 *
 * Lifecycle, frame, input, and close calls are synchronous and must run on the construction thread.
 * The host retains its independent core runtime session, transferred metadata, one-shot evaluator until evaluation or terminal release, and any version services transferred by an adapter until terminal release, but does not directly retain the definition object.
 * The evaluator temporarily retains the complete profile and every caller object, including a definition reference, captured by application content.
 * Content evaluates during the first [attach], while later synchronous changes use retained node invalidation before another [frame].
 * The host exposes no coroutine, local-state, source-binding, resource, rendering, or version-specific contract.
 * Core lifecycle, first-frame input gating, failure identity, suppression order, and cleanup behavior are delegated unchanged.
 * Every factory call creates distinct retained ownership with referential identity.
 */
@InternalStrataRuntimeApi
public sealed interface MinecraftUiHost : AutoCloseable {
    /**
     * Exact unresolved title transferred from the one-shot definition.
     *
     * @throws IllegalStateException when read from another thread, reentrantly, or after terminal failure or close.
     */
    public val title: UiText

    /**
     * Whether the transferred screen pauses the game while active.
     *
     * @throws IllegalStateException when read from another thread, reentrantly, or after terminal failure or close.
     */
    public val pausesGame: Boolean

    /**
     * Detached identity of the current editable focus interval, or null without committed editable focus.
     *
     * Created and detached hosts return null, and reattachment requires a successful frame before publishing an interval.
     * Adapters synchronize native input-method focus only after a host operation returns; native notifications may synchronously deliver preedit.
     * The token retains no host, node, or native references.
     *
     * @throws IllegalStateException when read from another thread, reentrantly, or after terminal failure or close.
     */
    public val textInputFocus: RuntimeTextInputFocus?

    /**
     * Attaches this host on its owner thread.
     *
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or from an invalid lifecycle state.
     * @throws Throwable when content, reconciliation, lifecycle, or cleanup fails; the exact first failure remains primary with later distinct failures suppressed.
     */
    public fun attach()

    /**
     * Detaches this host on its owner thread while retaining the core tree for reattachment.
     *
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while the host is not attached.
     */
    public fun detach()

    /**
     * Produces one immutable frame for the supplied viewport.
     *
     * The viewport is converted to fixed constraints, including zero dimensions.
     *
     * @param viewport the non-negative logical viewport.
     * @return the immutable core frame snapshot.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while the host is not attached.
     * @throws Throwable when the frame pipeline or cleanup fails; the exact first failure remains primary with later distinct failures suppressed.
     */
    public fun frame(viewport: IntSize): RuntimeUiFrame

    /**
     * Produces one immutable frame with an explicit host-clock timestamp for animated nodes.
     *
     * @param viewport non-negative logical viewport.
     * @param time timestamp from the owning platform clock.
     * @return immutable core frame snapshot.
     */
    public fun frame(
        viewport: IntSize,
        time: FrameTime,
    ): RuntimeUiFrame

    /**
     * Dispatches one pointer event through the most recently committed frame.
     *
     * Hover observers and hover-aware components update only from delivered [PointerEvent.Move] events; a stationary pointer has no implicit hover transition.
     * Active modifiers decide whether raw, press, release, move, and scroll events are consumed or continue through earlier painted candidates.
     * Consecutive events do not require an intervening [frame]: core synchronizes dirty retained geometry using the last committed viewport.
     * Source application, content rebuilding, animation, painting, and semantics still wait for the next frame.
     *
     * @param event the pointer event in host coordinates.
     * @return the retained core input result.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while the host is not attached.
     * @throws Throwable when pointer dispatch or cleanup fails; the exact first failure remains primary with later distinct failures suppressed.
     */
    public fun dispatchPointer(event: PointerEvent): InputResult

    /**
     * Dispatches one keyboard event to the focused component in the most recently committed frame.
     * Dirty retained geometry synchronizes as described by [dispatchPointer].
     *
     * @param event immutable typed key event.
     * @return the retained focused-input result, or [InputResult.Ignored] without a committed frame or focus target.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while detached.
     * @throws Throwable when focused behavior or cleanup fails; the exact first failure remains primary.
     */
    public fun dispatchKeyboard(event: KeyboardEvent): InputResult

    /**
     * Dispatches one committed-character or input-method preedit event to the focused component in the most recently committed frame.
     * Dirty retained geometry synchronizes as described by [dispatchPointer].
     *
     * @param event immutable typed text-input event.
     * @return the retained focused-input result, or [InputResult.Ignored] without a committed frame or focus target.
     * @throws IllegalStateException when called from the wrong thread, reentrantly, or while detached.
     * @throws Throwable when focused behavior or cleanup fails; the exact first failure remains primary.
     */
    public fun dispatchTextInput(event: TextInputEvent): InputResult

    /**
     * Cancels captured pointer input and clears hover and focus when the native window loses input ownership.
     *
     * The owner-thread adapter invokes this for window blur or an explicit input reset while the host remains attached.
     * The retained tree and immutable frame remain available, and repeated resets have no further transition to deliver.
     *
     * @throws IllegalStateException when called from another thread, reentrantly, or while the host is not attached.
     * @throws Throwable when input cleanup fails; the exact primary failure is preserved while remaining host cleanup is attempted.
     */
    public fun resetInputState()

    /**
     * Closes this host on its owner thread.
     *
     * Close is idempotent after completion and preserves the core session's exact cleanup failure behavior.
     *
     * @throws IllegalStateException when called from the wrong thread or from another active operation; recursive close from close cleanup is a no-op.
     * @throws Throwable when cleanup fails; the exact first failure remains primary with later distinct failures suppressed.
     */
    override fun close()
}
