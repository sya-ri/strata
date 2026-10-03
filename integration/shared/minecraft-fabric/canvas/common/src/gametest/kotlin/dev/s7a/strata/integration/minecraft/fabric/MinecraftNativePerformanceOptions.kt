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
    private val previousVsync = client.options.enableVsync().get()
    private val previousLimit = client.options.framerateLimit().get()

    init {
        check(client.isSameThread)
        client.options.enableVsync().set(false)
        client.options.framerateLimit().set(120)
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
            addProperty("scope", "Settled canonical component/native Canvas presentation; frame CPU includes instrumentation and pacing, excluding GPU completion")
        }
    }

    /**
     * Restores options on their client owner even when collection or native cleanup fails.
     */
    override fun close() {
        check(client.isSameThread)
        client.options.enableVsync().set(previousVsync)
        client.options.framerateLimit().set(previousLimit)
    }
}
