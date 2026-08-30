package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20

/**
 * Borrows explicit straight-alpha blending and painter-order depth state for one immediate legacy GUI texture submission.
 *
 * Calls belong to the render thread and retain no image, graphics context, or native resource.
 * Legacy shader blend-mode caching does not re-enable blending when the same shader follows an externally restored state.
 * Earlier buffered draws are flushed before disabling depth so native items and decorations remain beneath later texture layers.
 * The callback receives explicit additive source-alpha blending without depth testing or depth writes.
 * The caller's blend enable, factors, equations, and depth-test enable are restored even on failure.
 *
 * @param graphics active graphics context borrowed to flush earlier buffered GUI draws without fencing the complete presentation.
 * @param submit immediate texture submission, invoked once without retention.
 * @throws Throwable when setup or submission fails, preserving that failure while independently attempting every restoration step.
 */
@JvmSynthetic
internal fun withFabricMinecraftGuiBlending(
    graphics: GuiGraphics,
    submit: () -> Unit,
) {
    RenderSystem.assertOnRenderThread()
    val depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
    val enabled = GL11.glIsEnabled(GL11.GL_BLEND)
    val sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
    val destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
    val sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
    val destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
    val equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB)
    val equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)
    FabricMinecraftFailures.runWithCleanup(
        {
            graphics.flush()
            RenderSystem.disableDepthTest()
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD)
            submit()
        },
        {
            FabricMinecraftFailures.runWithCleanup(
                { RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha) },
                {
                    FabricMinecraftFailures.runWithCleanup(
                        { GL20.glBlendEquationSeparate(equationRgb, equationAlpha) },
                        {
                            FabricMinecraftFailures.runWithCleanup(
                                { if (enabled) RenderSystem.enableBlend() else RenderSystem.disableBlend() },
                                { if (depthEnabled) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest() },
                            )
                        },
                    )
                },
            )
        },
    )
}
