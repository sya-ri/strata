package dev.s7a.strata.performance

import java.lang.ref.WeakReference

/**
 * Test-owned completed presentation boundaries from the loaded Fabric screen-event API.
 * The observer borrows the screen through a weak reference, retaining no screen, host, command list or texture.
 * Reads rebind to replaced framework events after initialization or resize without installing a runtime-specific hook.
 * Construct, read and close it on the screen's client thread; closing stops collection without requiring event unregistration.
 */
public class FabricFrameObserver(
    screen: Any,
) : AutoCloseable {
    private var closed = false
    private val screen = WeakReference(screen)
    private var event: Any? = null
    private var completed = 0L

    /**
     * Actual completed extraction/render callbacks since registration, including ordinary clean presentations.
     */
    public val frames: Long
        get() {
            observe()
            return completed
        }

    init {
        observe()
    }

    private fun observe() {
        if (closed) return
        val owner = screen.get() ?: return
        event =
            FabricPerformanceCallbacks.register(owner, listOf("afterExtract", "afterRender"), event) {
                if (closed.not()) completed = Math.incrementExact(completed)
            }
    }

    override fun close() {
        closed = true
        screen.clear()
        event = null
    }
}
