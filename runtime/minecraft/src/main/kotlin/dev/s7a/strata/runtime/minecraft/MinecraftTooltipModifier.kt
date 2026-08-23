package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FrameTimeNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.RootOverlayPaintScope
import dev.s7a.strata.runtime.FrameTime

/**
 * Owns the immutable and retained forms of Minecraft tooltip behavior.
 */
private object MinecraftTooltipModifier {
    /**
     * Immutable tooltip description retaining only its resolved text and profile sprites.
     */
    data class Element(
        val text: MinecraftTextRun,
        val background: DrawImage,
        val frame: DrawImage,
        val delayNanos: Long,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retains hover timing and emits the tooltip only after its delay boundary.
     */
    class Node(
        initial: Element,
    ) : ModifierNode(),
        PointerHoverNode,
        FrameTimeNode,
        RootOverlayPaintNode {
        private var text = initial.text
        private var background = initial.background
        private var frame = initial.frame
        private var delayNanos = initial.delayNanos
        private var hovered = false
        private var hoverStartNanos: Long? = null
        private var visible = false

        override fun onPointerHover(hovered: Boolean) {
            if (this.hovered == hovered) return
            this.hovered = hovered
            hoverStartNanos = null
            if (visible) {
                visible = false
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            } else if (hovered && delayNanos == 0L) {
                visible = true
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        override fun onFrame(time: FrameTime) {
            if (hovered.not() || visible) return
            val start = hoverStartNanos
            if (start == null || time.nanoseconds < start) {
                hoverStartNanos = time.nanoseconds
                return
            }
            if (delayNanos <= time.nanoseconds - start) {
                visible = true
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            if (visible.not()) return
            val width = Math.addExact(text.size.width, PADDING * 2)
            val height = Math.addExact(text.size.height, PADDING * 2)
            val preferredLeft = scope.anchorBounds.left
            val left = minOf(maxOf(0, preferredLeft), maxOf(0, scope.size.width - width))
            val below = Math.addExact(scope.anchorBounds.bottom, GAP)
            val above = Math.subtractExact(scope.anchorBounds.top, Math.addExact(GAP, height))
            val preferredTop = if (below + height <= scope.size.height) below else above
            val top = minOf(maxOf(0, preferredTop), maxOf(0, scope.size.height - height))
            val bounds = IntRect(left, top, left + width, top + height)
            paintMinecraftNineSlice(
                MinecraftRectPaintScope(scope, bounds),
                background,
                Insets.all(BACKGROUND_BORDER),
                NineSliceCenterMode.Tiled,
            )
            paintMinecraftNineSlice(
                MinecraftRectPaintScope(scope, bounds),
                frame,
                Insets.all(FRAME_BORDER),
                NineSliceCenterMode.Stretched,
            )
            text.paint(scope, left + PADDING, top + PADDING)
        }

        /**
         * Applies changed tooltip content and timing.
         */
        fun update(current: Element): DirtyMask {
            val paintChanged = text.equivalentTo(current.text).not() || background !== current.background || frame !== current.frame
            val timingChanged = delayNanos != current.delayNanos
            text = current.text
            background = current.background
            frame = current.frame
            delayNanos = current.delayNanos
            if (timingChanged && hovered) {
                hoverStartNanos = null
                visible = current.delayNanos == 0L
            }
            return if (paintChanged || timingChanged) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
        }
    }

    val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { element ->
                require(element.background.size.width == 100 && element.background.size.height == 100) {
                    "Tooltip background sprite must be 100 by 100 pixels."
                }
                require(element.frame.size.width == 100 && element.frame.size.height == 100) {
                    "Tooltip frame sprite must be 100 by 100 pixels."
                }
                require(0L <= element.delayNanos) { "Tooltip delay must be non-negative." }
            },
            createNode = { element -> Node(element) },
            updateNode = { _, current, node -> node.update(current) },
        )

    fun element(
        text: MinecraftTextRun,
        background: DrawImage,
        frame: DrawImage,
        delayNanos: Long,
    ): ModifierElement = Element(text, background, frame, delayNanos)

    private const val PADDING = 3
    private const val GAP = 4
    private const val BACKGROUND_BORDER = 9
    private const val FRAME_BORDER = 10
}

/**
 * Creates one internal root-tooltip behavior description.
 */
internal fun createMinecraftTooltipModifier(
    text: MinecraftTextRun,
    background: DrawImage,
    frame: DrawImage,
    delayNanos: Long,
): ModifierElement = MinecraftTooltipModifier.element(text, background, frame, delayNanos)
