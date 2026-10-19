@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas

import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import dev.s7a.strata.runtime.minecraft.fabric.FabricCanvasGuiCleanup
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftCanvasGuiDiscard
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.StagedVertexBuffer
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow

/**
 * Discards pending GUI vertex data without closing any native GPU buffer or advancing a GPU buffer pool.
 *
 * The GUI consumer calls this capability only on its own staged buffer, on the render thread, after a failure
 * or before terminal device completion. It retains no drawing context and never submits GPU work.
 */
@Mixin(StagedVertexBuffer::class)
private abstract class FabricMinecraftCanvasVertexBufferMixin : FabricMinecraftCanvasGuiDiscard {
    @field:Shadow
    @field:Final
    private lateinit var stagingBuffer: ByteBufferBuilder

    @field:Shadow
    private var lastVertexBuilder: BufferBuilder? = null

    @field:Shadow
    private var lastBuildingDraw: StagedVertexBuffer.Draw? = null

    /**
     * Clears staged draw references while Minecraft retains ownership of its GPU buffer pools.
     */
    @Shadow
    abstract fun endDraw()

    override fun strataDiscardCanvasGui() {
        lastVertexBuilder = null
        lastBuildingDraw = null
        FabricCanvasGuiCleanup.run(
            this::endDraw,
            { stagingBuffer.build()?.close() },
            stagingBuffer::discard,
        )
    }
}
