package dev.s7a.strata.runtime.remote

import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.focusable
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onDrag
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.onRelease
import dev.s7a.strata.modifier.onScroll
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
        callback(registry, BuiltinProjection.Release) { action -> Modifier.Empty.onRelease(action) }
        callback(registry, BuiltinProjection.Move) { action -> Modifier.Empty.onMove(action) }
        callback(registry, BuiltinProjection.Scroll) { action -> Modifier.Empty.onScroll(action) }
        callback(registry, BuiltinProjection.Drag) { action -> Modifier.Empty.onDrag(action) }
        registry.modifier(BuiltinProjection.Hover.type, ::endpoint) { endpoint, actions ->
            Modifier.Empty.onHover { event -> actions.send(endpoint, BuiltinProjection.Hover.type, ProjectionValue.Flag(event == PointerHoverEvent.Enter)) }
        }
        registry.modifier(BuiltinProjection.FocusChanged.type, ::endpoint) { endpoint, actions ->
            Modifier.Empty.onFocusChanged { event -> actions.send(endpoint, BuiltinProjection.FocusChanged.type, ProjectionValue.Flag(event == FocusEvent.Gained)) }
        }
    }

    private fun callback(
        registry: RemoteRegistry,
        type: BuiltinProjection,
        create: (() -> Unit) -> Modifier,
    ) {
        registry.modifier(type.type, ::endpoint) { endpoint, actions -> create { actions.send(endpoint, type.type, ProjectionValue.Absent) } }
    }

    private fun endpoint(value: ProjectionValue): Long = requireNotNull(value as? ProjectionValue.Integer).value.also { require(0 < it) }
}
