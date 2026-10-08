package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import java.nio.file.Path
import java.util.UUID

/**
 * Supplemental owner-thread CPU/allocation intervals using the identical service fixture and shared collector.
 * JMH remains the elapsed-time and normalized-GC collector; these intervals explicitly measure owner CPU.
 * Preparation, provenance snapshots, queue assertions and serialization stay outside sample boundaries.
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
        val inputs = JmhFixtureSelection.inputs().mapValues { ArtifactIdentity.file(it.value) }
        val plan = PerformancePlan(warmup = 100, samples = 200)
        val phases = JsonArray()
        listOf(1, 100, 1000).forEach { peers ->
            TransportDrainWorkload.entries.forEach { workload ->
                val batch = maxOf(1, 1000 / (peers * maxOf(1, workload.frames)))
                TransportDrainFleet(peers, workload).use { fleet ->
                    val sample = JvmPerformanceRunner.measure("$peers/$workload", plan, afterOperation = { _, _ -> fleet.verifyDrained() }) {
                        var delivered = 0L
                        repeat(batch) { delivered = fleet.cycle() }
                        delivered
                    }
                    phases.add(sample.evidence.apply {
                        addProperty("peers", peers)
                        addProperty("workload", workload.name)
                        addProperty("cycles_per_sample", batch)
                        addProperty("warmup", plan.warmup)
                        addProperty("processed_input_frames", (plan.warmup + plan.samples).toLong() * batch * peers * workload.frames)
                        addProperty("cumulative_output_frames", sample.value)
                    })
                }
            }
        }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(ArtifactIdentity.applicationTrees(fixtures) == identity)
        check(JmhFixtureSelection.inputs().mapValues { ArtifactIdentity.file(it.value) } == inputs)
        PerformanceJson.writeNew(Path.of(args[0]), JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("workload_id", "bounded-transport-drain-v1")
            addProperty("status", "passed")
            addProperty("run_id", UUID.randomUUID().toString())
            addProperty("repetition", repetition)
            add("runtime_metadata", runtime)
            add("fixture_identity", Gson().toJsonTree(identity))
            add("inputs", Gson().toJsonTree(inputs))
            add("phases", phases)
            addProperty("native_uploads", "N/A: no native presentation")
            addProperty("gpu_time", "N/A: no native presentation")
            addProperty("fps", "N/A: no frame-rate claim")
        })
    }
}
