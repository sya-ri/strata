package dev.s7a.strata.gradle.fabric

import com.sun.management.OperatingSystemMXBean
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import java.lang.management.ManagementFactory

/**
 * Recheck host resources before each correctness client launch and while admission waits.
 * Completion events release failed as well as successful tasks; close wakes cancelled waiters.
 * This build controller supplies no runtime benchmark measurements and must not guard formal performance collection.
 */
public abstract class FabricClientResourceService :
    BuildService<FabricClientResourceService.Parameters>,
    OperationCompletionListener,
    AutoCloseable {
    /**
     * Fixed worker/processor ceiling and controlled client heap, supplied by the owning build.
     */
    public interface Parameters : BuildServiceParameters {
        /**
         * Maximum clients, leaving a Gradle worker available for prerequisite work.
         */
        public val maximum: Property<Int>

        /**
         * Positive JVM heap size with a k, m or g suffix.
         */
        public val clientHeap: Property<String>
    }

    private val gate by lazy { FabricClientCapacityGate(::capacity) }
    private val logger = Logging.getLogger(FabricClientResourceService::class.java)

    /**
     * Acquire before JavaExec starts; already running clients finish normally when available capacity decreases.
     */
    public fun acquire(taskPath: String) {
        val limit = gate.acquire(taskPath)
        logger.lifecycle("Automatic Minecraft client admission: $taskPath (current capacity $limit)")
    }

    override fun onFinish(event: FinishEvent) {
        if (event is TaskFinishEvent) gate.release(event.descriptor.taskPath)
    }

    override fun close() {
        gate.close()
    }

    /**
     * Reserve desktop RAM, unallocated Gradle heap, and native memory in addition to each client heap.
     */
    private fun capacity(): Int {
        val system = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
        val runtime = Runtime.getRuntime()
        val unusedGradleHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val gibibyte = 1024L * 1024 * 1024
        val heap = parameters.clientHeap.get()
        require(heap.matches(Regex("[1-9][0-9]*[kKmMgG]"))) { "Invalid Minecraft client heap: $heap" }
        val unit =
            when (heap.last().lowercaseChar()) {
                'k' -> 1024L
                'm' -> 1024L * 1024
                'g' -> gibibyte
                else -> error("Unsupported Minecraft client heap unit")
            }
        val heapBytes = Math.multiplyExact(heap.dropLast(1).toLong(), unit)
        return fabricClientCapacity(
            system?.freeMemorySize ?: -1,
            Math.addExact(2 * gibibyte, unusedGradleHeap.coerceAtLeast(0)),
            Math.addExact(heapBytes, gibibyte),
            runtime.availableProcessors(),
            system?.cpuLoad ?: -1.0,
            parameters.maximum.get(),
        )
    }
}
