package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.performance.GpuPerformanceMeter
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Records loaded RenderPearl timestamps immediately around the existing real GUI-consumer test hook.
 * Device timestampPeriod supplies nanoseconds per backend tick on OpenGL and Vulkan.
 * The host owns ordinary submission; this diagnostic owner submits only during terminal query cleanup.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftGpuPerformanceProbe(
    samples: Int,
) : MinecraftNativeGpuProbe {
    private val device = RenderSystem.getDevice()
    private val period =
        device.deviceInfo
            .timestampPeriod()
            .toDouble()
            .also { require(it.isFinite() && 0.0 < it) }
    private val queries = device.createTimestampQueryPool(Math.multiplyExact(samples, 2))
    private val meter = GpuPerformanceMeter(samples, period, "Host GUI consumption commands; excludes CPU preparation and uploads recorded before consumption.") { index -> queries.getValue(index).let { if (it.isPresent) it.asLong else null } }
    private var recorded = 0

    override fun arm() {
        RenderSystem.assertOnRenderThread()
        meter.begin()
        val index = recorded
        MinecraftCanvasConsumerTestHooks.arm(
            { device.createCommandEncoder().writeTimestamp(queries, index * 2) },
            {
                device.createCommandEncoder().writeTimestamp(queries, index * 2 + 1)
                meter.recorded()
                recorded += 1
            },
        )
    }

    override val completed: Boolean
        get() {
            RenderSystem.assertOnRenderThread()
            return meter.completed
        }

    override fun append(report: JsonObject) {
        RenderSystem.assertOnRenderThread()
        report.add("native_gpu", meter.result())
    }

    override fun close() {
        RenderSystem.assertOnRenderThread()
        MinecraftCanvasConsumerTestHooks.reset()
        meter.close()
        // Partial or interrupted query recording still belongs to this diagnostic owner until submitted work completes.
        val encoder = device.createCommandEncoder()
        encoder.createFence().use { fence ->
            encoder.submit()
            check(fence.awaitCompletion(Long.MAX_VALUE)) { "GPU performance query cleanup did not complete." }
        }
        queries.close()
    }
}
