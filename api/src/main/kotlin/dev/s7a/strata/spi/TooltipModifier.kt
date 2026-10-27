package dev.s7a.strata.spi

import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.modifier.RootOverlayModifierElement
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FrameTimeNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.RootOverlayContentNode
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.runtime.FrameTime

/**
 * Runtime construction bridge for a decorated tooltip root with common hover timing.
 * The runtime resolves decoration before constructing this immutable description.
 */
@InternalStrataRuntimeApi
public class TooltipModifier(
    override val content: Element,
    public val delayMillis: Long,
    override val projection: DeclarationProjection<*>? = null,
) : RootOverlayModifierElement {
    init {
        require(delayMillis in 0L..Long.MAX_VALUE / NANOS_PER_MILLI) { "Tooltip delay is outside the supported range." }
    }

    override val type: ModifierNodeType<*, *> get() = TYPE

    /**
     * Owner-confined timing state; hidden content remains owned by the containing tree.
     */
    private class Retained(
        initial: TooltipModifier,
    ) : ModifierNode(),
        PointerHoverNode,
        FrameTimeNode,
        RootOverlayContentNode {
        private var delay = initial.delayMillis * NANOS_PER_MILLI
        private var hovered = false
        private var started: Long? = null
        override var overlayVisible: Boolean = false
            private set

        override fun onPointerHover(hovered: Boolean) {
            if (this.hovered == hovered) return
            this.hovered = hovered
            started = null
            setVisible(hovered && delay == 0L)
        }

        override fun onFrame(time: FrameTime) {
            if (hovered.not() || overlayVisible) return
            val start = started
            if (start == null || time.nanoseconds < start) {
                started = time.nanoseconds
            } else if (delay <= time.nanoseconds - start) {
                setVisible(true)
            }
        }

        /**
         * Restarts timing only when the declared delay changes.
         */
        fun update(current: TooltipModifier): DirtyMask {
            val next = current.delayMillis * NANOS_PER_MILLI
            if (delay == next) return DirtyMask.None
            delay = next
            started = null
            overlayVisible = hovered && delay == 0L
            return DirtyMask.of(DirtyPhase.Layout)
        }

        private fun setVisible(value: Boolean) {
            if (overlayVisible == value) return
            overlayVisible = value
            invalidate(DirtyMask.of(DirtyPhase.Layout))
        }
    }

    private companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private val TYPE =
            ModifierNodeType(
                elementClass = TooltipModifier::class,
                nodeClass = Retained::class,
                validateLocal = {},
                createNode = { Retained(it) },
                updateNode = { _, current, node -> node.update(current) },
            )
    }
}
