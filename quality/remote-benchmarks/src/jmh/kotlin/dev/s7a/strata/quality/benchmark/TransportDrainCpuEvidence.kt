package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.UUID

/**
 * Supplemental owner-thread CPU/allocation intervals using the identical service fixture and shared collector.
 * JMH remains the elapsed-time and normalized-GC collector; these intervals explicitly measure owner CPU.
 * Fixture setup, provenance snapshots, queue assertions and serialization stay outside sample boundaries.
 */
public object TransportDrainCpuEvidence {
    /**
     * Accepts one fresh report path and independent repetition index on the frozen fixture/collector classpath.
     * Runtime archives are selected by that classpath, with external files preserved by the paired JMH receipt.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 2)
        val repetition = args[1].toInt()
        require(repetition in 0..2)
        TransportDrainBenchmark.verifyWork()
        val targets = mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree")
        val runtime = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val fixtures = listOf(TransportDrainBenchmark::class.java, TransportDrainFleet::class.java, TransportDrainWorkload::class.java)
        val identity = ArtifactIdentity.applicationTrees(fixtures)
        val inputs = inputs().mapValues { ArtifactIdentity.file(it.value) }
        val plan = PerformancePlan(warmup = 100, samples = 200)
        val phases = JsonArray()
        listOf(1, 100, 1000).forEach { peers ->
            TransportDrainWorkload.entries.forEach { workload ->
                phases.add(collect(peers, workload, plan))
            }
        }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(ArtifactIdentity.applicationTrees(fixtures) == identity)
        check(inputs().mapValues { ArtifactIdentity.file(it.value) } == inputs)
        PerformanceJson.writeNew(
            Path.of(args[0]),
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("workload_id", "bounded-transport-drain-v1")
                addProperty("status", "passed")
                addProperty("run_id", UUID.randomUUID().toString())
                addProperty("repetition", repetition)
                add("runtime_metadata", runtime)
                add("fixture_identity", Gson().toJsonTree(identity))
                add("inputs", Gson().toJsonTree(inputs))
                add("environment", environment())
                add("phases", phases)
                addProperty("native_uploads", "N/A: no native presentation")
                addProperty("gpu_time", "N/A: no native presentation")
                addProperty("fps", "N/A: no frame-rate claim")
            },
        )
    }

    /**
     * Uses the shared owner meter for one fixed matrix row; provenance and work assertions stay outside timing.
     */
    private fun collect(peers: Int, workload: TransportDrainWorkload, plan: PerformancePlan): JsonObject {
        val batch = maxOf(1, 1000 / (peers * maxOf(1, workload.frames)))
        return TransportDrainFleet(peers, workload).use { fleet ->
            val sample = JvmPerformanceRunner.measure("$peers/$workload", plan, afterOperation = { _, _ -> fleet.verifyDrained() }) {
                var delivered = 0L
                repeat(batch) { delivered = fleet.cycle() }
                delivered
            }
            sample.evidence.apply {
                addProperty("peers", peers)
                addProperty("workload", workload.name)
                addProperty("cycles_per_sample", batch)
                addProperty("measured_cycles", plan.samples.toLong() * batch)
                addProperty("warmup", plan.warmup)
                addProperty("processed_input_frames", (plan.warmup + plan.samples).toLong() * batch * peers * workload.frames)
                addProperty("cumulative_output_frames", sample.value)
            }
        }
    }

    /**
     * Preserves the same immutable fixture and resolved library inventory as the paired JMH invocation.
     */
    private fun inputs(): Map<String, Path> {
        val libraries = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs"))))
        val fixtures = JmhFixtureSelection.inputs()
        require(libraries.keys.intersect(fixtures.keys).isEmpty()) { "Duplicate external fixture input labels" }
        return libraries + fixtures
    }

    /**
     * Captures host/JVM conditions outside the interval; the runtime archives remain separate provenance.
     */
    private fun environment(): JsonObject =
        Gson()
            .toJsonTree(
                listOf("java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch").associateWith(System::getProperty) +
                    mapOf("available_processors" to Runtime.getRuntime().availableProcessors().toString(), "host" to System.getenv("COMPUTERNAME"), "jvm_arguments" to ManagementFactory.getRuntimeMXBean().inputArguments),
            ).asJsonObject
}
