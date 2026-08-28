package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.spi.InternalStrataRuntimeApi

// Why: this public owner intentionally exposes each retained lifecycle, frame, input, and inspection operation through one guarded boundary.

/**
 * A retained, platform-neutral UI tree.
 *
 * Every operation is confined to the thread that creates this tree.
 * Operational methods and [close] are not reentrant.
 * The [state] property may be read on the owner thread during an active callback.
 * An empty tree measures to [IntSize.Zero].
 * An empty tree performs no layout work and returns empty paint, input, and semantics results.
 * A complete description is validated before mutation.
 * Structural validation and local validation-hook failures leave an active tree unchanged and available for retry.
 * Any failure after validation begins reconciliation, lifecycle work, or a pipeline operation poisons the tree.
 * The tree clears retained ownership and attempts cleanup.
 * Cleanup preserves the primary [Throwable] instance and suppresses later distinct failures on it.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions")
public class UiTree : AutoCloseable {
    private val threadGuard: ThreadGuard = ThreadGuard.currentThread()
    private val dirtyTracker = DirtyTracker()
    private val registry = NodeOwnershipRegistry()
    private val lifecycle = LifecycleManager(registry, threadGuard, dirtyTracker)
    private val reconciler = Reconciler(lifecycle, dirtyTracker)
    private val validator = DescriptionValidator()
    private val pipeline = Pipeline(threadGuard)
    private var currentState: TreeState = TreeState.Active
    private var root: RetainedNode? = null
    private var operationActive: Boolean = false

    /**
     * The current lifecycle state, read on the owning tree thread.
     *
     * Reading this property from another thread fails without changing the tree.
     */
    public val state: TreeState
        get() {
            threadGuard.check()
            return currentState
        }

    /**
     * Returns the detached identity of the current editable focus interval to its owning session.
     *
     * @return the committed editable interval, or null without an accepting editable focus target.
     * @throws IllegalStateException when read from another thread, during a tree operation, or after terminal cleanup.
     */
    @JvmSynthetic
    internal fun currentTextInputFocus(): RuntimeTextInputFocus? {
        threadGuard.check()
        check(operationActive.not()) { "A tree operation is already active." }
        check(currentState === TreeState.Active) { "The retained tree is not active." }
        return pipeline.textInputFocus
    }

    /**
     * Returns the whole-tree change token used by an owning session's frame cache.
     *
     * The token changes when retained phase or structural work is recorded and is read only on the tree's owner thread.
     *
     * @return the current whole-tree change token.
     * @throws IllegalStateException when read from another thread.
     */
    @JvmSynthetic
    internal fun currentRevision(): Long {
        threadGuard.check()
        return dirtyTracker.revision
    }

    /**
     * Delivers one explicit host timestamp to retained time-aware nodes.
     *
     * @param time timestamp from the owning host clock.
     */
    internal fun advanceFrame(time: FrameTime) {
        pipelineOperation {
            root?.let { retainedRoot -> pipeline.advanceFrame(retainedRoot, time) }
        }
    }

    /**
     * Reconciles a complete immutable element description.
     *
     * Validation visits the complete description and runs each element's local validation hook.
     * It rejects duplicate keyed direct siblings before retained state changes.
     * A validation failure leaves the active tree unchanged.
     * Reconciliation reuses compatible nodes, creates detached nodes for additions, and attaches newly installed nodes parent-first.
     * A failure from reconciliation, node creation or update, lifecycle work, or cleanup poisons the tree.
     * The operation rethrows the primary failure after cleanup attempts.
     *
     * @param description the proposed root description.
     * @throws Throwable when a validation hook fails before mutation, or when reconciliation, lifecycle, or cleanup fails after validation.
     * The original throwable is propagated unchanged.
     * @throws IllegalStateException when the operation is called from the wrong thread.
     * It is also thrown when the operation re-enters an active operation or the tree is not active.
     */
    public fun update(description: Element) {
        beginOperation()
        try {
            validator.validate(description)
            runCatching {
                val nextRoot = reconciler.reconcileRoot(root, description)
                root = nextRoot
                reconciler.markInstalled(nextRoot)
                lifecycle.attachPending(nextRoot)
            }.getOrElse { failure -> poison(failure) }
        } finally {
            operationActive = false
        }
    }

    /**
     * Measures the retained tree under [constraints].
     *
     * An empty tree returns [IntSize.Zero].
     * A clean node with equal constraints may reuse its retained size and skip its measure callback.
     * Measurement invalidation reruns local measurement and marks local layout, paint, and semantics work dirty.
     * It propagates measurement work to ancestors without dirtying descendants directly.
     * A callback or scope exception poisons the tree after pipeline work begins only when it escapes the owning callback.
     *
     * @param constraints the root constraints.
     * @return the measured root size, or [IntSize.Zero] when no root is installed.
     * @throws Throwable when a measure callback or scope operation fails after pipeline work begins.
     * The original throwable is propagated unchanged after cleanup attempts.
     * @throws IllegalStateException when the operation is called from the wrong thread.
     * It is also thrown when the operation re-enters an active operation or the tree is not active.
     */
    public fun measure(constraints: Constraints): IntSize =
        pipelineOperation {
            val retainedRoot = root ?: return@pipelineOperation IntSize.Zero
            reconciler.refreshDynamicChildren(retainedRoot, validator)
            lifecycle.attachPending(retainedRoot)
            pipeline.measure(retainedRoot, constraints)
        }

    /**
     * Places every measured node that participates in the current pass.
     *
     * An empty tree performs no work.
     * A non-empty tree must have a completed measure pass with no pending measure work.
     * If that precondition fails, the tree is poisoned.
     * A clean node may skip its layout callback while retained placements are reused.
     * Layout invalidation reruns local placement and marks local paint and semantics work dirty.
     * Ancestor traversal reaches an invalidated node only while that node is currently placed.
     * Descendants are not dirtied by layout invalidation.
     * Unmeasured or unplaced children are excluded from subsequent layout, paint, input, and semantics work.
     *
     * @throws IllegalStateException when the root has not been measured or measurement remains pending.
     * It is also thrown when the operation is called from the wrong thread, re-enters an active operation, or the tree is not active.
     * @throws Throwable when a layout callback or scope operation throws after pipeline work begins and the exception escapes the callback.
     * The original throwable is propagated unchanged after cleanup attempts.
     */
    public fun layout() {
        pipelineOperation {
            val retainedRoot = root ?: return@pipelineOperation
            pipeline.requireMeasuredRoot(retainedRoot)
            pipeline.layout(retainedRoot)
        }
    }

    /**
     * Paints the laid-out tree in parent-before-child order.
     *
     * An empty tree returns an empty immutable list.
     * A non-empty tree must be laid out with no pending measurement or layout work.
     * If that precondition fails, the tree is poisoned.
     * A clean node reuses its complete local display list and combines it with current accumulated coordinates.
     * Commands preserve local emission order.
     * The core applies no implicit node or parent clipping.
     * Valid local overflow outside those bounds is retained.
     *
     * @return an immutable list of retained commands with bounds in accumulated tree coordinates.
     * @throws IllegalStateException when layout is incomplete or geometry remains pending.
     * It is also thrown when the operation is called from the wrong thread, re-enters an active operation, or the tree is not active.
     * @throws Throwable when a paint callback or scope operation throws after pipeline work begins and the exception escapes the callback.
     * The original throwable is propagated unchanged after cleanup attempts.
     */
    public fun paint(): List<DrawCommand> =
        pipelineOperation {
            val retainedRoot = root ?: return@pipelineOperation emptyList()
            pipeline.requirePlacedRoot(retainedRoot)
            pipeline.paint(retainedRoot)
        }

    /**
     * Dispatches [event] to the reverse paint-order hit path.
     *
     * An empty tree returns [InputResult.Ignored].
     * A non-empty tree must be laid out with no pending measurement or layout work.
     * If that precondition fails, the tree is poisoned.
     * Hit testing uses half-open accumulated bounds without implicit parent clipping.
     * The deepest and latest-painted hit node runs first.
     * [InputResult.Ignored] bubbles to earlier candidates.
     * [InputResult.Consumed] stops dispatch.
     * A callback failure poisons the tree only when it escapes the callback.
     *
     * @param event the event in tree coordinates.
     * @return consumed when a node handled the event, otherwise ignored.
     * @throws IllegalStateException when layout is incomplete or geometry remains pending.
     * It is also thrown when the operation is called from the wrong thread, re-enters an active operation, or the tree is not active.
     * @throws Throwable when a pointer callback fails after pipeline work begins.
     * The original throwable is propagated unchanged after cleanup attempts.
     */
    public fun dispatchPointer(event: PointerEvent): InputResult =
        pipelineOperation {
            val retainedRoot = root ?: return@pipelineOperation InputResult.Ignored
            pipeline.requirePlacedRoot(retainedRoot)
            pipeline.dispatch(retainedRoot, event)
        }

    /**
     * Dispatches [event] to the currently focused logical component.
     *
     * An empty tree or a tree without focus returns [InputResult.Ignored].
     * A non-empty tree must have completed layout with no pending geometry work.
     * Focused component and modifier nodes run from the component node toward outer modifiers until one consumes the event.
     *
     * @param event immutable keyboard event.
     * @return consumed when focused behavior handles the event, otherwise ignored.
     * @throws IllegalStateException when layout is incomplete, the call is from another thread, another operation is active, or the tree is not active.
     * @throws Throwable when focused behavior fails; the original throwable escapes unchanged after cleanup attempts.
     */
    public fun dispatchKeyboard(event: KeyboardEvent): InputResult =
        pipelineOperation {
            val retainedRoot = root ?: return@pipelineOperation InputResult.Ignored
            pipeline.requirePlacedRoot(retainedRoot)
            pipeline.dispatchKeyboard(event)
        }

    /**
     * Dispatches [event] to the currently focused logical component.
     *
     * An empty tree or a tree without focus returns [InputResult.Ignored].
     * A non-empty tree must have completed layout with no pending geometry work.
     * Focused component and modifier nodes run from the component node toward outer modifiers until one consumes the event.
     *
     * @param event immutable committed-character or preedit event.
     * @return consumed when focused behavior handles the event, otherwise ignored.
     * @throws IllegalStateException when layout is incomplete, the call is from another thread, another operation is active, or the tree is not active.
     * @throws Throwable when focused behavior fails; the original throwable escapes unchanged after cleanup attempts.
     */
    public fun dispatchTextInput(event: TextInputEvent): InputResult =
        pipelineOperation {
            val retainedRoot = root ?: return@pipelineOperation InputResult.Ignored
            pipeline.requirePlacedRoot(retainedRoot)
            pipeline.dispatchTextInput(event)
        }

    /**
     * Clears hover and focused ownership before an internal retained session detaches without disposing this tree.
     *
     * The operation is owner-thread confined and uses the most recently committed placement bounds even when later geometry became dirty.
     * It invokes capable placed nodes in deepest/latest-painted-first order and retains the tree for reattachment.
     *
     * @throws IllegalStateException when the call is from another thread, another operation is active, or this tree is not active.
     * @throws Throwable when a hover or focus callback fails; the exact failure remains primary while the tree is poisoned and cleaned.
     */
    @JvmSynthetic
    internal fun clearInputState() {
        pipelineOperation {
            val retainedRoot = root ?: return@pipelineOperation
            pipeline.clearInputState(retainedRoot)
        }
    }

    /**
     * Collects unresolved semantics in parent-before-child order.
     *
     * An empty tree returns an empty immutable list.
     * A non-empty tree must be laid out with no pending measurement or layout work.
     * If that precondition fails, the tree is poisoned.
     * A clean node reuses its complete local semantics payload and combines it with current accumulated bounds.
     * Semantics invalidation affects only that node's local payload.
     * Text remains unresolved for the platform adapter.
     * Callback failures poison the tree.
     *
     * @return an immutable list of entries with accumulated tree-coordinate bounds.
     * @throws IllegalStateException when layout is incomplete or geometry remains pending.
     * It is also thrown when the operation is called from the wrong thread, re-enters an active operation, or the tree is not active.
     * @throws Throwable when a semantics callback or scope operation throws after pipeline work begins.
     * The exception must escape the callback.
     * The original throwable is propagated unchanged after cleanup attempts.
     */
    public fun semantics(): List<SemanticsEntry> =
        pipelineOperation {
            val retainedRoot = root ?: return@pipelineOperation emptyList()
            pipeline.requirePlacedRoot(retainedRoot)
            pipeline.semantics(retainedRoot)
        }

    /**
     * Cleans all retained ownership in descendant-first order.
     *
     * Close is owner-thread confined and non-reentrant.
     * It records [TreeState.Closed] before any cleanup callback.
     * It clears retained ownership and pipeline-held references before cleanup and remains closed even when cleanup fails.
     * Cleanup visits descendants before parents and later siblings before earlier siblings.
     * Cleanup attempts detach only after an attach attempt.
     * Cleanup disposes every claimed node.
     * Cleanup continues after failures and rethrows the first [Throwable] instance with later distinct failures suppressed.
     * A repeated close after the operation has returned is a no-op.
     *
     * @throws IllegalStateException when the operation is called from the wrong thread or re-enters an active operation.
     * @throws Throwable when a cleanup callback fails.
     * The first cleanup throwable is propagated unchanged after all cleanup attempts.
     */
    override fun close() {
        threadGuard.check()
        check(operationActive.not()) { "A tree operation is already active." }
        if (currentState === TreeState.Closed) {
            return
        }
        operationActive = true
        try {
            currentState = TreeState.Closed
            val capturedRoot = root
            root = null
            pipeline.releaseRetainedReferences()
            registry.clear()
            val failures = FailureAccumulator()
            if (capturedRoot != null) {
                failures.addOptional(lifecycle.cleanup(capturedRoot))
            }
            failures.addOptional(reconciler.cleanupProvisionals())
            failures.throwIfPresent()
        } finally {
            operationActive = false
        }
    }

    private fun beginOperation() {
        threadGuard.check()
        check(operationActive.not()) { "A tree operation is already active." }
        check(currentState === TreeState.Active) { "The retained tree is not active." }
        operationActive = true
    }

    private inline fun <T> pipelineOperation(block: () -> T): T {
        beginOperation()
        try {
            return runCatching(block).getOrElse { failure -> poison(failure) }
        } finally {
            operationActive = false
        }
    }

    private fun poison(failure: Throwable): Nothing {
        currentState = TreeState.Poisoned
        val capturedRoot = root
        root = null
        pipeline.releaseRetainedReferences()
        registry.clear()
        val failures = FailureAccumulator(failure)
        if (capturedRoot != null) {
            failures.addOptional(lifecycle.cleanup(capturedRoot))
        }
        failures.addOptional(reconciler.cleanupProvisionals())
        failures.throwFirst()
    }
}
