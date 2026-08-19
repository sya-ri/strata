package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText

/**
 * Test-owned primitive factory, typed identifiers, nodes, and lifecycle observations.
 */
internal class TestProbe(
    private val failingCreateTag: ProbeId? = null,
    private val failingAttachTag: ProbeId? = null,
    private val failingDetachTag: ProbeId? = null,
    private val failingDisposeTag: ProbeId? = null,
    private val failingUpdateTag: ProbeId? = null,
    private val failingMeasureTag: ProbeId? = null,
    private val failingLayoutTag: ProbeId? = null,
    private val failingPaintTag: ProbeId? = null,
    private val failingInputTag: ProbeId? = null,
    private val failingSemanticsTag: ProbeId? = null,
    private val inputResult: InputResult = InputResult.Consumed,
    private val overlapChildren: Boolean = false,
    private val createFailure: Throwable = IllegalStateException("create failure"),
    private val attachFailure: Throwable = IllegalStateException("attach failure"),
    private val detachFailure: Throwable = IllegalStateException("detach failure"),
    private val disposeFailure: Throwable = IllegalStateException("dispose failure"),
    private val updateFailure: Throwable = IllegalStateException("update failure"),
    private val measureFailure: Throwable = IllegalStateException("measure failure"),
    private val layoutFailure: Throwable = IllegalStateException("layout failure"),
    private val paintFailure: Throwable = IllegalStateException("paint failure"),
    private val inputFailure: Throwable = IllegalStateException("input failure"),
    private val semanticsFailure: Throwable = IllegalStateException("semantics failure"),
) {
    /**
     * Lifecycle callback observations.
     */
    val events: MutableList<Event> = ArrayList()

    /**
     * Successfully returned fresh nodes.
     */
    val created: MutableList<ProbeNode> = ArrayList()

    /**
     * Pointer callbacks observed in dispatch order.
     */
    val inputEvents: MutableList<ProbeId> = ArrayList()

    /**
     * Pointer callbacks observed with their accumulated-to-local positions.
     */
    val inputObservations: MutableList<InputObservation> = ArrayList()

    /**
     * Update callback counter used by validation rollback assertions.
     */
    var updateCalls: Int = 0

    /**
     * Measurement callback counter.
     */
    var measureCalls: Int = 0

    /**
     * Layout callback counter.
     */
    var layoutCalls: Int = 0

    /**
     * Paint callback counter.
     */
    var paintCalls: Int = 0

    /**
     * Semantics callback counter.
     */
    var semanticsCalls: Int = 0

    /**
     * Creates a root description.
     *
     * @param children direct child descriptions.
     * @param modifier active modifier descriptions.
     * @return an immutable root description.
     */
    fun root(
        children: List<Element>,
        modifier: Modifier = Modifier.Empty,
    ): ProbeElement = element(ProbeId("root"), children = children, modifier = modifier)

    /**
     * Creates one immutable probe description.
     *
     * @param tag typed description identity.
     * @param key optional direct-sibling key.
     * @param sharedNode optional deliberately aliased node for ownership tests.
     * @param children direct child descriptions.
     * @param measureDirty property that reports a measure-phase update when changed.
     * @param modifier active modifier descriptions.
     * @param onAttach callback invoked from this node's attach hook.
     * @param onDetach callback invoked from this node's detach hook.
     * @param onUpdate callback invoked from this element's update hook.
     * @return an immutable element description.
     */
    fun element(
        tag: ProbeId,
        key: ProbeId? = null,
        sharedNode: ProbeNode? = null,
        children: List<Element> = emptyList(),
        modifier: Modifier = Modifier.Empty,
        measureDirty: Boolean = false,
        onAttach: (() -> Unit)? = null,
        onDetach: (() -> Unit)? = null,
        onUpdate: (() -> Unit)? = null,
        onMeasure: (() -> Unit)? = null,
        onLayout: (() -> Unit)? = null,
        onPaint: (() -> Unit)? = null,
        onInput: (() -> Unit)? = null,
        onSemantics: (() -> Unit)? = null,
        onDispose: (() -> Unit)? = null,
    ): ProbeElement =
        ProbeElement(
            this,
            tag,
            key,
            sharedNode,
            children,
            modifier,
            measureDirty,
            onAttach,
            onDetach,
            onUpdate,
            onMeasure,
            onLayout,
            onPaint,
            onInput,
            onSemantics,
            onDispose,
        )

    /**
     * Finds a created node by its current typed tag.
     *
     * @param tag the expected tag.
     * @return the latest node with [tag].
     */
    fun nodeForTag(tag: ProbeId): ProbeNode = created.last { node -> node.tag == tag }

    /**
     * Creates or returns the node requested by [element].
     */
    internal fun create(element: ProbeElement): ProbeNode {
        if (element.tag == failingCreateTag) {
            throw createFailure
        }
        val shared = element.sharedNode
        if (shared != null) {
            return shared
        }
        val fresh =
            ProbeNode(
                this,
                element.tag,
                element.onAttach,
                element.onMeasure,
                element.onLayout,
                element.onPaint,
                element.onInput,
                element.onSemantics,
                element.onDispose,
            )
        fresh.onDetach = element.onDetach
        created.add(fresh)
        return fresh
    }

    /**
     * Applies a typed property update and reports its dirty phases.
     */
    internal fun update(
        previous: ProbeElement,
        current: ProbeElement,
        node: ProbeNode,
    ): DirtyMask {
        updateCalls += 1
        current.onUpdate?.invoke()
        if (current.tag == failingUpdateTag) {
            throw updateFailure
        }
        var mask = DirtyMask.None
        if (previous.tag != current.tag) {
            node.tag = current.tag
            mask += DirtyMask.of(DirtyPhase.Semantics)
        }
        if (previous.measureDirty != current.measureDirty) {
            mask += DirtyMask.of(DirtyPhase.Measure)
        }
        node.onAttach = current.onAttach
        node.onDetach = current.onDetach
        node.onMeasure = current.onMeasure
        node.onLayout = current.onLayout
        node.onPaint = current.onPaint
        node.onInput = current.onInput
        node.onSemantics = current.onSemantics
        node.onDispose = current.onDispose
        return mask
    }

    /**
     * Throws the configured failure for one pipeline stage and typed node.
     */
    internal fun failIfConfigured(
        stage: FailureStage,
        tag: ProbeId,
    ) {
        val failure =
            when (stage) {
                FailureStage.Measure -> if (tag == failingMeasureTag) measureFailure else null
                FailureStage.Layout -> if (tag == failingLayoutTag) layoutFailure else null
                FailureStage.Paint -> if (tag == failingPaintTag) paintFailure else null
                FailureStage.Input -> if (tag == failingInputTag) inputFailure else null
                FailureStage.Semantics -> if (tag == failingSemanticsTag) semanticsFailure else null
            }
        if (failure != null) {
            throw failure
        }
    }

    /**
     * Returns the test placement offset for one direct child.
     */
    internal fun childOffset(index: Int): IntOffset = IntOffset(if (overlapChildren) 0 else index * 2, 0)

    /**
     * Records and optionally fails an attach callback.
     */
    internal fun attach(node: ProbeNode) {
        events.add(Event.Attach(node.tag))
        node.onAttach?.invoke()
        if (node.tag == failingAttachTag) {
            throw attachFailure
        }
    }

    /**
     * Records and optionally fails a detach callback.
     */
    internal fun detach(node: ProbeNode) {
        events.add(Event.Detach(node.tag))
        node.onDetach?.invoke()
        if (node.tag == failingDetachTag) {
            throw detachFailure
        }
    }

    /**
     * Records and optionally fails a dispose callback.
     */
    internal fun dispose(node: ProbeNode) {
        events.add(Event.Dispose(node.tag))
        node.onDispose?.invoke()
        if (node.tag == failingDisposeTag) {
            throw disposeFailure
        }
    }

    /**
     * Typed description identifier used by test probes.
     */
    data class ProbeId(
        val value: String,
    )

    /**
     * Typed pointer observation used to verify local-coordinate conversion.
     */
    data class InputObservation(
        val tag: ProbeId,
        val event: PointerEvent,
        val localPosition: IntOffset,
    )

    /**
     * Typed lifecycle observation.
     */
    sealed interface Event {
        /**
         * An attach callback observation.
         */
        data class Attach(
            val tag: ProbeId,
        ) : Event

        /**
         * A detach callback observation.
         */
        data class Detach(
            val tag: ProbeId,
        ) : Event

        /**
         * A dispose callback observation.
         */
        data class Dispose(
            val tag: ProbeId,
        ) : Event
    }

    /**
     * Pipeline stages that can be configured to fail in tests.
     */
    enum class FailureStage {
        /**
         * Measurement callback stage.
         */
        Measure,

        /**
         * Layout callback stage.
         */
        Layout,

        /**
         * Paint callback stage.
         */
        Paint,

        /**
         * Pointer input callback stage.
         */
        Input,

        /**
         * Semantics callback stage.
         */
        Semantics,
    }

    /**
     * Publicly shaped test node implementing the external primitive capabilities.
     */
    class ProbeNode internal constructor(
        private val probe: TestProbe,
        var tag: ProbeId,
        var onAttach: (() -> Unit)? = null,
        var onMeasure: (() -> Unit)? = null,
        var onLayout: (() -> Unit)? = null,
        var onPaint: (() -> Unit)? = null,
        var onInput: (() -> Unit)? = null,
        var onSemantics: (() -> Unit)? = null,
        var onDispose: (() -> Unit)? = null,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        PointerInputNode,
        SemanticsNode,
        LifecycleNode {
        /**
         * Callback invoked before a detach failure decision.
         */
        var onDetach: (() -> Unit)? = null

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            probe.measureCalls += 1
            onMeasure?.invoke()
            probe.failIfConfigured(FailureStage.Measure, tag)
            var height = 1
            for (index in 0 until scope.childCount) {
                height += scope.measureChild(index, constraints).height
            }
            return constraints.constrain(IntSize(2, height))
        }

        override fun layout(scope: LayoutScope) {
            probe.layoutCalls += 1
            onLayout?.invoke()
            probe.failIfConfigured(FailureStage.Layout, tag)
            for (index in 0 until scope.childCount) {
                scope.placeChild(index, probe.childOffset(index))
            }
        }

        override fun paint(scope: PaintScope) {
            probe.paintCalls += 1
            onPaint?.invoke()
            probe.failIfConfigured(FailureStage.Paint, tag)
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), ArgbColor(0xFF00FF00.toInt()))
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            onInput?.invoke()
            probe.failIfConfigured(FailureStage.Input, tag)
            probe.inputEvents.add(tag)
            probe.inputObservations.add(InputObservation(tag, event, localPosition))
            return probe.inputResult
        }

        override fun semantics(scope: SemanticsScope) {
            probe.semanticsCalls += 1
            onSemantics?.invoke()
            probe.failIfConfigured(FailureStage.Semantics, tag)
            scope.emit(Semantics(label = UiText.Literal(tag.value)))
        }

        override fun attach() = probe.attach(this)

        override fun detach() = probe.detach(this)

        override fun dispose() = probe.dispose(this)

        /**
         * Exercises protected node invalidation from cleanup callbacks.
         */
        fun invalidateForTest(mask: DirtyMask) {
            invalidate(mask)
        }
    }

    /**
     * Typed test element with a stable singleton token.
     */
    class ProbeElement internal constructor(
        private val probe: TestProbe,
        val tag: ProbeId,
        key: ProbeId?,
        internal val sharedNode: ProbeNode?,
        children: List<Element>,
        modifier: Modifier,
        internal val measureDirty: Boolean,
        internal val onAttach: (() -> Unit)?,
        internal val onDetach: (() -> Unit)?,
        internal val onUpdate: (() -> Unit)?,
        internal val onMeasure: (() -> Unit)?,
        internal val onLayout: (() -> Unit)?,
        internal val onPaint: (() -> Unit)?,
        internal val onInput: (() -> Unit)?,
        internal val onSemantics: (() -> Unit)?,
        internal val onDispose: (() -> Unit)?,
    ) : Element(
            identity = key?.let { value -> ElementIdentity.Keyed(ElementKey(value)) } ?: ElementIdentity.Positional,
            type = TYPE,
            children = children,
            modifier = modifier,
        ) {
        /**
         * Stable test token and typed element hooks.
         */
        companion object {
            val TYPE: ElementType<ProbeElement, ProbeNode> =
                ElementType(
                    elementClass = ProbeElement::class,
                    nodeClass = ProbeNode::class,
                    validateLocal = { element -> require(element.tag.value.isNotBlank()) },
                    createNode = { element -> element.probe.create(element) },
                    updateNode = { previous, current, node -> current.probe.update(previous, current, node) },
                )
        }
    }
}
