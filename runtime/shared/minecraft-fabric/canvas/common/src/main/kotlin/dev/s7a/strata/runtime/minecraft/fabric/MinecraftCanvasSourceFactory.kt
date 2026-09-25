@file:JvmName("MinecraftCanvasSources")

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.runtime.minecraft.canvas.nativeCanvasSource
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Describes an externally owned GPU image provider without acquiring a texture or allocating native storage.
 *
 * Each Canvas attachment receives its own binding, and shared providers remain externally owned.
 * Construction belongs to the Minecraft render thread and allocates no target or lease.
 * Actual capture runs on the render thread after layout; leases end only after GPU capture completes.
 * Unsupported formats and missing usage capabilities fail explicitly at capture without CPU readback.
 * Native source and physical destination axes are limited to 32,768 pixels for exact integer nearest sampling; the active device may impose a lower limit.
 * Preparation rejects a larger physical target before allocation and a larger source before sampling; this native limit does not change CPU Canvas geometry.
 *
 * @param provider externally owned RGBA8 straight-alpha image provider.
 * @return a source safe to construct before a Minecraft presentation begins.
 * @throws IllegalStateException when called off the render thread or after device shutdown.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun canvasSource(provider: MinecraftCanvasTextureProvider): CanvasSource {
    RenderSystem.assertOnRenderThread()
    return nativeCanvasSource(FabricNativeCanvasDriver, { FabricNativeCanvasTextureProducer(provider) })
}

/**
 * Describes a custom offscreen renderer without invoking its factory or allocating native storage.
 *
 * Source construction belongs to the render thread and invokes no renderer factory.
 * The factory creates one renderer during the attachment's first native capture and a replacement during the first capture after resource reload.
 * Target reservation precedes factory invocation, so initialization uploads remain protected by that capture's completion fence.
 * Each renderer instance is closed only after its last GPU use, independently of the externally owned factory.
 * The external factory remains owned by the caller; a renderer may borrow only the target and context supplied to its callback.
 * Factory and rendering failures propagate through native preparation.
 * A factory that throws before returning an instance must release or defer its own untransferred partial resources safely.
 * No failure publishes a partial generation.
 * Physical target axes are limited to 32,768 pixels, or the active device's lower limit, and preparation validates the adapter limit before allocation or factory invocation.
 * The limit belongs to native storage and does not restrict common CPU Canvas logical geometry.
 *
 * @param depth whether each owned target includes a depth attachment.
 * @param factory creates an independently owned renderer for each attached Canvas.
 * @return an immutable native source description.
 * @throws IllegalStateException when called off the render thread or after device shutdown.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun canvasSource(
    depth: Boolean = false,
    factory: () -> MinecraftCanvasRenderer,
): CanvasSource {
    RenderSystem.assertOnRenderThread()
    return nativeCanvasSource(FabricNativeCanvasDriver, { FabricNativeCanvasRendererProducer(factory) }, depth)
}
