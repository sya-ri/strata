package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import java.lang.management.ManagementFactory

/**
 * Client-thread fixture options and actual host conditions, outside shared sampling intervals.
 * Restores the borrowed frame-pacing options; the existing Canvas coordinator owns viewport restoration.
 */
internal class MinecraftNativePerformanceOptions : AutoCloseable {
    private val client = Minecraft.getInstance()
    private val lease = MinecraftNativePerformanceOptionLease { check(client.isSameThread) }
    private val inactivity = MinecraftNativePerformancePacing.Inactivity.fromProperty(System.getProperty("strata.performance.inactivity"))
    private var lastPacing: MinecraftNativePerformancePacing? = null
    private var expectedPacing: MinecraftNativePerformancePacing? = null
    private var observingPacing = false
    private var boundaryLimit = 0
    private val pacingBoundaries = ArrayList<MinecraftNativePerformancePacing>()

    init {
        val vsync = client.options.enableVsync()
        val limit = client.options.framerateLimit()
        lease.capture(vsync::get, vsync::set, false)
        lease.capture(limit::get, limit::set, 120)
        client.captureNativeInactivity(lease, inactivity)
    }

    /**
     * Applies the captured settings only after the caller owns this lease in its protected lifetime.
     */
    internal fun apply() {
        lease.apply()
    }

    /**
     * Resets bounded invocation-owned pacing evidence before attaching the next measured screen.
     */
    internal fun beginPhase(samples: Int) {
        check(client.isSameThread)
        require(0 < samples)
        expectedPacing = null
        lastPacing = null
        observingPacing = false
        boundaryLimit = Math.addExact(samples, 1)
        pacingBoundaries.clear()
        pacingBoundaries.ensureCapacity(boundaryLimit)
    }

    /**
     * Waits for the actual selector and applied limiter to agree before the existing warmup starts.
     */
    internal fun pacingReady(): Boolean {
        check(client.isSameThread)
        val pacing = pacingObservation
        pacing.validate(inactivity)
        if (pacing.ready().not()) return false
        expectedPacing = pacing
        return true
    }

    /**
     * Arms observations after settling; the next validation records the actual initial sample boundary.
     */
    internal fun beforeSamples() {
        check(client.isSameThread)
        val pacing = pacingObservation
        pacing.validate(inactivity)
        if (inactivity == MinecraftNativePerformancePacing.Inactivity.MINIMIZED) pacing.verifyStable(checkNotNull(expectedPacing))
        observingPacing = true
    }

    /**
     * Stops observations at the meter's terminal boundary; its detached result callback is never mutated.
     */
    internal fun afterSamples() {
        check(client.isSameThread)
        check(pacingBoundaries.size == boundaryLimit)
        observingPacing = false
    }

    /**
     * Returns detached live observations for the initial and every completed sample boundary.
     */
    internal fun pacingEvidence(): JsonObject {
        check(client.isSameThread)
        check(pacingBoundaries.size == boundaryLimit)
        observingPacing = false
        return JsonObject().apply {
            addProperty("scope", "Initial pre-sample boundary and every complete presented sample boundary; reads outside extraction")
            addProperty("sample_boundaries", pacingBoundaries.size)
            add("boundaries", JsonArray().apply { pacingBoundaries.forEach { add(boundaryJson(it)) } })
        }
    }

    /**
     * Retains the actual failing observation and bounded completed boundaries without certifying an unfinished interval.
     */
    internal fun pacingProgress(): JsonObject {
        check(client.isSameThread)
        return JsonObject().apply {
            addProperty("requested_boundaries", boundaryLimit)
            addProperty("observed_boundaries", pacingBoundaries.size)
            lastPacing?.let { add("last_observation", boundaryJson(it)) }
            add("boundaries", JsonArray().apply { pacingBoundaries.forEach { add(boundaryJson(it)) } })
        }
    }

    private val pacingObservation: MinecraftNativePerformancePacing
        get() = client.nativePerformancePacing().also { lastPacing = it }

    private fun boundaryJson(pacing: MinecraftNativePerformancePacing): JsonObject =
        JsonObject().apply {
            addProperty("inactivity", pacing.inactivity().name)
            addProperty("reason", pacing.reason().name)
            addProperty("reason_available", pacing.reason() != MinecraftNativePerformancePacing.Reason.UNAVAILABLE)
            addProperty("selected_limit", pacing.selectedLimit())
            addProperty("applied_limit", pacing.appliedLimit())
            addProperty("applied_limit_source", pacing.appliedLimitSource().name)
            addProperty("iconified", pacing.iconified())
        }

    /**
     * Verifies actual loaded framebuffer, GUI scale and pacing options on every collected frame.
     */
    internal fun validate(scale: Int) {
        check(client.isSameThread)
        check(client.window.width == 1920 && client.window.height == 1080)
        check(client.options.guiScale().get() == scale)
        val actualScale: Number = client.window.guiScale
        check(actualScale.toDouble() == scale.toDouble())
        check(
            client.options
                .enableVsync()
                .get()
                .not() && client.options.framerateLimit().get() == 120,
        )
        val pacing = pacingObservation
        pacing.validate(inactivity)
        if (inactivity == MinecraftNativePerformancePacing.Inactivity.MINIMIZED) expectedPacing?.let(pacing::verifyStable)
        if (observingPacing) {
            check(pacingBoundaries.size < boundaryLimit)
            pacingBoundaries.add(pacing)
        }
    }

    /**
     * Captures immutable fixture conditions from the actual process, after configuring the measured viewport.
     */
    internal fun conditions(): JsonObject {
        validate(1)
        return JsonObject().apply {
            addProperty("minecraft_version", checkNotNull(System.getProperty("strata.minecraftVersion")))
            addProperty("java", System.getProperty("java.runtime.version"))
            addProperty("vm", System.getProperty("java.vm.name"))
            addProperty("os", System.getProperty("os.name"))
            addProperty("os_version", System.getProperty("os.version"))
            addProperty("architecture", System.getProperty("os.arch"))
            addProperty("cpu_model", System.getenv("PROCESSOR_IDENTIFIER"))
            addProperty("available_processors", Runtime.getRuntime().availableProcessors())
            addProperty("max_heap_bytes", Runtime.getRuntime().maxMemory())
            add("jvm_arguments", JsonArray().apply { ManagementFactory.getRuntimeMXBean().inputArguments.forEach(::add) })
            addProperty("framebuffer_width", client.window.width)
            addProperty("framebuffer_height", client.window.height)
            addProperty("vsync", client.options.enableVsync().get())
            addProperty("framerate_limit", client.options.framerateLimit().get())
            addProperty("inactivity_mode", inactivity.name)
            addProperty("scope", "Settled canonical component/native Canvas presentation; frame CPU includes instrumentation and pacing, excluding GPU completion")
        }
    }

    /**
     * Restores options on their client owner even when collection or native cleanup fails.
     */
    override fun close() {
        check(client.isSameThread)
        try {
            lease.close()
        } finally {
            expectedPacing = null
            lastPacing = null
            observingPacing = false
            pacingBoundaries.clear()
        }
    }
}
