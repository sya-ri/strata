package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FrameTimeNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Retained Minecraft friends-loading animation using the exact three-cell profile sprite.
 */
internal class MinecraftLoadingIndicatorElement private constructor(
    internal val image: DrawImage,
    internal val size: IntSize,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = emptyList(),
        modifier = modifier,
    ) {
    /**
     * Retains the discrete native animation cell and invalidates only when that cell changes.
     */
    internal class Node(
        private var image: DrawImage,
        private var size: IntSize,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        FrameTimeNode {
        private var frameIndex = 0
        private var startedAtNanoseconds: Long? = null

        override fun onFrame(time: FrameTime) {
            val startedAt = startedAtNanoseconds
            if (startedAt == null) {
                startedAtNanoseconds = time.nanoseconds
                return
            }
            val elapsed = Math.subtractExact(time.nanoseconds, startedAt)
            val next = Math.floorMod(Math.floorDiv(elapsed, FRAME_DURATION_NANOS), FRAME_COUNT.toLong()).toInt()
            if (frameIndex != next) {
                frameIndex = next
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 0) { "LoadingIndicator cannot have children." }
            require(constraints.isSatisfiedBy(size)) { "LoadingIndicator constraints must contain its requested size." }
            return size
        }

        override fun paint(scope: PaintScope) {
            val sourceTop = frameIndex * SOURCE_FRAME_HEIGHT
            scope.blitImage(
                image,
                IntRect(0, sourceTop, SOURCE_FRAME_WIDTH, sourceTop + SOURCE_FRAME_HEIGHT),
                IntRect(0, 0, size.width, size.height),
            )
        }

        /**
         * Applies a changed immutable sprite or destination size.
         */
        internal fun update(current: MinecraftLoadingIndicatorElement): DirtyMask {
            val sizeChanged = size != current.size
            val imageChanged = image !== current.image
            image = current.image
            size = current.size
            var dirty = if (imageChanged) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
            if (sizeChanged) dirty += DirtyMask.of(DirtyPhase.Measure)
            return dirty
        }
    }

    /**
     * Owns the stable element type and verified vanilla animation timing.
     */
    companion object {
        private const val SOURCE_FRAME_WIDTH = 5
        private const val SOURCE_FRAME_HEIGHT = 2
        private const val FRAME_COUNT = 3
        private const val FRAME_DURATION_NANOS = 300_000_000L
        private val TYPE: ElementType<MinecraftLoadingIndicatorElement, Node> =
            ElementType(
                elementClass = MinecraftLoadingIndicatorElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.image.size == IntSize(5, 6)) { "LoadingIndicator sprite must be 5 by 6 pixels." }
                    require(0 < element.size.width && 0 < element.size.height) { "LoadingIndicator size must be positive." }
                },
                createNode = { element -> Node(element.image, element.size) },
                updateNode = { _, current, node -> node.update(current) },
            )

        /**
         * Creates one immutable profile-backed loading indicator description.
         */
        internal fun create(
            image: DrawImage,
            size: IntSize,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftLoadingIndicatorElement(image, size, modifier, key)
    }
}
