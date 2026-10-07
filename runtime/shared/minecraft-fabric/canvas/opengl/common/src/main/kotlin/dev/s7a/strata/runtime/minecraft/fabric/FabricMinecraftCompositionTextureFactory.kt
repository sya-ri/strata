package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture
import org.lwjgl.opengl.GL11

/**
 * Requires enough native extent for every admitted output and metadata axis before preparing a GPU tile.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftOrderedComposition(): Boolean {
    RenderSystem.assertOnRenderThread()
    return 4096 <= GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)
}

/**
 * Transfers one empty four-texture owner before allocation and restores all touched OpenGL state after recording.
 * Source identities remain pinned by the caller through initialization and ordered GUI consumption.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftCompositionTexture(
    indices: NativeImage,
    factors: NativeImage,
    size: IntSize,
    sources: List<AbstractTexture?>,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
) {
    RenderSystem.assertOnRenderThread()
    val storage = FabricMinecraftGlCompositionStorage()
    retain(storage.texture, storage)
    storage.initialize(indices, factors, size, sources)
}
