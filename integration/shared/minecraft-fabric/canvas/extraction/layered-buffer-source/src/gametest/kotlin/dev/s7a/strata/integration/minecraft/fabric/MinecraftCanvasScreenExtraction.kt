package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.render.state.GuiRenderState
import net.minecraft.client.gui.screens.Screen

/**
 * Runs a real buffered screen callback against an independently owned GUI state with layered element traversal.
 *
 * Calls belong to the client/render thread; [screen] and [before] are borrowed only for this invocation.
 * No local GUI state is submitted to Minecraft's consumer, and all four native command collections must remain empty.
 * The exact screen failure is returned with any queue-validation failure suppressed; setup and baseline failures propagate.
 */
internal fun extractMinecraftCanvasScreen(
    screen: Screen,
    before: () -> Unit,
): Throwable? {
    RenderSystem.assertOnRenderThread()
    val state = GuiRenderState()
    val graphics = GuiGraphics(Minecraft.getInstance(), state)
    before()
    return attemptMinecraftCanvasExtraction({ screen.render(graphics, 0, 0, 0f) }) {
        var elements = 0
        state.forEachElement({ _, _ -> elements++ }, GuiRenderState.TraverseRange.ALL)
        state.forEachItem { elements++ }
        state.forEachText { elements++ }
        state.forEachPictureInPicture { elements++ }
        state.reset()
        check(elements == 0) { "Failed Canvas preparation partially queued $elements native GUI commands." }
    }
}
