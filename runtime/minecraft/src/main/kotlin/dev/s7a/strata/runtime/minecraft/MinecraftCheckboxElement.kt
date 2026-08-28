@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.action.ActionDispatcher
import dev.s7a.strata.action.ComponentActions
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.ComponentStateSubscription
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
 * Private retained implementation of one native-profile Checkbox.
 */
private class MinecraftCheckboxElement private constructor(
    internal val normal: DrawImage,
    internal val highlighted: DrawImage,
    internal val selected: DrawImage,
    internal val selectedHighlighted: DrawImage,
    internal val normalText: MinecraftTextRun,
    internal val inactiveText: MinecraftTextRun,
    internal val label: UiText,
    internal val state: CheckboxState,
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
        initial: MinecraftCheckboxElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        PointerInputNode,
        PointerHoverNode,
        KeyboardInputNode,
        FocusTargetNode,
        SemanticsNode,
        LifecycleNode {
        private var normal: DrawImage? = initial.normal
        private var highlighted: DrawImage? = initial.highlighted
        private var selected: DrawImage? = initial.selected
        private var selectedHighlighted: DrawImage? = initial.selectedHighlighted
        private var normalText: MinecraftTextRun? = initial.normalText
        private var inactiveText: MinecraftTextRun? = initial.inactiveText
        private var label: UiText? = initial.label
        private var state: CheckboxState? = initial.state
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
            check(scope.childCount == 0) { "Minecraft Checkbox cannot have children." }
            val size = IntSize(width, BOX_SIZE)
            require(constraints.isSatisfiedBy(size)) { "Minecraft Checkbox constraints must contain its requested width by 20." }
            return size
        }

        override fun paint(scope: PaintScope) {
            val checked = checkNotNull(state).checked
            val emphasized = enabled && (hovered || focused)
            val image =
                when {
                    checked && emphasized -> checkNotNull(selectedHighlighted)
                    checked -> checkNotNull(selected)
                    emphasized -> checkNotNull(highlighted)
                    else -> checkNotNull(normal)
                }
            scope.blitImage(image, IntRect(0, 0, BOX_SIZE, BOX_SIZE), IntRect(0, 0, BOX_SIZE, BOX_SIZE))
            val text = if (enabled) checkNotNull(normalText) else checkNotNull(inactiveText)
            text.paint(scope, BOX_SIZE + SPACING, (BOX_SIZE - text.size.height) / 2)
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            if (event is PointerEvent.Press && event.button === PointerButton.Primary && enabled) {
                activate()
                InputResult.Consumed
            } else {
                InputResult.Ignored
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
            val isActivationKey = event is KeyboardEvent.Press && (event.key == KeyCode.Space || event.key == KeyCode.Enter)
            return if (enabled && isActivationKey) {
                activate()
                InputResult.Consumed
            } else {
                InputResult.Ignored
            }
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(
                Semantics(
                    label = checkNotNull(label),
                    role = SemanticsRole.Checkbox,
                    disabled = enabled.not(),
                    checked = checkNotNull(state).checked,
                ),
            )
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
            normal = null
            highlighted = null
            selected = null
            selectedHighlighted = null
            normalText = null
            inactiveText = null
            label = null
            state = null
            hovered = false
            focused = false
        }

        internal fun updateFrom(current: MinecraftCheckboxElement): DirtyMask {
            val geometryChanged = width != current.width
            val semanticsChanged = label != current.label || enabled != current.enabled || state !== current.state
            if (state !== current.state) {
                observer?.close()
                state = current.state
                observer = current.state.observe { invalidate(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)) }
            }
            normal = current.normal
            highlighted = current.highlighted
            selected = current.selected
            selectedHighlighted = current.selectedHighlighted
            normalText = current.normalText
            inactiveText = current.inactiveText
            label = current.label
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

        private fun activate() {
            val next = checkNotNull(state).toggle()
            actions.dispatch(ComponentActions.CheckedChange, next)
        }
    }

    companion object {
        private const val BOX_SIZE = 20
        private const val SPACING = 4
        private val TYPE: ElementType<MinecraftCheckboxElement, Node> =
            ElementType(
                elementClass = MinecraftCheckboxElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(element.normal.size == IntSize(BOX_SIZE, BOX_SIZE)) { "Minecraft Checkbox sprites must be 20 by 20 pixels." }
                    require(element.highlighted.size == IntSize(BOX_SIZE, BOX_SIZE)) { "Minecraft Checkbox sprites must be 20 by 20 pixels." }
                    require(element.selected.size == IntSize(BOX_SIZE, BOX_SIZE)) { "Minecraft Checkbox sprites must be 20 by 20 pixels." }
                    require(element.selectedHighlighted.size == IntSize(BOX_SIZE, BOX_SIZE)) { "Minecraft Checkbox sprites must be 20 by 20 pixels." }
                    require(BOX_SIZE + SPACING + element.normalText.size.width <= element.width) { "Minecraft Checkbox label must fit its width." }
                    require(element.children.isEmpty()) { "Minecraft Checkbox cannot have children." }
                },
                createNode = { element -> Node(element) },
                updateNode = { _, current, node -> node.updateFrom(current) },
            )

        internal fun create(
            normal: DrawImage,
            highlighted: DrawImage,
            selected: DrawImage,
            selectedHighlighted: DrawImage,
            normalText: MinecraftTextRun,
            inactiveText: MinecraftTextRun,
            label: UiText,
            state: CheckboxState,
            width: Int,
            enabled: Boolean,
            actions: ActionDispatcher,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element =
            MinecraftCheckboxElement(
                normal,
                highlighted,
                selected,
                selectedHighlighted,
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
 * Creates one internal profile-backed Checkbox description.
 */
internal fun createMinecraftCheckboxElement(
    normal: DrawImage,
    highlighted: DrawImage,
    selected: DrawImage,
    selectedHighlighted: DrawImage,
    normalText: MinecraftTextRun,
    inactiveText: MinecraftTextRun,
    label: UiText,
    state: CheckboxState,
    width: Int,
    enabled: Boolean,
    actions: ActionDispatcher,
    modifier: Modifier,
    key: ElementKey<*>?,
): Element =
    MinecraftCheckboxElement.create(
        normal,
        highlighted,
        selected,
        selectedHighlighted,
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
