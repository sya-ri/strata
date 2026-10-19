@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import dev.s7a.strata.runtime.minecraft.fabric.FabricCanvasGuiCleanup
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasGuiDiscard
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.StagedVertexBuffer
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow

/**
 * Runs the Canvas target-consumption boundary after the current GUI renderer has attempted to consume and encode its queue on either GPU backend.
 *
 * This render-thread wrapper always reaches the shared lifetime hook after the actual consumer, including failures.
 * It never runs a producer or resolves a retained frame token, and it never retains the borrowed operation.
 * The hook preserves the original failure and does not block for unfinished GPU work.
 */
@Mixin(GuiRenderer::class)
private abstract class FabricMinecraftCanvasGuiMixin : FabricMinecraftCanvasGuiDiscard {
    @field:Shadow
    @field:Final
    private lateinit var draws: MutableList<*>

    @field:Shadow
    @field:Final
    private lateinit var vertexBuffer: StagedVertexBuffer

    @field:Shadow
    private var previousTextureSetup: TextureSetup? = null

    @field:Shadow
    private var previousDraw: StagedVertexBuffer.Draw? = null

    override fun strataDiscardCanvasGui() {
        FabricCanvasGuiCleanup.run(
            {
                draws.clear()
                previousTextureSetup = null
                previousDraw = null
            },
            { FabricMinecraftCanvasGuiDiscard::class.java.cast(vertexBuffer).strataDiscardCanvasGui() },
            {
                FabricMinecraftCanvasRenderStateAccess::class.java
                    .cast(this)
                    .strataCanvasRenderState()
                    .reset()
            },
        )
    }

    @WrapMethod(method = ["render()V"])
    private fun strataConsumeCanvasGui(original: Operation<Void?>) {
        val result = runCatching { original.call() }
        FabricMinecraftCanvasHooks.afterGui(result.exceptionOrNull())
        result.getOrThrow()
    }
}
