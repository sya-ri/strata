package dev.s7a.strata.runtime.headless

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.text.UiText

/**
 * A test-owned primitive implemented only through the public element and node SPI.
 *
 * @property probe the callback observation sink.
 * @property width the natural width.
 * @property height the natural height.
 * @property color the emitted fill color.
 * @property label the unresolved semantics label.
 * @property key the optional typed retained identity.
 */
internal class HeadlessPrimitive(
    val probe: HeadlessProbe,
    val width: Int = 2,
    val height: Int = 2,
    val color: ArgbColor = ArgbColor(0xFF00FF00.toInt()),
    val label: String = "headless",
    key: ElementKey<*>? = null,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
    ) {
    init {
        require(0 <= width) { "Width must be non-negative." }
        require(0 <= height) { "Height must be non-negative." }
    }

    /**
     * The stable primitive token used by the test fixture.
     */
    companion object {
        /**
         * Stable token and typed hooks for the fixture.
         */
        val TYPE: ElementType<HeadlessPrimitive, Retained> =
            ElementType(
                elementClass = HeadlessPrimitive::class,
                nodeClass = Retained::class,
                validateLocal = { element ->
                    element.probe.validations += 1
                    require(0 <= element.width)
                    require(0 <= element.height)
                },
                createNode = { element ->
                    element.probe.creations += 1
                    Retained(element.probe).also { node ->
                        node.width = element.width
                        node.height = element.height
                        node.color = element.color
                        node.label = element.label
                    }
                },
                updateNode = { previous, current, node ->
                    var dirty = DirtyMask.None
                    if (previous.width != current.width || previous.height != current.height) {
                        node.width = current.width
                        node.height = current.height
                        dirty += DirtyMask.of(DirtyPhase.Measure)
                    }
                    if (previous.color != current.color) {
                        node.color = current.color
                        dirty += DirtyMask.of(DirtyPhase.Paint)
                    }
                    if (previous.label != current.label) {
                        node.label = current.label
                        dirty += DirtyMask.of(DirtyPhase.Semantics)
                    }
                    dirty
                },
            )
    }

    /**
     * Retained node implementation for the external fixture.
     */
    class Retained internal constructor(
        private val probe: HeadlessProbe,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        SemanticsNode,
        LifecycleNode {
        /**
         * Current natural width.
         */
        var width: Int = 2

        /**
         * Current natural height.
         */
        var height: Int = 2

        /**
         * Current fill color.
         */
        var color: ArgbColor = ArgbColor(0xFF00FF00.toInt())

        /**
         * Current unresolved label.
         */
        var label: String = "headless"

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            probe.callbackThreads += Thread.currentThread()
            probe.measures += 1
            return constraints.constrain(IntSize(width, height))
        }

        override fun layout(scope: LayoutScope) {
            probe.callbackThreads += Thread.currentThread()
            probe.layouts += 1
        }

        override fun paint(scope: PaintScope) {
            probe.callbackThreads += Thread.currentThread()
            probe.paints += 1
            val failure = probe.paintFailure
            if (failure != null) {
                throw failure
            }
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
        }

        override fun semantics(scope: SemanticsScope) {
            probe.callbackThreads += Thread.currentThread()
            probe.semantics += 1
            scope.emit(Semantics(label = UiText.Literal(label)))
        }

        override fun attach() {
            probe.callbackThreads += Thread.currentThread()
            probe.lifecycle += HeadlessLifecycleEvent.Attach
        }

        override fun detach() {
            probe.callbackThreads += Thread.currentThread()
            probe.lifecycle += HeadlessLifecycleEvent.Detach
            val failure = probe.detachFailure
            if (failure != null) {
                throw failure
            }
        }

        override fun dispose() {
            probe.callbackThreads += Thread.currentThread()
            probe.lifecycle += HeadlessLifecycleEvent.Dispose
            val failure = probe.disposeFailure
            if (failure != null) {
                throw failure
            }
        }
    }
}
