package net.fabricmc.fabric.api.client.screen.v1

/**
 * Test-only event registration with the same generic listener shape as Fabric's event.
 */
internal class NativeScreenEvent<T : ExtractionListener> {
    private val callbacks = mutableListOf<T>()

    /**
     * Registers a callback exactly as the loaded adapter would.
     */
    fun register(listener: T) {
        callbacks.add(listener)
    }

    /**
     * Drives a boundary; the fixture never supplies clocks or performance statistics.
     */
    fun fire() {
        callbacks.forEach { it.extract() }
    }
}
