package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.server.packs.resources.ResourceManager
import java.util.function.IntSupplier

/**
 * Gives the texture manager a render-thread view of independently owned Canvas storage.
 * The provider retains only its native owner, which must outlive GUI consumption.
 * Resource loading, unregister and manager shutdown never allocate or destroy storage through this view.
 */
internal class FabricBorrowedTexture(
    private val textureId: IntSupplier,
) : AbstractTexture() {
    override fun getId(): Int = textureId.asInt

    override fun load(resourceManager: ResourceManager) = Unit

    override fun releaseId() = Unit

    override fun close() = Unit
}
