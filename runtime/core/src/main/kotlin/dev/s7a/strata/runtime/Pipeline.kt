package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerGesture
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ParentDataModifierNode
import dev.s7a.strata.node.RootOverlayContentNode
import dev.s7a.strata.node.SessionAttachmentNode
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Executes retained measure and layout work through virtual modifier ancestry.
 *
 * Logical component scopes expose logical children only.
 * Each modifier scope exposes exactly one virtual child.
 */
@Suppress("TooManyFunctions")
@OptIn(InternalStrataRuntimeApi::class)
internal class Pipeline(
    private val ownerGuard: OwnerGuard,
    private val dirtyTracker: DirtyTracker = DirtyTracker(),
    private val prepareChildren: (RetainedNode, Constraints) -> Unit = { _, _ -> },
) {
    private val frameCallbacks = FrameCallbackIndex()
    private val paintPipeline = PaintPipeline(ownerGuard)
    private val focusedInputPipeline = FocusedInputPipeline(dirtyTracker)
    private val inputPipeline = InputPipeline(focusedInputPipeline)
    private val semanticsPipeline = SemanticsPipeline(ownerGuard, focusedInputPipeline)

    /**
     * Detached current editable-focus identity, read outside a tree operation under its execution owner.
     */
    val textInputFocus: RuntimeTextInputFocus?
        get() = focusedInputPipeline.textInputFocus

    /**
     * Measures [root] under [constraints].
     *
     * @param root the installed logical root.
     * @param constraints the root constraints.
     * @return the measured effective root size.
     */
    fun measure(
        root: RetainedNode,
        constraints: Constraints,
    ): IntSize = measureEntry(root.effectiveRoot, constraints)

    /**
     * Lays out [root] after its effective measure pass.
     *
     * @param root the installed logical root.
     */
    fun layout(root: RetainedNode) {
        val effective = root.effectiveRoot
        val transformChanged = effective.localToTree != TreeTransform.Identity
        effective.transformFromParent = ChildTransform.Identity
        effective.localToTree = TreeTransform.Identity
        effective.bounds = effective.localToTree.enclosing(effective.measuredSize)
        effective.placed = true
        layoutEntry(effective, transformChanged)
        layoutOverlays(effective, effective.measuredSize, true)
        inputPipeline.layoutCommitted(root)
        focusedInputPipeline.layoutCommitted(root)
    }

    /**
     * Paints [root] in effective parent-before-child order.
     *
     * @param root the installed logical root.
     * @return commands in accumulated tree coordinates.
     */
    fun paint(root: RetainedNode): List<DrawCommand> = paintPipeline.paint(root.effectiveRoot)

    /**
     * Dispatches [event] through effective modifier and component ancestry.
     *
     * @param root the installed logical root.
     * @param event the tree-coordinate event.
     * @return consumed when one node handles the event, otherwise ignored.
     */
    fun dispatch(
        root: RetainedNode,
        event: PointerEvent,
    ): InputResult = inputPipeline.dispatch(root, event)

    /**
     * Dispatches recognized navigation without changing pointer focus or capture.
     */
    fun dispatchGesture(
        root: RetainedNode,
        gesture: PointerGesture,
    ): InputResult = inputPipeline.dispatchGesture(root, gesture)

    /**
     * Acquires native focus at committed coordinates without pointer activation.
     */
    fun focusAt(
        root: RetainedNode,
        position: IntOffset,
    ) {
        focusedInputPipeline.acquireFromPointer(root, position)
    }

    /**
     * Dispatches [event] through the focused logical component and applies ignored Tab traversal.
     *
     * @param root the installed logical root.
     * @param event immutable keyboard event.
     * @return consumed when focused behavior handles it or traversal selects an eligible owner, otherwise ignored.
     */
    fun dispatchKeyboard(
        root: RetainedNode,
        event: KeyboardEvent,
    ): InputResult = focusedInputPipeline.dispatchKeyboard(root, event)

    /**
     * Dispatches [event] through the focused logical component.
     *
     * @param event immutable committed-character or preedit event.
     * @return consumed when focused behavior handles it, otherwise ignored.
     */
    fun dispatchTextInput(event: TextInputEvent): InputResult = focusedInputPipeline.dispatchTextInput(event)

    /**
     * Clears capture, hover, and focus from the retained tree rooted at [root], including unplaced hover observers.
     *
     * Each independent cleanup is attempted under the tree's execution owner even when an earlier callback fails.
     *
     * @param root the installed logical root retained across session detachment.
     * @throws Throwable when a callback rejects cancellation or an exit transition; the first failure remains primary and later distinct failures are suppressed.
     */
    fun clearInputState(root: RetainedNode) {
        val failures = FailureAccumulator()
        failures.capture { inputPipeline.cancelCapture() }
        failures.capture { inputPipeline.clearHover(root) }
        failures.capture { focusedInputPipeline.clear() }
        failures.throwIfPresent()
    }

    /**
     * Cancels a captured entry before its callbacks and lifecycle resources are disposed.
     *
     * @param entry retained component or modifier being cleaned under the tree's execution owner.
     * @throws Throwable when cancellation fails; the lifecycle owner still attempts remaining cleanup.
     */
    fun entryWillCleanup(entry: RetainedEntry) {
        frameCallbacks.clear()
        inputPipeline.entryWillCleanup(entry)
    }

    /**
     * Releases retained input-pipeline references and cancels an unfinished captured gesture.
     *
     * The owning tree calls this under its execution owner after entering a terminal state, and still performs lifecycle cleanup if cancellation fails.
     * Focus references and then capture references are cleared before cancellation is invoked.
     *
     * @throws Throwable when the previous capture owner rejects cancellation.
     */
    fun releaseRetainedReferences() {
        frameCallbacks.clear()
        focusedInputPipeline.releaseRetainedReferences()
        inputPipeline.cancelCapture()
    }

    /**
     * Collects unresolved semantics through effective ancestry.
     *
     * @param root the installed logical root.
     * @return entries in effective parent-before-child order.
     */
    fun semantics(
        root: RetainedNode,
        ownership: Boolean = false,
    ): List<SemanticsEntry> = if (root.hasSemantics) semanticsPipeline.semantics(root, ownership) else emptyList()

    /**
     * Acquires a visible semantic owner without pointer activation.
     */
    fun focusSemantics(
        root: RetainedNode,
        identity: Long,
    ): InputResult = focusedInputPipeline.focusSemantics(root, identity)

    /**
     * Captures pending external observations for every effective entry without committing any observation.
     *
     * @param root installed logical root, borrowed under its execution owner.
     * @throws Throwable when capture fails; the tree performs terminal cleanup.
     */
    fun captureFrameState(root: RetainedNode) {
        frameCallbacks.capture(root, dirtyTracker.structureRevision)
    }

    /**
     * Commits previously captured external observations before frame-cache evaluation.
     *
     * @param root installed logical root whose entries have all captured their cutoff.
     * @throws Throwable when a commit fails; the tree performs terminal cleanup.
     */
    fun commitFrameState(root: RetainedNode) {
        frameCallbacks.commit(root, dirtyTracker.structureRevision)
    }

    /**
     * Resumes attachment-scoped node resources in effective parent-first order under the tree's execution owner.
     *
     * @param root installed logical root retained by an attached session.
     * @throws Throwable when attachment fails; ordinary tree cleanup releases every claimed node.
     */
    fun sessionAttached(root: RetainedNode) {
        visitEntries(root.effectiveRoot) { entry -> (entry.node as? SessionAttachmentNode)?.sessionAttached() }
    }

    /**
     * Suspends all attachment-scoped resources in reverse-sibling descendant-first order.
     *
     * @param root installed logical root that remains owned by the detached session.
     * @throws Throwable after every suspension is attempted; the first failure remains primary and later failures are suppressed.
     */
    fun sessionDetached(root: RetainedNode) {
        val failures = FailureAccumulator()
        detachSessionEntry(root.effectiveRoot, failures)
        failures.throwIfPresent()
    }

    /**
     * Notifies every time-aware effective entry before frame-cache evaluation.
     *
     * @param root installed logical root.
     * @param time current host timestamp.
     */
    fun advanceFrame(
        root: RetainedNode,
        time: FrameTime,
    ) {
        frameCallbacks.advance(root, dirtyTracker.structureRevision, time)
    }

    /**
     * Returns whether a measured subtree still has pending measurement work.
     *
     * @param root the installed logical root.
     * @return true when reachable effective entries need measurement.
     */
    fun hasPendingMeasure(root: RetainedNode): Boolean = pendingMeasure(root.effectiveRoot)

    /**
     * Resolves pending geometry of the already committed retained tree before the next input event.
     *
     * This performs only dirty measure/layout work; it neither refreshes dynamic child descriptions nor paints or collects semantics.
     * Clean geometry does not invoke node callbacks, and a self-invalidating measure remains an error before dispatch.
     *
     * @param root currently committed logical root.
     * @param constraints root constraints from the last successful frame, not a future resize.
     */
    fun synchronizeInputGeometry(
        root: RetainedNode,
        constraints: Constraints,
    ) {
        if (pendingMeasure(root.effectiveRoot)) measure(root, constraints)
        if (pendingLayout(root.effectiveRoot)) {
            requireMeasuredRoot(root)
            layout(root)
        }
    }

    /**
     * Checks that the effective root has completed measurement and has no pending measurement work.
     *
     * @param root the installed logical root.
     */
    fun requireMeasuredRoot(root: RetainedNode) {
        val effective = root.effectiveRoot
        check(effective.measured) { "The tree must be measured before layout." }
        check(pendingMeasure(effective).not()) { "The tree must be measured before layout." }
    }

    /**
     * Checks that the effective root has completed layout and has no pending geometry work.
     *
     * @param root the installed logical root.
     */
    fun requirePlacedRoot(root: RetainedNode) {
        val effective = root.effectiveRoot
        check(effective.placed) { "The tree must be laid out before this operation." }
        check(pendingMeasure(effective).not()) { "The tree must be laid out before this operation." }
        check(pendingLayout(effective).not()) { "The tree must be laid out before this operation." }
    }

    private fun measureEntry(
        retained: RetainedEntry,
        constraints: Constraints,
    ): IntSize {
        if (DirtyPhase.Measure in retained.dirty || retained.measuredConstraints != constraints) {
            if (retained is RetainedNode) prepareChildren(retained, constraints)
            retained.dirty -= DirtyMask.of(DirtyPhase.Measure)
            retained.dirty += DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint, DirtyPhase.Semantics)
            val measurePass = if (0 < retained.effectiveChildCount) Any() else null
            val guard = ScopeGuard(ownerGuard)
            val scope =
                object : MeasureScope {
                    var measuredAnyChild = false

                    override val childCount: Int
                        get() {
                            guard.check()
                            return retained.effectiveChildCount
                        }

                    override fun measureChild(
                        index: Int,
                        constraints: Constraints,
                    ): IntSize {
                        guard.check()
                        require(index in 0 until childCount) { "Child index is outside the measurement scope." }
                        val child = retained.effectiveChildAt(index)
                        check(child.parentMeasurePass !== measurePass) { "A child may be measured only once per pass." }
                        child.parentMeasurePass = measurePass
                        measuredAnyChild = true
                        return measureEntry(child, constraints)
                    }

                    override fun <D : Any> childParentData(
                        index: Int,
                        key: ParentDataKey<D>,
                    ): D? {
                        guard.check()
                        require(index in 0 until childCount) { "Child index is outside the measurement scope." }
                        return resolveParentData(retained.effectiveChildAt(index), key)
                    }
                }
            val measured =
                try {
                    if (retained.node is MeasureNode) {
                        retained.node.measure(scope, constraints)
                    } else {
                        constraints.constrain(IntSize.Zero)
                    }
                } finally {
                    guard.close()
                }
            check(constraints.isSatisfiedBy(measured)) { "Node returned a size outside its constraints." }
            if (scope.measuredAnyChild) {
                check(retained.node is LayoutNode) { "A node that measures children must implement LayoutNode." }
            }
            retained.measuredSize = measured
            retained.measuredConstraints = constraints
            retained.childMeasurePass = measurePass
            retained.measured = true
        }
        return retained.measuredSize
    }

    private fun layoutEntry(
        retained: RetainedEntry,
        transformChanged: Boolean,
    ) {
        val mustLayout = retained.laidOut.not() || DirtyPhase.Layout in retained.dirty
        if (mustLayout) {
            retained.dirty -= DirtyMask.of(DirtyPhase.Layout)
            retained.childLayoutPass = if (0 < retained.effectiveChildCount) Any() else null
            val layoutCapability = retained.node as? LayoutNode
            if (layoutCapability != null) {
                val scope =
                    object : LayoutScope {
                        val guard = ScopeGuard(ownerGuard)

                        override val size: IntSize
                            get() {
                                guard.check()
                                return retained.measuredSize
                            }

                        override val childCount: Int
                            get() {
                                guard.check()
                                return retained.effectiveChildCount
                            }

                        override fun measuredChildSize(index: Int): IntSize {
                            guard.check()
                            require(index in 0 until childCount) { "Child index is outside the layout scope." }
                            val child = retained.effectiveChildAt(index)
                            check(retained.measuredChild(child)) { "The child was not measured in this pass." }
                            return child.measuredSize
                        }

                        override fun <D : Any> childParentData(
                            index: Int,
                            key: ParentDataKey<D>,
                        ): D? {
                            guard.check()
                            require(index in 0 until childCount) { "Child index is outside the layout scope." }
                            return resolveParentData(retained.effectiveChildAt(index), key)
                        }

                        override fun placeChild(
                            index: Int,
                            offset: IntOffset,
                        ) {
                            guard.check()
                            require(index in 0 until childCount) { "Child index is outside the layout scope." }
                            val child = retained.effectiveChildAt(index)
                            check(retained.measuredChild(child)) { "The child was not measured in this pass." }
                            check(child.parentLayoutPass !== retained.childLayoutPass) {
                                "A child may be placed only once per pass."
                            }
                            child.parentLayoutPass = retained.childLayoutPass
                            child.parentOffset = offset
                        }
                    }
                try {
                    layoutCapability.layout(scope)
                } finally {
                    scope.guard.close()
                }
            }
            retained.laidOut = true
        }
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            val offset = retained.childOffset(child)
            if (offset == null || retained.measuredChild(child).not()) {
                child.placed = false
                child.laidOut = false
            } else {
                val childTransform =
                    if (mustLayout) {
                        (
                            (retained.node as? ChildTransformNode)?.childTransform(index)
                                ?: ChildTransform.Identity
                        ).also { transform ->
                            child.transformFromParent = transform
                        }
                    } else {
                        child.transformFromParent
                    }
                val childTransformChanged = updateChildGeometry(retained, child, offset, childTransform, mustLayout || transformChanged)
                child.placed = true
                layoutEntry(child, childTransformChanged)
            }
        }
    }

    private fun layoutOverlays(
        entry: RetainedEntry,
        viewport: IntSize,
        ancestorPlaced: Boolean,
    ) {
        val placed = ancestorPlaced && entry.placed
        for (index in 0 until entry.effectiveChildCount) {
            layoutOverlays(entry.effectiveChildAt(index), viewport, placed)
        }
        if (entry !is RetainedModifier) return
        val overlay = entry.overlay?.effectiveRoot ?: return
        overlay.placed = placed && (entry.node as? RootOverlayContentNode)?.overlayVisible == true
        if (overlay.placed.not()) return
        val size = measureEntry(overlay, Constraints(maxWidth = viewport.width, maxHeight = viewport.height))
        val left = entry.bounds.left.coerceIn(0, maxOf(0, viewport.width - size.width))
        val below = entry.bounds.bottom.toLong() + 4L
        val above = entry.bounds.top.toLong() - 4L - size.height
        val preferred = if (below + size.height <= viewport.height) below else above
        val top = preferred.coerceIn(0L, maxOf(0, viewport.height - size.height).toLong()).toInt()
        val transform = TreeTransform.Identity.descend(IntOffset(left, top), ChildTransform.Identity)
        val changed = overlay.localToTree != transform
        overlay.localToTree = transform
        overlay.bounds = transform.enclosing(size)
        layoutEntry(overlay, changed)
        layoutOverlays(overlay, viewport, true)
    }

    private fun updateChildGeometry(
        parent: RetainedEntry,
        child: RetainedEntry,
        offset: IntOffset,
        transform: ChildTransform,
        parentChanged: Boolean,
    ): Boolean {
        // Paint-only traversal preserves current geometry; layout dirtiness also covers measured-size changes.
        val childNeedsLayout = child.laidOut.not() || DirtyPhase.Layout in child.dirty
        if (parentChanged.not() && child.placed && childNeedsLayout.not()) return false
        val previous = child.localToTree
        child.localToTree = parent.localToTree.descend(offset, transform)
        child.bounds = child.localToTree.enclosing(child.measuredSize)
        return child.localToTree != previous
    }

    private fun <D : Any> resolveParentData(
        child: RetainedEntry,
        key: ParentDataKey<D>,
    ): D? {
        var current: RetainedEntry? = child
        var matchingProvider: ParentDataModifierNode<*>? = null
        while (current != null) {
            if (current is RetainedModifier) {
                val provider = current.modifierNode as? ParentDataModifierNode<*>
                if (provider != null && provider.parentDataKey === key) {
                    matchingProvider = provider
                }
                current = current.effectiveChildAt(0)
            } else {
                val delegated = current.parentDataDelegate?.parentDataChild
                current = if (delegated != null && delegated in 0 until current.effectiveChildCount) current.effectiveChildAt(delegated) else null
            }
        }
        // Scan the complete chain before reading data so an outer provider is never invoked when shadowed.
        return matchingProvider?.let { provider -> key.castErased(provider.parentData()) }
    }

    private fun pendingMeasure(retained: RetainedEntry): Boolean {
        if (DirtyPhase.Measure in retained.dirty) {
            return true
        }
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            if (retained.measuredChild(child) && pendingMeasure(child)) {
                return true
            }
        }
        return false
    }

    private fun pendingLayout(retained: RetainedEntry): Boolean {
        if (DirtyPhase.Layout in retained.dirty) {
            return true
        }
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            if (child.placed && pendingLayout(child)) {
                return true
            }
        }
        return false
    }

    private fun visitEntries(
        retained: RetainedEntry,
        callback: (RetainedEntry) -> Unit,
    ) {
        callback(retained)
        for (index in 0 until retained.effectiveChildCount) {
            visitEntries(retained.effectiveChildAt(index), callback)
        }
        if (retained is RetainedModifier) retained.overlay?.let { visitEntries(it.effectiveRoot, callback) }
    }

    private fun detachSessionEntry(
        retained: RetainedEntry,
        failures: FailureAccumulator,
    ) {
        for (index in retained.effectiveChildCount - 1 downTo 0) {
            detachSessionEntry(retained.effectiveChildAt(index), failures)
        }
        if (retained is RetainedModifier) retained.overlay?.let { detachSessionEntry(it.effectiveRoot, failures) }
        val capability = retained.node as? SessionAttachmentNode ?: return
        failures.capture(capability::sessionDetached)
    }
}
