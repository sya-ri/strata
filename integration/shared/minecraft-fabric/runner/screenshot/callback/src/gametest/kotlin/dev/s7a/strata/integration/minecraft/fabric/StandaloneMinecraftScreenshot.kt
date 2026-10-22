package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot

/**
 * Hands one native image to its owner after the selected renderer has produced the captured frame.
 */
internal fun readStandaloneMinecraftFramebuffer(
    minecraft: Minecraft,
    receive: (NativeImage) -> Unit,
) {
    minecraft.gameRenderer.render(DeltaTracker.ONE, true)
    Screenshot.takeScreenshot(minecraft.mainRenderTarget, receive)
}
