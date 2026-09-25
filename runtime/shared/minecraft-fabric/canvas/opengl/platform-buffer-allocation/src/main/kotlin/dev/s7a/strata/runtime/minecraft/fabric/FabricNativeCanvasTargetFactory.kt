package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.Minecraft

/**
 * Allocates a legacy RGBA8 framebuffer on the render thread under the device's reserved lifetime permit.
 *
 * Success returns native ownership to the caller; failure transfers partial attachments through a NativeCanvasAllocationFailure carrier without destroying them.
 * The device attempts an initialization fence and retains failed allocations until terminal GPU completion permits cleanup.
 * The allocation-only clear is skipped, while later calls to the borrowed target's clear method retain native behavior.
 * The first actual capture initializes visible pixels, separately from the device's allocation fence.
 */
@Suppress("TooGenericExceptionCaught")
@JvmSynthetic
internal fun createCanvasRenderTarget(
    size: IntSize,
    depth: Boolean,
): RenderTarget {
    val target = FabricCanvasRenderTarget(depth)
    try {
        target.createBuffers(size.width, size.height, Minecraft.ON_OSX)
    } catch (failure: Throwable) {
        FabricNativeCanvasPartialTarget.fail(target, size, failure)
    } finally {
        target.finishAllocation()
    }
    return target
}

private class FabricCanvasRenderTarget(
    depth: Boolean,
) : RenderTarget(depth) {
    private var allocating = true

    fun finishAllocation() {
        allocating = false
    }

    override fun clear(checkError: Boolean) {
        if (allocating.not()) super.clear(checkError)
    }
}
