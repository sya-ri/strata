package net.fabricmc.fabric.api.client.screen.v1

/**
 * Test-only callback contract for exercising the reflective adapter without a game dependency.
 */
internal fun interface ExtractionListener {
    /**
     * Presents one simulated extraction boundary.
     */
    fun extract()
}
