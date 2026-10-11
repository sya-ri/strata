@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.fixture

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
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.node.TextInputNode
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText

/**
 * Public third-party leaf SPI control preserving focus, pointer capture, hover, keyboard, text and flattened semantics.
 */
public object EmptyChildInputControl {
    /**
     * Exercises compatible redeclaration during active focus and capture, plus terminal input cancellation.
     */
    public fun verify() {
        val state = mutableStateOf(false)
        val probe = Probe()
        val session = createRuntimeUiSession { InputElement(probe, state.value) }
        session.use {
            session.attach()
            val initial = session.frame(Constraints())
            check(
                initial.semantics
                    .single()
                    .semantics.label == UiText.Literal("leaf"),
            ) { "Initial flattened semantics label: ${initial.semantics}" }
            check(probe.focus == listOf(true)) { "Initial focus callbacks: ${probe.focus}" }
            val focus = checkNotNull(session.textInputFocus) { "Initial editable focus must be present" }
            check(session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) == InputResult.Consumed) { "Initial pointer move must be consumed" }
            check(session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary)) == InputResult.Consumed) { "Primary press must be consumed" }
            check(probe.acquisitions == 1 && probe.hover.last()) { "Capture acquisitions=${probe.acquisitions}, hover=${probe.hover}" }
            state.value = true
            val changed = session.frame(Constraints())
            check(
                changed.semantics
                    .single()
                    .semantics.value == UiText.Literal("true"),
            ) { "Changed flattened semantics value: ${changed.semantics}" }
            check(session.textInputFocus == focus && probe.focus == listOf(true)) { "Retained editable focus=${session.textInputFocus}, callbacks=${probe.focus}" }
            check(probe.updates == 1 && probe.validations == 2 && probe.creations == 1) { "Compatible work: updates=${probe.updates}, validations=${probe.validations}, creations=${probe.creations}" }
            check(session.dispatchPointer(PointerEvent.Drag(IntOffset(5, 6), PointerButton.Primary, 5.0, 6.0)) == InputResult.Consumed) { "Captured drag must be consumed outside the leaf" }
            check(probe.positions.last() == IntOffset(5, 6)) { "Captured local pointer positions: ${probe.positions}" }
            check(session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)) == InputResult.Consumed) { "Focused Enter key must be consumed" }
            check(session.dispatchTextInput(TextInputEvent.Character(65)) == InputResult.Consumed) { "Focused text character must be consumed" }
            check(probe.keys == 1 && probe.text == 1 && probe.cancellations == 0) { "Active input: keys=${probe.keys}, text=${probe.text}, cancellations=${probe.cancellations}" }
            session.close()
            check(probe.cancellations == 1 && probe.focus == listOf(true, false) && probe.hover.last().not()) { "Terminal input: cancellations=${probe.cancellations}, focus=${probe.focus}, hover=${probe.hover}" }
            session.close()
            check(probe.cancellations == 1) { "Repeated close cancellation count: ${probe.cancellations}" }
        }
    }

    /**
     * Independent observations of actual public input callbacks.
     */
    private class Probe {
        val focus = mutableListOf<Boolean>()
        val hover = mutableListOf<Boolean>()
        val positions = mutableListOf<IntOffset>()
        var acquisitions = 0
        var cancellations = 0
        var keys = 0
        var text = 0
        var updates = 0
        var validations = 0
        var creations = 0
    }

    /**
     * Fresh description with a stable type/key and a changed semantics field.
     */
    private class InputElement(
        val probe: Probe,
        val value: Boolean,
    ) : Element(ElementIdentity.Keyed(ElementKey(1)), type)

    /**
     * Custom capability node; the runtime never dispatches on this concrete class.
     */
    private class InputNode(
        private val probe: Probe,
        var value: Boolean,
    ) : Node(),
        MeasureNode,
        FocusTargetNode,
        PointerCaptureNode,
        PointerHoverNode,
        KeyboardInputNode,
        TextInputNode,
        SemanticsNode {
        override val acceptsFocus: Boolean get() = true
        override val requestsInitialFocus: Boolean get() = true
        override val requiresTextInput: Boolean get() = true

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(1, 1))

        override fun onFocusChanged(focused: Boolean) {
            probe.focus.add(focused)
        }

        override fun onPointerHover(hovered: Boolean) {
            probe.hover.add(hovered)
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            probe.positions.add(localPosition)
            return InputResult.Consumed
        }

        override fun onPointerCaptureAcquired(button: PointerButton) {
            probe.acquisitions += 1
        }

        override fun onPointerCaptureCancelled(button: PointerButton) {
            probe.cancellations += 1
        }

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult {
            probe.keys += 1
            return InputResult.Consumed
        }

        override fun onTextInput(event: TextInputEvent): InputResult {
            probe.text += 1
            return InputResult.Consumed
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(label = UiText.Literal("leaf"), value = UiText.Literal(value.toString())))
        }
    }

    private val type =
        ElementType(
            InputElement::class,
            InputNode::class,
            { it.probe.validations += 1 },
            {
                it.probe.creations += 1
                InputNode(it.probe, it.value)
            },
            { _, current, node ->
                current.probe.updates += 1
                node.value = current.value
                DirtyMask.All
            },
        )
}
