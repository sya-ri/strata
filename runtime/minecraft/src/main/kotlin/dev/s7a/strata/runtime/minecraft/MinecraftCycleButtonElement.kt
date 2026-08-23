@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.action.ActionDispatcher
import dev.s7a.strata.action.ComponentActions
import dev.s7a.strata.component.ComponentStateSubscription
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
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
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Private retained implementation of one finite profile-backed CycleButton.
 */
private class MinecraftCycleButtonElement private constructor(
    internal val normalSprite: MinecraftButtonSpriteSnapshot,
    internal val highlightedSprite: MinecraftButtonSpriteSnapshot,
    internal val disabledSprite: MinecraftButtonSpriteSnapshot,
    internal val state: CycleButtonState<*>,
    labels: List<Pair<MinecraftTextRun, MinecraftTextRun>>,
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
    internal val labels: List<Pair<MinecraftTextRun, MinecraftTextRun>> = Collections.unmodifiableList(labels.toList())

    @Suppress("TooManyFunctions") // The retained node implements the component's input, lifecycle, drawing, and semantics contracts.
    private class Node(
        initial: MinecraftCycleButtonElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        PointerInputNode,
        PointerHoverNode,
        KeyboardInputNode,
        FocusTargetNode,
        SemanticsNode,
        LifecycleNode {
        private var normalSprite: MinecraftButtonSpriteSnapshot? = initial.normalSprite
        private var highlightedSprite: MinecraftButtonSpriteSnapshot? = initial.highlightedSprite
        private var disabledSprite: MinecraftButtonSpriteSnapshot? = initial.disabledSprite
        private var state: CycleButtonState<*>? = initial.state
        private var labels: List<Pair<MinecraftTextRun, MinecraftTextRun>>? = initial.labels
        private var width = initial.width
        private var enabled = initial.enabled
        private var actions = initial.actions
        private var observer: ComponentStateSubscription? = null
        private var hovered = false
        private var focused = false
        private var disposed = false

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            check(scope.childCount == 0) { "Minecraft CycleButton cannot have children." }
            val size = IntSize(width, HEIGHT)
            require(constraints.isSatisfiedBy(size)) { "Minecraft CycleButton constraints must contain its requested width by 20." }
            return size
        }

        override fun paint(scope: PaintScope) {
            val sprite =
                when {
                    enabled.not() -> checkNotNull(disabledSprite)
                    hovered || focused -> checkNotNull(highlightedSprite)
                    else -> checkNotNull(normalSprite)
                }
            paintMinecraftButtonSprite(scope, sprite, width)
            val label = currentLabel()
            val text = if (enabled) label.first else label.second
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
                    cycleForward()
                    InputResult.Consumed
                }

                event is PointerEvent.Scroll && event.deltaY != 0.0 -> {
                    if (event.deltaY < 0.0) cycleBackward() else cycleForward()
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
                invalidate(DirtyMask.of(DirtyPhase.Paint))
            }
        }

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult {
            if (enabled.not() || event !is KeyboardEvent.Press) return InputResult.Ignored
            return when (event.key) {
                KeyCode.Left -> {
                    cycleBackward()
                    InputResult.Consumed
                }

                KeyCode.Right, KeyCode.Space, KeyCode.Enter -> {
                    cycleForward()
                    InputResult.Consumed
                }

                else -> {
                    InputResult.Ignored
                }
            }
        }

        override fun semantics(scope: SemanticsScope) {
            val label = currentLabel().first.text
            scope.emit(Semantics(label = label, role = SemanticsRole.CycleButton, value = label, disabled = enabled.not()))
        }

        override fun attach() {
            observer = checkNotNull(state).observe { invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)) }
        }

        override fun detach() {
            hovered = false
            focused = false
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            observer?.close()
            observer = null
            normalSprite = null
            highlightedSprite = null
            disabledSprite = null
            state = null
            labels = null
            hovered = false
            focused = false
        }

        internal fun updateFrom(current: MinecraftCycleButtonElement): DirtyMask {
            val geometryChanged = width != current.width
            val semanticsChanged = enabled != current.enabled || state !== current.state || labels != current.labels
            if (state !== current.state) {
                observer?.close()
                state = current.state
                observer = current.state.observe { invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)) }
            }
            normalSprite = current.normalSprite
            highlightedSprite = current.highlightedSprite
            disabledSprite = current.disabledSprite
            labels = current.labels
            width = current.width
            enabled = current.enabled
            actions = current.actions
            if (enabled.not()) {
                hovered = false
                focused = false
            }
            var dirty = DirtyMask.of(DirtyPhase.Paint)
            if (geometryChanged) dirty += DirtyMask.of(DirtyPhase.Measure)
            if (semanticsChanged) dirty += DirtyMask.of(DirtyPhase.Semantics)
            return dirty
        }

        private fun currentLabel(): Pair<MinecraftTextRun, MinecraftTextRun> {
            val currentState = checkNotNull(state)
            val index = currentState.values.indexOf(currentState.value)
            return checkNotNull(labels)[index]
        }

        private fun cycleForward() {
            @Suppress("UNCHECKED_CAST")
            val next = (checkNotNull(state) as CycleButtonState<Any>).next()
            actions.dispatch(ComponentActions.Cycle, next)
        }

        private fun cycleBackward() {
            @Suppress("UNCHECKED_CAST")
            val next = (checkNotNull(state) as CycleButtonState<Any>).previous()
            actions.dispatch(ComponentActions.Cycle, next)
        }
    }

    companion object {
        private const val HEIGHT = 20
        private const val TEXT_TOP = 6
        private val TYPE: ElementType<MinecraftCycleButtonElement, Node> =
            ElementType(
                elementClass = MinecraftCycleButtonElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.labels.size == element.state.values.size) { "CycleButton labels must match its values." }
                    require(element.labels.all { it.first.size.width <= element.width - 4 }) { "CycleButton labels must fit its width." }
                    require(element.children.isEmpty()) { "Minecraft CycleButton cannot have children." }
                },
                createNode = { element -> Node(element) },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        internal fun create(
            normalSprite: MinecraftButtonSpriteSnapshot,
            highlightedSprite: MinecraftButtonSpriteSnapshot,
            disabledSprite: MinecraftButtonSpriteSnapshot,
            state: CycleButtonState<*>,
            labels: List<Pair<MinecraftTextRun, MinecraftTextRun>>,
            width: Int,
            enabled: Boolean,
            actions: ActionDispatcher,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftCycleButtonElement(normalSprite, highlightedSprite, disabledSprite, state, labels, width, enabled, actions, modifier, key)
    }
}

/**
 * Creates one internal profile-backed CycleButton description.
 */
internal fun createMinecraftCycleButtonElement(
    normalSprite: MinecraftButtonSpriteSnapshot,
    highlightedSprite: MinecraftButtonSpriteSnapshot,
    disabledSprite: MinecraftButtonSpriteSnapshot,
    state: CycleButtonState<*>,
    labels: List<Pair<MinecraftTextRun, MinecraftTextRun>>,
    width: Int,
    enabled: Boolean,
    actions: ActionDispatcher,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element = MinecraftCycleButtonElement.create(normalSprite, highlightedSprite, disabledSprite, state, labels, width, enabled, actions, modifier, key)
