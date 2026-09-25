package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Creates a non-owning texture-manager view of a Canvas target on the render thread.
 *
 * The target's device lifetime permit remains its sole physical owner; texture reload and unregister never delete attachments.
 * The returned wrapper retains only the target and throws no cleanup errors because it owns no native storage.
 */
@JvmSynthetic
internal fun createFabricNativeCanvasTexture(target: RenderTarget): AbstractTexture = FabricCanvasBorrowedTexture(target)
