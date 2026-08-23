@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ScrollMetrics
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.ScrollStateObserver
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
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.max
import kotlin.math.min
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal independent Minecraft scrollbar linked to caller-owned scroll state.
 */
private class MinecraftScrollbarElement private constructor(
    internal val background: DrawImage,
    internal val thumb: DrawImage,
    internal val state: ScrollState,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = emptyList(),
        modifier = modifier,
    ) {
    private class Node(
        initialBackground: DrawImage,
        initialThumb: DrawImage,
        initialState: ScrollState,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        PointerInputNode,
        LifecycleNode {
        private val spriteSize = IntSize(6, 32)
        private var background: DrawImage? = initialBackground
        private var thumb: DrawImage? = initialThumb
        private var state: ScrollState? = initialState
        private var observer: ScrollStateObserver? = null
        private var size = IntSize.Zero
        private var dragging = false
        private var disposed = false

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 0) { "Minecraft Scrollbar cannot have children." }
            require(constraints.maxHeight != Int.MAX_VALUE) { "Minecraft Scrollbar height must be bounded." }
            val measured = constraints.constrain(IntSize(spriteSize.width, constraints.maxHeight))
            if (checkNotNull(state).metrics.canScroll) {
                require(40 <= measured.height) { "A scrollable Minecraft Scrollbar requires at least 40 logical pixels of height." }
            }
            size = measured
            return measured
        }

        override fun paint(scope: PaintScope) {
            val metrics = checkNotNull(state).metrics
            if (metrics.canScroll.not() || scope.size.width == 0 || scope.size.height == 0) return
            paintVerticalNineSlice(scope, checkNotNull(background), 0, scope.size.height)
            val thumbHeight = thumbHeight(scope.size.height, metrics)
            paintVerticalNineSlice(scope, checkNotNull(thumb), thumbTop(scope.size.height, thumbHeight, metrics), thumbHeight)
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            when (event) {
                is PointerEvent.Press -> {
                    if (event.button === PointerButton.Primary && checkNotNull(state).metrics.canScroll) {
                        dragging = true
                        InputResult.Consumed
                    } else {
                        InputResult.Ignored
                    }
                }

                is PointerEvent.Drag -> {
                    if (dragging && event.button === PointerButton.Primary) {
                        drag(event, localPosition)
                        InputResult.Consumed
                    } else {
                        InputResult.Ignored
                    }
                }

                is PointerEvent.Release -> {
                    if (dragging && event.button === PointerButton.Primary) {
                        dragging = false
                        InputResult.Consumed
                    } else {
                        InputResult.Ignored
                    }
                }

                is PointerEvent.Move,
                is PointerEvent.Scroll,
                -> {
                    InputResult.Ignored
                }
            }

        override fun attach() {
            observer = checkNotNull(state).observe { invalidate(DirtyMask.of(DirtyPhase.Paint)) }
        }

        override fun detach() {
            dragging = false
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            dragging = false
            observer?.close()
            observer = null
            state = null
            background = null
            thumb = null
        }

        internal fun updateFrom(current: MinecraftScrollbarElement): DirtyMask {
            val imagesChanged = background != current.background || thumb != current.thumb
            if (state !== current.state) {
                observer?.close()
                state = current.state
                observer = current.state.observe { invalidate(DirtyMask.of(DirtyPhase.Paint)) }
            }
            background = current.background
            thumb = current.thumb
            return if (imagesChanged) DirtyMask.of(DirtyPhase.Paint) else DirtyMask.None
        }

        private fun drag(
            event: PointerEvent.Drag,
            localPosition: IntOffset,
        ) {
            val currentState = checkNotNull(state)
            val currentObserver = checkNotNull(observer)
            val metrics = currentState.metrics
            if (localPosition.y < 0) {
                currentState.scrollTo(0.0, currentObserver)
                invalidate(DirtyMask.of(DirtyPhase.Paint))
                return
            }
            if (size.height < localPosition.y) {
                currentState.scrollTo(metrics.maximumOffset, currentObserver)
                invalidate(DirtyMask.of(DirtyPhase.Paint))
                return
            }
            val travel = Math.subtractExact(size.height, thumbHeight(size.height, metrics))
            val multiplier = max(1.0, metrics.maximumOffset / travel.toDouble())
            currentState.scrollBy(event.deltaY * multiplier, currentObserver)
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }

        private fun thumbHeight(
            height: Int,
            metrics: ScrollMetrics,
        ): Int {
            val squared = Math.multiplyExact(height.toLong(), height.toLong())
            val natural = Math.toIntExact(squared / metrics.contentExtent.toLong())
            return natural.coerceIn(32, Math.subtractExact(height, 8))
        }

        private fun thumbTop(
            height: Int,
            thumbHeight: Int,
            metrics: ScrollMetrics,
        ): Int {
            val travel = Math.subtractExact(height, thumbHeight)
            val fraction = metrics.offset / metrics.maximumOffset
            return max(0, (fraction * travel.toDouble()).toInt())
        }

        private fun paintVerticalNineSlice(
            scope: PaintScope,
            image: DrawImage,
            top: Int,
            height: Int,
        ) {
            if (height == 0) return
            if (height == spriteSize.height) {
                scope.blitImage(image, IntRect(0, 0, spriteSize.width, spriteSize.height), IntRect(0, top, spriteSize.width, top + height))
                return
            }
            val border = min(1, height / 2)
            if (0 < border) {
                scope.blitImage(image, IntRect(0, 0, spriteSize.width, border), IntRect(0, top, spriteSize.width, top + border))
            }
            var destinationTop = top + border
            val centerBottom = top + height - border
            while (destinationTop < centerBottom) {
                val chunk = min(spriteSize.height - border * 2, centerBottom - destinationTop)
                scope.blitImage(
                    image,
                    IntRect(0, border, spriteSize.width, border + chunk),
                    IntRect(0, destinationTop, spriteSize.width, destinationTop + chunk),
                )
                destinationTop += chunk
            }
            if (0 < border) {
                val bottom = top + height
                scope.blitImage(
                    image,
                    IntRect(0, spriteSize.height - border, spriteSize.width, spriteSize.height),
                    IntRect(0, bottom - border, spriteSize.width, bottom),
                )
            }
        }
    }

    companion object {
        private val TYPE: ElementType<MinecraftScrollbarElement, Node> =
            ElementType(
                elementClass = MinecraftScrollbarElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.background.size == IntSize(6, 32)) { "Minecraft Scrollbar background must be 6 by 32 pixels." }
                    require(element.thumb.size == IntSize(6, 32)) { "Minecraft Scrollbar thumb must be 6 by 32 pixels." }
                    require(element.children.isEmpty()) { "Minecraft Scrollbar cannot have children." }
                },
                createNode = { element -> Node(element.background, element.thumb, element.state) },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        internal fun create(
            background: DrawImage,
            thumb: DrawImage,
            state: ScrollState,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftScrollbarElement(background, thumb, state, modifier, key)
    }
}

/**
 * Creates one independent profile-backed scrollbar description.
 */
internal fun createMinecraftScrollbarElement(
    background: DrawImage,
    thumb: DrawImage,
    state: ScrollState,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftScrollbarElement.create(background, thumb, state, modifier, key)
