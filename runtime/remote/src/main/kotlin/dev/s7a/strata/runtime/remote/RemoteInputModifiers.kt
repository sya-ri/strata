package dev.s7a.strata.runtime.remote

import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.focusable
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue

/**
 * Standard notifications with fixed client propagation behavior and server-owned Unit handlers.
 * Focus and hover transitions are interpreted locally; synchronous custom InputResult callbacks require an extension.
 */
internal object RemoteInputModifiers {
    /**
     * Registers built-in focus targets and event notifications before negotiation.
     */
    fun register(registry: RemoteRegistry) {
        registry.modifier(BuiltinProjection.Focusable.type, { ProjectionFields(it).finish() }) { _, _ -> Modifier.Empty.focusable() }
        registry.modifier(BuiltinProjection.InitialFocus.type, { ProjectionFields(it).finish() }) { _, _ -> Modifier.Empty.initialFocus() }
        registry.modifier(BuiltinProjection.Hover.type, ::endpoint) { endpoint, actions ->
            Modifier.Empty.onHover { event -> actions.send(endpoint, BuiltinProjection.Hover.type, ProjectionValue.Flag(event == PointerHoverEvent.Enter)) }
        }
        registry.modifier(BuiltinProjection.FocusChanged.type, ::endpoint) { endpoint, actions ->
            Modifier.Empty.onFocusChanged { event -> actions.send(endpoint, BuiltinProjection.FocusChanged.type, ProjectionValue.Flag(event == FocusEvent.Gained)) }
        }
    }

    private fun endpoint(value: ProjectionValue): Long = requireNotNull(value as? ProjectionValue.Integer).value.also { require(0 < it) }
}
