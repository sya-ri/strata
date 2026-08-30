package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasCapture
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasProducer
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Borrows an external provider and transfers acquired source leases to the native device.
 *
 * All calls are render-thread confined; the provider is never closed or retained by a detached draw command.
 * A lease is acquired before source sampling and remains owned by its capture even if validation or sampling throws.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
internal class FabricNativeCanvasTextureProducer(
    private val provider: MinecraftCanvasTextureProvider,
) : NativeCanvasProducer {
    @JvmSynthetic
    override fun capture(): NativeCanvasCapture? {
        val lease = provider.acquire() ?: return null
        return Capture(lease)
    }

    @JvmSynthetic
    override fun close() = Unit

    private class Capture(
        private val lease: MinecraftCanvasTextureLease,
    ) : NativeCanvasCapture {
        private val retained: MutableList<AutoCloseable> = ArrayList()

        override fun render(
            target: NativeCanvasTarget,
            logicalSize: IntSize,
            frameTime: FrameTime,
        ): DrawImage? {
            val snapshot = normalizeCanvasSnapshot(lease.snapshot, lease.size, target.size, lease.origin)
            FabricNativeCanvasDriver.copy(lease, target as FabricNativeCanvasTarget, retained::add)
            return snapshot
        }

        override fun close() {
            var failure: Throwable? = null
            for (resource in retained.asReversed() + lease) {
                try {
                    resource.close()
                } catch (caught: Throwable) {
                    val primary = failure
                    if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
                }
            }
            retained.clear()
            failure?.let { throw it }
        }
    }
}
