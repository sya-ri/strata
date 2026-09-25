package dev.s7a.strata.projection

import dev.s7a.strata.ui.UiSession

/**
 * Server-owned typed event endpoint.
 * The decoder validates untrusted detached data before the handler receives a value.
 * The runtime owns this endpoint only while its declaring node and generation remain active.
 */
public class ProjectionAction<T : Any>(
    public val type: ProjectionType,
    private val decode: (ProjectionValue) -> T,
    private val handle: UiSession.(T) -> Unit,
) {
    /**
     * Validates and delivers one event on the owning session thread.
     */
    public fun dispatch(
        session: UiSession,
        value: ProjectionValue,
    ) {
        handle(session, decode(value))
    }

    /**
     * Validates untrusted data before returning an owner-thread operation that invokes the handler.
     * A decoder failure never invokes the handler.
     */
    public fun prepare(value: ProjectionValue): UiSession.() -> Unit {
        val decoded = decode(value)
        return { handle(decoded) }
    }
}
