@file:OptIn(InternalStrataRuntimeApi::class)
@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.pipeline.RenderTarget
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Validates the extent before allocating and publishes a target only after native initialization succeeds.
 *
 * The render-thread caller owns a reserved device permit; both callbacks run synchronously and are never retained.
 * Initialization failure transfers partial ownership through [FabricNativeCanvasPartialTarget.fail] without releasing attachments before a device fence exists.
 */
@JvmSynthetic
@Suppress("TooGenericExceptionCaught")
internal inline fun createFabricNativeCanvasTarget(
    physicalSize: IntSize,
    create: () -> RenderTarget,
    initialize: (RenderTarget) -> Unit,
): NativeCanvasTarget {
    requireFabricMinecraftCanvasExtent(physicalSize)
    val target = create()
    try {
        initialize(target)
    } catch (failure: Throwable) {
        FabricNativeCanvasPartialTarget.fail(target, physicalSize, failure)
    }
    return FabricNativeCanvasTarget.create(target)
}
