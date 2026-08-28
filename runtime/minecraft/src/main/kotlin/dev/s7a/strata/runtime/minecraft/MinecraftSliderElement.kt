@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.action.ActionDispatcher
import dev.s7a.strata.action.ComponentActions
import dev.s7a.strata.component.ComponentStateSubscription
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Private retained implementation of one native-profile horizontal Slider.
 */
private class MinecraftSliderElement private constructor(
    internal val normalTrack: MinecraftButtonSpriteSnapshot,
    internal val highlightedTrack: MinecraftButtonSpriteSnapshot,
    internal val normalHandle: DrawImage,
    internal val highlightedHandle: DrawImage,
    internal val normalText: MinecraftTextRun,
    internal val inactiveText: MinecraftTextRun,
    internal val label: UiText,
    internal val state: SliderState,
    internal val width: Int,
    internal val enabled: Boolean,
    internal val actions: ActionDispatcher,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = emptyList(),
        modifier = modifier,
    ) {
    @Suppress("TooManyFunctions") // The retained node implements the component's input, lifecycle, drawing, and semantics contracts.
    private class Node(
        initial: MinecraftSliderElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        PointerInputNode,
        PointerHoverNode,
        KeyboardInputNode,
        FocusTargetNode,
        SemanticsNode,
        LifecycleNode {
        private var normalTrack: MinecraftButtonSpriteSnapshot? = initial.normalTrack
        private var highlightedTrack: MinecraftButtonSpriteSnapshot? = initial.highlightedTrack
        private var normalHandle: DrawImage? = initial.normalHandle
        private var highlightedHandle: DrawImage? = initial.highlightedHandle
        private var normalText: MinecraftTextRun? = initial.normalText
        private var inactiveText: MinecraftTextRun? = initial.inactiveText
        private var label: UiText? = initial.label
        private var state: SliderState? = initial.state
        private var width = initial.width
        private var enabled = initial.enabled
        private var actions = initial.actions
        private var observer: ComponentStateSubscription? = null
        private var hovered = false
        private var focused = false
        private var dragging = false
        private var disposed = false

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 0) { "Minecraft Slider cannot have children." }
            val size = IntSize(width, HEIGHT)
            require(constraints.isSatisfiedBy(size)) { "Minecraft Slider constraints must contain its requested width by 20." }
            return size
        }

        override fun paint(scope: PaintScope) {
            val emphasized = enabled && (hovered || dragging)
            paintMinecraftButtonSprite(scope, if (focused && dragging.not()) checkNotNull(highlightedTrack) else checkNotNull(normalTrack), width)
            val handle = if (emphasized) checkNotNull(highlightedHandle) else checkNotNull(normalHandle)
            val left = (checkNotNull(state).fraction * (width - HANDLE_WIDTH).toDouble()).toInt()
            scope.blitImage(handle, IntRect(0, 0, HANDLE_WIDTH, HEIGHT), IntRect(left, 0, left + HANDLE_WIDTH, HEIGHT))
            val text = if (enabled) checkNotNull(normalText) else checkNotNull(inactiveText)
            text.paint(scope, (width - text.size.width) / 2, TEXT_TOP)
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            when {
                enabled.not() -> {
                    InputResult.Ignored
                }

                event is PointerEvent.Press && event.button === PointerButton.Primary -> {
                    dragging = true
                    setFromPointer(localPosition.x)
                    InputResult.Consumed
                }

                event is PointerEvent.Drag && dragging && event.button === PointerButton.Primary -> {
                    setFromPointer(localPosition.x)
                    InputResult.Consumed
                }

                event is PointerEvent.Release && dragging && event.button === PointerButton.Primary -> {
                    dragging = false
                    invalidate(DirtyMask.of(DirtyPhase.Paint))
                    InputResult.Consumed
                }

                else -> {
                    InputResult.Ignored
                }
            }

        override fun onPointerHover(hovered: Boolean) {
            val next = enabled && hovered
            if (this.hovered != next) {
                this.hovered = next
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        override val acceptsFocus: Boolean
            get() = enabled

        override fun onFocusChanged(focused: Boolean) {
            if (this.focused != focused) {
                this.focused = focused
                if (focused.not()) dragging = false
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult {
            if (enabled.not() || event !is KeyboardEvent.Press) return InputResult.Ignored
            val direction =
                when (event.key) {
                    KeyCode.Left -> -1
                    KeyCode.Right -> 1
                    else -> return InputResult.Ignored
                }
            val currentState = checkNotNull(state)
            val intervals = if (currentState.steps == 0) 100 else currentState.steps + 1
            val amount = (currentState.range.endInclusive - currentState.range.start) / intervals.toDouble()
            updateValue(currentState.value + amount * direction.toDouble())
            return InputResult.Consumed
        }

        override fun semantics(scope: SemanticsScope) {
            val currentState = checkNotNull(state)
            scope.emit(
                Semantics(
                    label = checkNotNull(label),
                    role = SemanticsRole.Slider,
                    value = UiText.Literal(currentState.value.toString()),
                    disabled = enabled.not(),
                ),
            )
        }

        override fun attach() {
            observer = checkNotNull(state).observe { invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)) }
        }

        override fun detach() {
            hovered = false
            focused = false
            dragging = false
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            observer?.close()
            observer = null
            normalTrack = null
            highlightedTrack = null
            normalHandle = null
            highlightedHandle = null
            normalText = null
            inactiveText = null
            label = null
            state = null
            hovered = false
            focused = false
            dragging = false
        }

        internal fun updateFrom(current: MinecraftSliderElement): DirtyMask {
            val geometryChanged = width != current.width
            val semanticsChanged = label != current.label || enabled != current.enabled || state !== current.state
            if (state !== current.state) {
                observer?.close()
                state = current.state
                observer = current.state.observe { invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)) }
            }
            normalTrack = current.normalTrack
            highlightedTrack = current.highlightedTrack
            normalHandle = current.normalHandle
            highlightedHandle = current.highlightedHandle
            normalText = current.normalText
            inactiveText = current.inactiveText
            label = current.label
            width = current.width
            enabled = current.enabled
            actions = current.actions
            if (enabled.not()) {
                hovered = false
                focused = false
                dragging = false
            }
            var dirty = DirtyMask.of(DirtyPhase.Paint)
            if (geometryChanged) dirty += DirtyMask.of(DirtyPhase.Measure)
            if (semanticsChanged) dirty += DirtyMask.of(DirtyPhase.Semantics)
            return dirty
        }

        private fun setFromPointer(x: Int) {
            val fraction = (x.toDouble() - HANDLE_WIDTH / 2.0) / (width - HANDLE_WIDTH).toDouble()
            val currentState = checkNotNull(state)
            updateValue(currentState.range.start + fraction.coerceIn(0.0, 1.0) * (currentState.range.endInclusive - currentState.range.start))
        }

        private fun updateValue(value: Double) {
            val currentState = checkNotNull(state)
            val before = currentState.value
            currentState.value = value
            val next = currentState.value
            if (before != next) actions.dispatch(ComponentActions.SliderChange, next)
        }
    }

    companion object {
        private const val HEIGHT = 20
        private const val HANDLE_WIDTH = 8
        private const val TEXT_TOP = 6
        private val TYPE: ElementType<MinecraftSliderElement, Node> =
            ElementType(
                elementClass = MinecraftSliderElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.width in (HANDLE_WIDTH + 1)..200) { "Minecraft Slider width must be greater than 8 and no larger than 200." }
                    require(element.normalHandle.size == IntSize(HANDLE_WIDTH, HEIGHT)) { "Minecraft Slider handles must be 8 by 20 pixels." }
                    require(element.highlightedHandle.size == IntSize(HANDLE_WIDTH, HEIGHT)) { "Minecraft Slider handles must be 8 by 20 pixels." }
                    require(element.normalText.size.width <= element.width - 4) { "Minecraft Slider label must fit its width." }
                },
                createNode = { element -> Node(element) },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        internal fun create(
            normalTrack: MinecraftButtonSpriteSnapshot,
            highlightedTrack: MinecraftButtonSpriteSnapshot,
            normalHandle: DrawImage,
            highlightedHandle: DrawImage,
            normalText: MinecraftTextRun,
            inactiveText: MinecraftTextRun,
            label: UiText,
            state: SliderState,
            width: Int,
            enabled: Boolean,
            actions: ActionDispatcher,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element =
            MinecraftSliderElement(
                normalTrack,
                highlightedTrack,
                normalHandle,
                highlightedHandle,
                normalText,
                inactiveText,
                label,
                state,
                width,
                enabled,
                actions,
                modifier,
                key,
            )
    }
}

/**
 * Creates one internal profile-backed Slider description.
 */
internal fun createMinecraftSliderElement(
    normalTrack: MinecraftButtonSpriteSnapshot,
    highlightedTrack: MinecraftButtonSpriteSnapshot,
    normalHandle: DrawImage,
    highlightedHandle: DrawImage,
    normalText: MinecraftTextRun,
    inactiveText: MinecraftTextRun,
    label: UiText,
    state: SliderState,
    width: Int,
    enabled: Boolean,
    actions: ActionDispatcher,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element =
    MinecraftSliderElement.create(
        normalTrack,
        highlightedTrack,
        normalHandle,
        highlightedHandle,
        normalText,
        inactiveText,
        label,
        state,
        width,
        enabled,
        actions,
        modifier,
        key,
    )
