package dev.s7a.strata.performance

/**
 * Test-owned completed presentation boundaries from the loaded Fabric screen-event API.
 * The observer retains no screen, host, command list or texture and installs no runtime-specific hook.
 * Construct, read and close it on the screen's client thread; closing stops collection without requiring event unregistration.
 */
public class FabricFrameObserver(
    screen: Any,
) : AutoCloseable {
    private var closed = false

    /**
     * Actual completed extraction/render callbacks since registration, including ordinary clean presentations.
     */
    public var frames: Long = 0L
        private set

    init {
        FabricPerformanceCallbacks.register(screen, listOf("afterExtract", "afterRender")) {
            if (closed.not()) frames = Math.incrementExact(frames)
        }
    }

    override fun close() {
        closed = true
    }
}
