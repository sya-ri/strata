package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent

/**
 * Application-owned untimed event interpretation; the shared debug launcher owns process and request lifetimes.
 */
internal interface RemoteProbeObservation {
    /**
     * Detached complete case rows, with no references to production objects or debugger mirrors.
     */
    val rows: JsonArray

    /**
     * Whether observed production requests should be enabled for the current operation.
     */
    val active: Boolean

    /**
     * Reads one suspended method entry without changing the debuggee or retaining its stack frame.
     */
    fun enter(event: MethodEntryEvent)

    /**
     * Interprets a successful return while its object or primitive identity is still available.
     */
    fun exit(event: MethodExitEvent)

    /**
     * Rejects missing rows, unfinished operations, or outstanding event state.
     */
    fun complete(): Boolean
}
