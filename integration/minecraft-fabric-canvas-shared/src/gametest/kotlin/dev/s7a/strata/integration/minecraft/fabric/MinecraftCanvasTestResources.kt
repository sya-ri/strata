package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasContext
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureLease
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureOrigin

/**
 * Owns a real native 2 by 2 source texture and the independent custom-renderer test operation.
 *
 * All methods run on the client render thread. Native row zero contains red and green; row one contains blue and yellow.
 * Source leases deliberately supply no snapshots, and test resources remain externally owned until every lease ends.
 * The custom operation clears the borrowed target to straight half-alpha green without sampling the source texture.
 */
internal interface MinecraftCanvasTestResources : AutoCloseable {
    /**
     * Immutable family-specific input checks, empty when this fixture does not claim such loaded validation coverage.
     *
     * Entries contain no native resources and run through the explicit client scheduler.
     */
    val inputValidation: List<MinecraftCanvasInputValidation>
        get() = emptyList()

    /**
     * Actual selected native backend, checked independently of the launch preference.
     */
    val backend: MinecraftCanvasTestBackend

    /**
     * Actual native device metadata for the loaded evidence, never an assumed launch backend.
     */
    val backendDescription: String

    /**
     * Borrows the immutable source texture with [origin] until the adapter releases the lease.
     */
    fun lease(origin: MinecraftCanvasTextureOrigin): MinecraftCanvasTextureLease

    /**
     * Draws independent native pixels into the scoped target and returns no CPU substitute.
     */
    fun render(context: MinecraftCanvasContext): DrawImage?

    /**
     * Destroys the external source only after the fixture has observed all source leases close.
     */
    override fun close()
}
