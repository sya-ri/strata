package dev.s7a.strata.runtime.remote

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.modifier.onDrag
import dev.s7a.strata.modifier.onKeyEvent
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onKeyRelease
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.onPreedit
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.onRelease
import dev.s7a.strata.modifier.onScroll
import dev.s7a.strata.modifier.onTextInput
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.ui.UiSession

/**
 * Installs only declared event listeners, using the same filters and propagation rules as local screens.
 */
internal object RemoteInputSubscriptions {
    /**
     * Registers the complete typed standard input family before capability negotiation.
     */
    fun register(registry: RemoteRegistry) {
        keyboard(registry, BuiltinProjection.KeyboardEvents) { result, filter, action -> Modifier.Empty.onKeyEvent(result, filter, action) }
        keyboard(registry, BuiltinProjection.KeyPress) { result, filter, action -> Modifier.Empty.onKeyPress(result, filter, action) }
        keyboard(registry, BuiltinProjection.KeyRelease) { result, filter, action -> Modifier.Empty.onKeyRelease(result, filter, action) }
        pointer(registry, BuiltinProjection.PointerEvents) { result, button, action ->
            require(button == null)
            Modifier.Empty.onPointerEvent(result, action)
        }
        pointer(registry, BuiltinProjection.PointerPress) { result, button, action -> Modifier.Empty.onPress(result, button, action) }
        pointer(registry, BuiltinProjection.PointerRelease) { result, button, action -> Modifier.Empty.onRelease(result, button, action) }
        pointer(registry, BuiltinProjection.PointerMove) { result, button, action ->
            require(button == null)
            Modifier.Empty.onMove(result, action)
        }
        pointer(registry, BuiltinProjection.PointerDrag) { result, button, action -> Modifier.Empty.onDrag(result, button, action) }
        pointer(registry, BuiltinProjection.PointerScroll) { result, button, action ->
            require(button == null)
            Modifier.Empty.onScroll(result, action)
        }
        text(registry, BuiltinProjection.TextInput) { result, action -> Modifier.Empty.onTextInput(result, action) }
        text(registry, BuiltinProjection.CharacterInput) { result, action -> Modifier.Empty.onCharacterInput(result, action) }
        text(registry, BuiltinProjection.PreeditInput) { result, action -> Modifier.Empty.onPreedit(result, action) }
        registry.modifier(BuiltinProjection.PointerCapture.type, { value ->
            val fields = ProjectionFields(value)
            val subscription = Triple(ProjectionInputCodec.button(fields.value()), endpoint(fields), endpoint(fields))
            fields.finish()
            subscription
        }) { (button, endpoint, cancellation), actions ->
            Modifier.Empty.onCapturedPointerEvent(button, { cancelled ->
                actions.send(cancellation, BuiltinProjection.PointerCapture.type, ProjectionInputCodec.button(cancelled))
            }) { event, position -> actions.send(endpoint, BuiltinProjection.PointerCapture.type, ProjectionInputCodec.pointer(event, position)) }
        }
    }

    private fun keyboard(
        registry: RemoteRegistry,
        type: BuiltinProjection,
        create: (InputResult, KeyboardInputFilter, UiSession.(KeyboardEvent) -> Unit) -> Modifier,
    ) {
        registry.modifier(type.type, { value ->
            val fields = ProjectionFields(value)
            val policy = ProjectionFields(fields.value())
            val result = propagation(policy)
            val filter = ProjectionInputCodec.filter(policy.value())
            policy.finish()
            val subscription = Triple(result, filter, endpoint(fields))
            fields.finish()
            subscription
        }) { (result, filter, endpoint), actions ->
            create(result, filter) { event -> actions.send(endpoint, type.type, ProjectionInputCodec.keyboard(event)) }
        }
    }

    private fun pointer(
        registry: RemoteRegistry,
        type: BuiltinProjection,
        create: (InputResult, PointerButton?, UiSession.(PointerEvent, IntOffset) -> Unit) -> Modifier,
    ) {
        registry.modifier(type.type, { value ->
            val fields = ProjectionFields(value)
            val policy = ProjectionFields(fields.value())
            val result = propagation(policy)
            val button = policy.value().let { if (it === ProjectionValue.Absent) null else ProjectionInputCodec.button(it) }
            policy.finish()
            val subscription = Triple(result, button, endpoint(fields))
            fields.finish()
            subscription
        }) { (result, button, endpoint), actions ->
            create(result, button) { event, position -> actions.send(endpoint, type.type, ProjectionInputCodec.pointer(event, position)) }
        }
    }

    private fun text(
        registry: RemoteRegistry,
        type: BuiltinProjection,
        create: (InputResult, UiSession.(TextInputEvent) -> Unit) -> Modifier,
    ) {
        registry.modifier(type.type, { value ->
            val fields = ProjectionFields(value)
            val subscription = propagation(fields) to endpoint(fields)
            fields.finish()
            subscription
        }) { (result, endpoint), actions -> create(result) { event -> actions.send(endpoint, type.type, ProjectionInputCodec.text(event)) } }
    }

    private fun propagation(fields: ProjectionFields): InputResult = InputResult.entries[fields.int(InputResult.entries.indices)]

    private fun endpoint(fields: ProjectionFields): Long = fields.long().also { require(0 < it) { "Invalid event endpoint." } }
}
