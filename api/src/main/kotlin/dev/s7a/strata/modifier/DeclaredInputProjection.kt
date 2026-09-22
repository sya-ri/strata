package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionValue

/**
 * Projects fixed input subscriptions and revalidates their filters before server handlers run.
 */
internal object DeclaredInputProjection {
    /**
     * Retains the typed handler while transferring only filter, propagation, and endpoint identity.
     */
    fun <E : KeyboardEvent> keyboard(
        type: BuiltinProjection,
        propagation: InputResult,
        filter: KeyboardInputFilter,
        select: (KeyboardEvent) -> E,
        action: (E) -> Unit,
    ): DeclarationProjection<*> {
        val policy = fields(result(propagation), ProjectionInputCodec.filter(filter))
        return DeclarationProjection(type.type, action) { handler, scope ->
            val endpoint =
                scope.action(
                    ProjectionAction(type.type, { value ->
                        val event = select(ProjectionInputCodec.keyboard(value))
                        require(filter.matches(event)) { "Keyboard event does not match its subscription." }
                        event
                    }, handler),
                    policy,
                )
            fields(policy, ProjectionValue.Integer(endpoint))
        }
    }

    /**
     * Carries complete pointer data and rejects event variants or buttons outside the current subscription.
     */
    fun <E : PointerEvent> pointer(
        type: BuiltinProjection,
        propagation: InputResult,
        button: PointerButton?,
        select: (PointerEvent) -> E,
        action: (E, IntOffset) -> Unit,
    ): DeclarationProjection<*> {
        val policy = fields(result(propagation), button?.let(ProjectionInputCodec::button) ?: ProjectionValue.Absent)
        return DeclarationProjection(type.type, action) { handler, scope ->
            val endpoint =
                scope.action(
                    ProjectionAction(type.type, { value ->
                        val (event, position) = ProjectionInputCodec.pointer(value)
                        val selected = select(event)
                        require(matches(selected, button)) { "Pointer event does not match its subscription." }
                        selected to position
                    }) { (event, position) -> handler(event, position) },
                    policy,
                )
            fields(policy, ProjectionValue.Integer(endpoint))
        }
    }

    /**
     * Uses the existing Unicode and composition contracts for typed text notifications.
     */
    fun <E : TextInputEvent> text(
        type: BuiltinProjection,
        propagation: InputResult,
        select: (TextInputEvent) -> E,
        action: (E) -> Unit,
    ): DeclarationProjection<*> {
        val policy = result(propagation)
        return DeclarationProjection(type.type, action) { handler, scope ->
            val endpoint = scope.action(ProjectionAction(type.type, { select(ProjectionInputCodec.text(it)) }, handler), policy)
            fields(policy, ProjectionValue.Integer(endpoint))
        }
    }

    /**
     * Subscribes to one captured-button gesture and its typed cancellation notification.
     */
    fun capture(
        button: PointerButton,
        onCancel: (PointerButton) -> Unit,
        action: (PointerEvent, IntOffset) -> Unit,
    ): DeclarationProjection<*> {
        val type = BuiltinProjection.PointerCapture.type
        val policy = ProjectionInputCodec.button(button)
        return DeclarationProjection(type, action to onCancel) { (handler, cancel), scope ->
            val endpoint =
                scope.action(
                    ProjectionAction(type, { value ->
                        ProjectionInputCodec.pointer(value).also { (event, _) -> require(matches(event, button)) { "Captured event uses another button." } }
                    }) { (event, position) -> handler(event, position) },
                    policy,
                )
            val cancellation =
                scope.action(
                    ProjectionAction(type, { value ->
                        ProjectionInputCodec.button(value).also { require(it == button) { "Cancellation uses another button." } }
                    }, cancel),
                    policy,
                )
            fields(policy, ProjectionValue.Integer(endpoint), ProjectionValue.Integer(cancellation))
        }
    }

    /**
     * Movement and scrolling carry no button; button-bearing events must match when a filter is present.
     */
    fun matches(
        event: PointerEvent,
        button: PointerButton?,
    ): Boolean =
        button == null ||
            when (event) {
                is PointerEvent.Press -> event.button == button
                is PointerEvent.Release -> event.button == button
                is PointerEvent.Drag -> event.button == button
                is PointerEvent.Move, is PointerEvent.Scroll -> true
            }

    private fun result(value: InputResult): ProjectionValue = ProjectionValue.Integer(value.ordinal.toLong())

    private fun fields(vararg values: ProjectionValue): ProjectionValue = ProjectionValue.Sequence(values.toList())
}
