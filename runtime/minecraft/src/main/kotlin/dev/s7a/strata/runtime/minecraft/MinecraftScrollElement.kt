package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import kotlin.math.max
import kotlin.math.min
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description for the 26.2 menu-list scroll viewport.
 *
 * The description owns one immutable content root plus the exact profile asset references required for background, separators, and scrollbar rendering.
 * Checked measure, layout, paint, and pointer failures escape into the retained tree's ordinary poisoning and cleanup contract.
 *
 * @param listBackground immutable 16 by 16 list texture.
 * @param headerSeparator immutable 32 by 2 header separator.
 * @param footerSeparator immutable 32 by 2 footer separator.
 * @param scrollbarBackground immutable 6 by 32 track sprite.
 * @param scrollbarThumb immutable 6 by 32 thumb sprite.
 * @param scrollRate positive logical wheel displacement multiplier.
 * @param content sole direct content root.
 * @param modifier active behavior applied to the viewport.
 * @param key optional stable identity among direct siblings.
 */
private class MinecraftScrollElement private constructor(
    @get:JvmSynthetic
    internal val listBackground: DrawImage,
    @get:JvmSynthetic
    internal val headerSeparator: DrawImage,
    @get:JvmSynthetic
    internal val footerSeparator: DrawImage,
    @get:JvmSynthetic
    internal val scrollbarBackground: DrawImage,
    @get:JvmSynthetic
    internal val scrollbarThumb: DrawImage,
    @get:JvmSynthetic
    internal val scrollRate: Int,
    content: Element,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = listOf(content),
        modifier = modifier,
    ) {
    /**
     * Retained one-child viewport implementing the verified 26.2 list geometry, clipping, paint order, and wheel/scrollbar interaction.
     */
    @Suppress("TooManyFunctions")
    private class Node(
        initialListBackground: DrawImage,
        initialHeaderSeparator: DrawImage,
        initialFooterSeparator: DrawImage,
        initialScrollbarBackground: DrawImage,
        initialScrollbarThumb: DrawImage,
        initialScrollRate: Int,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode,
        PaintNode,
        OverlayPaintNode,
        ClipChildrenNode,
        PointerInputNode,
        LifecycleNode {
        private val listTileSize = IntSize(32, 32)
        private val scrollbarSize = IntSize(6, 32)
        private val separatorSize = IntSize(32, 2)
        private var listBackground: DrawImage? = initialListBackground
        private var headerSeparator: DrawImage? = initialHeaderSeparator
        private var footerSeparator: DrawImage? = initialFooterSeparator
        private var scrollbarBackground: DrawImage? = initialScrollbarBackground
        private var scrollbarThumb: DrawImage? = initialScrollbarThumb
        private var scrollRate = initialScrollRate
        private var contentSize = IntSize.Zero
        private var viewportSize = IntSize.Zero
        private var scrollAmount = 0.0
        private var maxScrollAmount = 0
        private var draggingScrollbar = false
        private var disposed = false

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.maxWidth != Int.MAX_VALUE) { "Minecraft Scroll width must be bounded." }
            require(constraints.maxHeight != Int.MAX_VALUE) { "Minecraft Scroll height must be bounded." }
            check(scope.childCount == 1) { "Minecraft Scroll requires exactly one retained child." }
            val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
            val measuredContent =
                scope.measureChild(
                    0,
                    Constraints(
                        minWidth = 0,
                        maxWidth = viewport.width,
                        minHeight = 0,
                        maxHeight = Int.MAX_VALUE,
                    ),
                )
            val contentHeight = Math.addExact(measuredContent.height, 4)
            val nextMaximum = max(0, Math.subtractExact(contentHeight, viewport.height))
            if (0 < nextMaximum) {
                require(40 <= viewport.height) { "Scrollable Minecraft Scroll viewports require at least 40 logical pixels of height." }
            }
            contentSize = measuredContent
            viewportSize = viewport
            maxScrollAmount = nextMaximum
            scrollAmount = scrollAmount.coerceIn(0.0, nextMaximum.toDouble())
            return viewport
        }

        override fun layout(scope: LayoutScope) {
            check(scope.childCount == 1) { "Minecraft Scroll requires exactly one measured child." }
            val child = scope.measuredChildSize(0)
            val left = Math.subtractExact(scope.size.width, child.width) / 2
            val top = Math.subtractExact(2, scrollAmount.toInt())
            scope.placeChild(0, IntOffset(left, top))
        }

        override fun paint(scope: PaintScope) {
            if (scope.size.width == 0 || scope.size.height == 0) return
            val phaseY = Math.addExact(scope.size.height, scrollAmount.toInt())
            paintScaledTiles(
                scope,
                checkNotNull(listBackground),
                scope.size,
                phaseX = scope.size.width,
                phaseY = phaseY,
            )
        }

        override fun paintOverlay(scope: PaintScope) {
            paintHorizontalRepeat(scope, checkNotNull(headerSeparator), -separatorSize.height)
            paintHorizontalRepeat(scope, checkNotNull(footerSeparator), scope.size.height)
            if (maxScrollAmount == 0 || scope.size.width == 0 || scope.size.height == 0) return
            val x = scrollbarX(scope.size.width)
            paintVerticalNineSlice(scope, checkNotNull(scrollbarBackground), x, 0, scope.size.height)
            val thumbHeight = scrollerHeight(scope.size.height)
            paintVerticalNineSlice(scope, checkNotNull(scrollbarThumb), x, scrollbarY(scope.size.height, thumbHeight), thumbHeight)
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            when (event) {
                is PointerEvent.Scroll -> {
                    changeScroll(event.deltaY * scrollRate.toDouble())
                    InputResult.Consumed
                }

                is PointerEvent.Press -> {
                    if (
                        event.button == PointerButton.Primary &&
                        0 < maxScrollAmount &&
                        isOverScrollbar(localPosition)
                    ) {
                        draggingScrollbar = true
                        InputResult.Consumed
                    } else {
                        InputResult.Ignored
                    }
                }

                is PointerEvent.Drag -> {
                    if (draggingScrollbar && event.button == PointerButton.Primary) {
                        dragScrollbar(event, localPosition)
                        InputResult.Consumed
                    } else {
                        InputResult.Ignored
                    }
                }

                is PointerEvent.Release -> {
                    if (draggingScrollbar && event.button == PointerButton.Primary) {
                        draggingScrollbar = false
                        InputResult.Consumed
                    } else {
                        InputResult.Ignored
                    }
                }

                is PointerEvent.Move -> {
                    InputResult.Ignored
                }
            }

        override fun attach() = Unit

        override fun detach() {
            draggingScrollbar = false
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            draggingScrollbar = false
            listBackground = null
            headerSeparator = null
            footerSeparator = null
            scrollbarBackground = null
            scrollbarThumb = null
        }

        /**
         * Updates the retained viewport from one reconciled immutable description.
         *
         * Equal values preserve cached output and scroll position, asset changes repaint, and a rate-only change affects future input without geometry work.
         *
         * @param current next immutable description.
         * @return exact dirty phases required by the update.
         */
        @JvmSynthetic
        internal fun updateFrom(current: MinecraftScrollElement): DirtyMask {
            val imagesChanged =
                listBackground != current.listBackground ||
                    headerSeparator != current.headerSeparator ||
                    footerSeparator != current.footerSeparator ||
                    scrollbarBackground != current.scrollbarBackground ||
                    scrollbarThumb != current.scrollbarThumb
            listBackground = current.listBackground
            headerSeparator = current.headerSeparator
            footerSeparator = current.footerSeparator
            scrollbarBackground = current.scrollbarBackground
            scrollbarThumb = current.scrollbarThumb
            scrollRate = current.scrollRate
            return if (imagesChanged) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
        }

        private fun dragScrollbar(
            event: PointerEvent.Drag,
            localPosition: IntOffset,
        ) {
            if (localPosition.y < 0) {
                setScrollAmount(0.0)
                return
            }
            if (viewportSize.height < localPosition.y) {
                setScrollAmount(maxScrollAmount.toDouble())
                return
            }
            val trackTravel = Math.subtractExact(viewportSize.height, scrollerHeight(viewportSize.height))
            val multiplier = max(1.0, maxScrollAmount.toDouble() / trackTravel.toDouble())
            changeScroll(event.deltaY * multiplier)
        }

        private fun changeScroll(delta: Double) {
            val next = scrollAmount + delta
            if (next.isFinite()) {
                setScrollAmount(next)
            } else if (0.0 < delta) {
                setScrollAmount(maxScrollAmount.toDouble())
            } else {
                setScrollAmount(0.0)
            }
        }

        private fun setScrollAmount(value: Double) {
            val next = value.coerceIn(0.0, maxScrollAmount.toDouble())
            if (scrollAmount == next) return
            scrollAmount = next
            invalidate(DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint))
        }

        private fun isOverScrollbar(position: IntOffset): Boolean {
            val x = scrollbarX(viewportSize.width)
            val right = Math.addExact(x, scrollbarSize.width)
            return x <= position.x && position.x <= right && 0 <= position.y && position.y < viewportSize.height
        }

        private fun scrollbarX(width: Int): Int {
            val contentLeft = Math.subtractExact(width, contentSize.width) / 2
            return Math.addExact(Math.addExact(contentLeft, contentSize.width), 8)
        }

        private fun scrollerHeight(height: Int): Int {
            val contentHeight = Math.addExact(contentSize.height, 4)
            val squared = Math.multiplyExact(height.toLong(), height.toLong())
            val natural = Math.toIntExact(squared / contentHeight.toLong())
            return natural.coerceIn(32, Math.subtractExact(height, 8))
        }

        private fun scrollbarY(
            height: Int,
            thumbHeight: Int,
        ): Int {
            val travel = Math.subtractExact(height, thumbHeight)
            val numerator = Math.multiplyExact(scrollAmount.toInt().toLong(), travel.toLong())
            return max(0, Math.toIntExact(numerator / maxScrollAmount.toLong()))
        }

        private fun paintHorizontalRepeat(
            scope: PaintScope,
            image: DrawImage,
            top: Int,
        ) {
            var left = 0
            while (left < scope.size.width) {
                val width = min(separatorSize.width, Math.subtractExact(scope.size.width, left))
                scope.blitImage(
                    image,
                    IntRect(0, 0, width, separatorSize.height),
                    IntRect(left, top, Math.addExact(left, width), Math.addExact(top, separatorSize.height)),
                )
                left = Math.addExact(left, width)
            }
        }

        private fun paintVerticalNineSlice(
            scope: PaintScope,
            image: DrawImage,
            left: Int,
            top: Int,
            height: Int,
        ) {
            if (height == 0) return
            if (height == scrollbarSize.height) {
                scope.blitImage(
                    image,
                    IntRect(0, 0, scrollbarSize.width, scrollbarSize.height),
                    IntRect(left, top, Math.addExact(left, scrollbarSize.width), Math.addExact(top, height)),
                )
                return
            }
            val border = min(1, height / 2)
            if (0 < border) {
                scope.blitImage(
                    image,
                    IntRect(0, 0, scrollbarSize.width, border),
                    IntRect(left, top, Math.addExact(left, scrollbarSize.width), Math.addExact(top, border)),
                )
            }
            val centerTop = Math.addExact(top, border)
            val centerHeight = Math.subtractExact(Math.subtractExact(height, border), border)
            paintVerticalRepeat(scope, image, left, centerTop, centerHeight, border, scrollbarSize.height - border)
            if (0 < border) {
                val bottom = Math.addExact(top, height)
                scope.blitImage(
                    image,
                    IntRect(0, scrollbarSize.height - border, scrollbarSize.width, scrollbarSize.height),
                    IntRect(left, bottom - border, Math.addExact(left, scrollbarSize.width), bottom),
                )
            }
        }

        private fun paintVerticalRepeat(
            scope: PaintScope,
            image: DrawImage,
            left: Int,
            top: Int,
            height: Int,
            sourceTop: Int,
            sourceBottom: Int,
        ) {
            if (height == 0) return
            val sourceHeight = Math.subtractExact(sourceBottom, sourceTop)
            var offset = 0
            while (offset < height) {
                val chunk = min(sourceHeight, Math.subtractExact(height, offset))
                val destinationTop = Math.addExact(top, offset)
                scope.blitImage(
                    image,
                    IntRect(0, sourceTop, scrollbarSize.width, Math.addExact(sourceTop, chunk)),
                    IntRect(
                        left,
                        destinationTop,
                        Math.addExact(left, scrollbarSize.width),
                        Math.addExact(destinationTop, chunk),
                    ),
                )
                offset = Math.addExact(offset, chunk)
            }
        }

        private fun paintScaledTiles(
            scope: PaintScope,
            image: DrawImage,
            size: IntSize,
            phaseX: Int,
            phaseY: Int,
        ) {
            val horizontal = scaledAxisSegments(size.width, phaseX, image.size.width)
            val vertical = scaledAxisSegments(size.height, phaseY, image.size.height)
            for (y in vertical) {
                for (x in horizontal) {
                    scope.blitImage(
                        image,
                        IntRect(x.sourceStart, y.sourceStart, x.sourceEnd, y.sourceEnd),
                        IntRect(x.destinationStart, y.destinationStart, x.destinationEnd, y.destinationEnd),
                    )
                }
            }
        }

        private fun scaledAxisSegments(
            size: Int,
            phase: Int,
            sourceExtent: Int,
        ): List<AxisSegment> {
            val scale = 2
            val tileExtent = Math.multiplyExact(sourceExtent, scale)
            val output = ArrayList<AxisSegment>()
            var tileStart = -Math.floorMod(phase, tileExtent)
            while (tileStart < size) {
                val tileEnd = Math.addExact(tileStart, tileExtent)
                val start = max(0, tileStart)
                val end = min(size, tileEnd)
                var cursor = start
                var sourceOffset = Math.subtractExact(cursor, tileStart)
                val remainder = sourceOffset % scale
                if (remainder != 0 && cursor < end) {
                    val length = min(scale - remainder, Math.subtractExact(end, cursor))
                    val source = sourceOffset / scale
                    output += AxisSegment(source, source + 1, cursor, Math.addExact(cursor, length))
                    cursor = Math.addExact(cursor, length)
                    sourceOffset = Math.addExact(sourceOffset, length)
                }
                val completePixels = Math.subtractExact(end, cursor) / scale
                if (0 < completePixels) {
                    val source = sourceOffset / scale
                    val length = Math.multiplyExact(completePixels, scale)
                    output += AxisSegment(source, Math.addExact(source, completePixels), cursor, Math.addExact(cursor, length))
                    cursor = Math.addExact(cursor, length)
                    sourceOffset = Math.addExact(sourceOffset, length)
                }
                if (cursor < end) {
                    val source = sourceOffset / scale
                    output += AxisSegment(source, source + 1, cursor, end)
                }
                tileStart = tileEnd
            }
            return output
        }

        private data class AxisSegment(
            val sourceStart: Int,
            val sourceEnd: Int,
            val destinationStart: Int,
            val destinationEnd: Int,
        )
    }

    /**
     * Owns the private element type token and constructor-only factory.
     */
    companion object {
        private val TYPE: ElementType<MinecraftScrollElement, Node> =
            ElementType(
                elementClass = MinecraftScrollElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.listBackground.size == IntSize(16, 16)) { "Minecraft Scroll list background must be 16 by 16 pixels." }
                    require(element.headerSeparator.size == IntSize(32, 2)) { "Minecraft Scroll header separator must be 32 by 2 pixels." }
                    require(element.footerSeparator.size == IntSize(32, 2)) { "Minecraft Scroll footer separator must be 32 by 2 pixels." }
                    require(element.scrollbarBackground.size == IntSize(6, 32)) { "Minecraft Scroll background sprite must be 6 by 32 pixels." }
                    require(element.scrollbarThumb.size == IntSize(6, 32)) { "Minecraft Scroll thumb sprite must be 6 by 32 pixels." }
                    require(0 < element.scrollRate) { "Minecraft Scroll rate must be positive." }
                    require(element.children.size == 1) { "Minecraft Scroll requires exactly one content root." }
                },
                createNode = { element ->
                    Node(
                        element.listBackground,
                        element.headerSeparator,
                        element.footerSeparator,
                        element.scrollbarBackground,
                        element.scrollbarThumb,
                        element.scrollRate,
                    )
                },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        /**
         * Creates one private Scroll description without exposing its constructor to Java callers.
         *
         * @param listBackground immutable 16 by 16 list texture.
         * @param headerSeparator immutable 32 by 2 header separator.
         * @param footerSeparator immutable 32 by 2 footer separator.
         * @param scrollbarBackground immutable 6 by 32 track sprite.
         * @param scrollbarThumb immutable 6 by 32 thumb sprite.
         * @param scrollRate positive logical wheel displacement multiplier.
         * @param content sole content root.
         * @param modifier active component behavior.
         * @param key optional stable sibling identity.
         * @return one immutable Scroll description.
         */
        @JvmSynthetic
        internal fun create(
            listBackground: DrawImage,
            headerSeparator: DrawImage,
            footerSeparator: DrawImage,
            scrollbarBackground: DrawImage,
            scrollbarThumb: DrawImage,
            scrollRate: Int,
            content: Element,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element =
            MinecraftScrollElement(
                listBackground,
                headerSeparator,
                footerSeparator,
                scrollbarBackground,
                scrollbarThumb,
                scrollRate,
                content,
                modifier,
                key,
            )
    }
}

/**
 * Creates one internal Scroll description through the private retained implementation.
 *
 * @param listBackground immutable 16 by 16 list texture.
 * @param headerSeparator immutable 32 by 2 header separator.
 * @param footerSeparator immutable 32 by 2 footer separator.
 * @param scrollbarBackground immutable 6 by 32 track sprite.
 * @param scrollbarThumb immutable 6 by 32 thumb sprite.
 * @param scrollRate positive logical wheel displacement multiplier.
 * @param content sole content root.
 * @param modifier active component behavior.
 * @param key optional stable sibling identity.
 * @return one immutable Scroll description.
 */
@JvmSynthetic
internal fun createMinecraftScrollElement(
    listBackground: DrawImage,
    headerSeparator: DrawImage,
    footerSeparator: DrawImage,
    scrollbarBackground: DrawImage,
    scrollbarThumb: DrawImage,
    scrollRate: Int,
    content: Element,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element =
    MinecraftScrollElement.create(
        listBackground,
        headerSeparator,
        footerSeparator,
        scrollbarBackground,
        scrollbarThumb,
        scrollRate,
        content,
        modifier,
        key,
    )
