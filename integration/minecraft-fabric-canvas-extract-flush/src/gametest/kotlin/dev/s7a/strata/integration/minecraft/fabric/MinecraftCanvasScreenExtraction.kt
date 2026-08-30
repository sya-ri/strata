package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen

/**
 * Catches one real immediate screen render after flushing earlier native GUI work before the caller's framebuffer baseline.
 *
 * The client/render thread borrows [screen] and [before] for this call; Minecraft retains ownership of its buffer source.
 * The Canvas screen's own finally block flushes its submission, including its failure path.
 * [before] may enqueue an explicit native baseline copy; the caller compares the framebuffer again after return.
 * Context and baseline failures propagate, while the exact screen failure is returned without escaping into Minecraft's crash handler.
 */
internal fun extractMinecraftCanvasScreen(
    screen: Screen,
    before: () -> Unit,
): Throwable? {
    RenderSystem.assertOnRenderThread()
    val client = Minecraft.getInstance()
    val graphics = GuiGraphics(client, client.renderBuffers().bufferSource())
    graphics.flush()
    before()
    return runCatching { screen.render(graphics, 0, 0, 0f) }.exceptionOrNull()
}
