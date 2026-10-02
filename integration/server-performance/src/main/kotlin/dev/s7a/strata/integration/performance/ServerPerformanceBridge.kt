package dev.s7a.strata.integration.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.JvmPerformanceSchedule
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.UUID
import java.util.function.IntConsumer
import java.util.function.IntFunction

/**
 * Test-only child-loaded bridge with a JDK-only reflection boundary to isolated plugin classloaders.
 * Each host callback advances the shared collector once; this bridge owns no clock or sampling loop.
 * The collector and fixture class trees are verified before measurement, while shaded runtime ownership is explicit.
 */
public class ServerPerformanceBridge(
    name: String,
    operation: IntFunction<Int>,
    verify: IntConsumer,
    representatives: Map<String, String>,
) : AutoCloseable {
    private val plan = PerformancePlan()
    private val loader = ServerPerformanceBridge::class.java.classLoader
    private val runtime =
        LoadedArtifactMetadata
            .capture(
                loader,
                representatives + mapOf("collector" to "dev.s7a.strata.performance.JvmPerformanceMeter", "fixture" to ServerPerformanceBridge::class.java.name),
                setOf("collector", "fixture"),
            ).also(LoadedArtifactMetadata::verifyComplete)
    private val schedule =
        JvmPerformanceSchedule(name, plan, afterOperation = { index, _ -> verify.accept(index) }) { index -> operation.apply(index) }
    private val intervalName = name

    /**
     * Runs one kit-owned warm-up or sample at the current host scheduling opportunity.
     */
    @Suppress("unused") // Called reflectively across the isolated plugin/collector classloader boundary.
    public fun advance(): Boolean = schedule.advance()

    /**
     * Publishes complete invocation-bound evidence without replacing a previous receipt.
     * Runtime representative identities do not claim complete shaded-module API discovery or native acknowledgement.
     */
    @Suppress("unused") // Called reflectively across the isolated plugin/collector classloader boundary.
    public fun write(
        destination: String,
        runId: String,
        host: String,
    ) {
        require(UUID.fromString(runId).toString().contentEquals(runId)) { "Use a canonical invocation UUID" }
        val phase = schedule.complete().evidence
        val report =
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("workload_id", "strata-$host-$intervalName-v1")
                addProperty("status", "passed")
                addProperty("run_id", runId)
                addProperty("host", host)
                addProperty("warmup", plan.warmup)
                addProperty("required_repetitions", plan.repetitions)
                addProperty("scope", "Synchronous server-owner operation; scheduling gaps and client application are outside samples. Runtime modules use representative identities; only collector and fixture use complete class trees.")
                add(
                    "environment",
                    JsonObject().apply {
                        listOf("java.version", "java.vm.name", "java.vm.version", "os.name", "os.version", "os.arch").forEach { addProperty(it, System.getProperty(it)) }
                        addProperty("available_processors", Runtime.getRuntime().availableProcessors())
                        addProperty("max_heap_bytes", Runtime.getRuntime().maxMemory())
                        addProperty("processor_identifier", System.getenv("PROCESSOR_IDENTIFIER"))
                        addProperty("host_name", System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"))
                        add(
                            "jvm_arguments",
                            JsonArray().apply {
                                ManagementFactory
                                    .getRuntimeMXBean()
                                    .inputArguments
                                    .filter { it.startsWith("-Dstrata.paper.run=").not() }
                                    .forEach(::add)
                            },
                        )
                    },
                )
                add(
                    "runtime_identity",
                    JsonObject().apply {
                        runtime.getAsJsonArray("modules").forEach { entry ->
                            val module = entry.asJsonObject
                            addProperty(module.get("representativeClass").asString, module.getAsJsonObject("codeSource").get("sha256").asString)
                        }
                    },
                )
                add("loaded_runtime", runtime)
                add("phases", JsonArray().apply { add(phase) })
            }
        PerformanceJson.writeNew(Path.of(destination), report)
    }

    override fun close(): Unit = schedule.close()
}
