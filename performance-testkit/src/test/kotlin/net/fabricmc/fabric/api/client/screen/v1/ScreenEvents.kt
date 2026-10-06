package net.fabricmc.fabric.api.client.screen.v1

/**
 * Test-only host surface proves orchestration and failure behavior, not loaded Minecraft performance.
 */
internal object ScreenEvents {
    private val before = mutableMapOf<Any, NativeScreenEvent<ExtractionListener>>()
    private val after = mutableMapOf<Any, NativeScreenEvent<ExtractionListener>>()

    /**
     * Returns the simulated pre-extraction event.
     */
    @JvmStatic
    fun beforeExtract(screen: Any): NativeScreenEvent<ExtractionListener> = before.getOrPut(screen) { NativeScreenEvent() }

    /**
     * Returns the simulated post-extraction event.
     */
    @JvmStatic
    fun afterExtract(screen: Any): NativeScreenEvent<ExtractionListener> = after.getOrPut(screen) { NativeScreenEvent() }

    /**
     * Releases this test fixture's event owner.
     */
    fun release(screen: Any) {
        before.remove(screen)
        after.remove(screen)
    }
}
