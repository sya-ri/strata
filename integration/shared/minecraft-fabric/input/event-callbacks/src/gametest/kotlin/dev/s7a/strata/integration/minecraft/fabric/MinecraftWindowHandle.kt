package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft

/**
 * Returns the borrowed native handle on the client thread for physical framebuffer verification.
 */
internal fun minecraftTestWindowHandle(): Long = Minecraft.getInstance().window.handle()
