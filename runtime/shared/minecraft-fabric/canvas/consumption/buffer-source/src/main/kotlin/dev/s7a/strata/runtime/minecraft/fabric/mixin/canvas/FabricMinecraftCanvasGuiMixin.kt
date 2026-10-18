@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import dev.s7a.strata.runtime.minecraft.fabric.FabricCanvasGuiCleanup
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasGuiDiscard
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasHooks
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.gui.render.TextureSetup
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow

/**
 * Records Canvas target consumption after the queued GUI renderer has issued its GPU work.
 *
 * This render-thread boundary runs after extraction and producer capture, even when GUI rendering throws.
 * The shared hook fences successful consumption or quarantines failed queues without waiting.
 * The borrowed projection buffer and operation are never retained, and cleanup preserves any original rendering failure.
 */
@Mixin(GuiRenderer::class)
private abstract class FabricMinecraftCanvasGuiMixin : FabricMinecraftCanvasGuiDiscard {
    @field:Shadow
    @field:Final
    private lateinit var draws: MutableList<*>

    @field:Shadow
    @field:Final
    private lateinit var meshesToDraw: MutableList<out AutoCloseable>

    @field:Shadow
    @field:Final
    private lateinit var byteBufferBuilder: ByteBufferBuilder

    @field:Shadow
    private var previousTextureSetup: TextureSetup? = null

    @field:Shadow
    private var bufferBuilder: BufferBuilder? = null

    override fun strataDiscardCanvasGui() {
        FabricCanvasGuiCleanup.run(
            {
                draws.clear()
                previousTextureSetup = null
                bufferBuilder = null
            },
            { FabricCanvasGuiCleanup.closeMeshes(meshesToDraw) },
            { byteBufferBuilder.build()?.close() },
            byteBufferBuilder::discard,
            {
                FabricMinecraftCanvasRenderStateAccess::class.java
                    .cast(this)
                    .strataCanvasRenderState()
                    .reset()
            },
        )
    }

    @WrapMethod(method = ["render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"])
    private fun strataConsumeCanvasGui(
        projection: GpuBufferSlice,
        original: Operation<Void?>,
    ) {
        val result = runCatching { original.call(projection) }
        FabricMinecraftCanvasHooks.afterGui(result.exceptionOrNull())
        result.getOrThrow()
    }
}
