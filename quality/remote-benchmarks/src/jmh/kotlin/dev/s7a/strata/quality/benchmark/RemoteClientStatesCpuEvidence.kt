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
 * Measures owner CPU and allocation for the same 45 registered-state operations used by JMH.
 * The shared collector owns clocks and sampling; fixtures, provenance and serialization stay outside sample boundaries.
 * Each invocation uses one fresh process and is paired with a JMH receipt preserving the same runtime archives.
 */
public object RemoteClientStatesCpuEvidence {
    /**
     * Accepts a fresh report path and independent repetition 0 through 2 on the frozen fixture classpath.
     * Per-operation values divide shared collector totals by the recorded operations per sample.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 2)
        val repetition = args[1].toInt()
        require(repetition in 0..2)
        RemoteClientStatesBenchmark.verifyWork()
        val targets = mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree")
        val runtime = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val fixtures = listOf(RemoteClientStatesBenchmark::class.java, RemoteClientStatesCpuEvidence::class.java)
        val identity = ArtifactIdentity.applicationTrees(fixtures)
        val inputs = JmhFixtureSelection.inputs().mapValues { ArtifactIdentity.file(it.value) }
        val plan = PerformancePlan(warmup = 100, samples = 200)
        val phases = JsonArray()
        for (entries in listOf(100, 1_000, 8_192)) {
            for (membership in RemoteClientStatesBenchmark.Membership.entries) {
                for (operation in Operation.entries) {
                    phases.add(measure(entries, membership, operation, plan))
                }
            }
        }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(ArtifactIdentity.applicationTrees(fixtures) == identity)
        check(JmhFixtureSelection.inputs().mapValues { ArtifactIdentity.file(it.value) } == inputs)
        PerformanceJson.writeNew(Path.of(args[0]), JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("workload_id", "current-client-editable-states-v1")
            addProperty("status", "passed")
            addProperty("run_id", UUID.randomUUID().toString())
            addProperty("repetition", repetition)
            add("runtime_metadata", runtime)
            add("fixture_identity", Gson().toJsonTree(identity))
            add("inputs", Gson().toJsonTree(inputs))
            add("phases", phases)
            addProperty("native_uploads", "N/A: native-free client state operations")
            addProperty("gpu_time", "N/A: native-free client state operations")
            addProperty("fps", "N/A: no frame-rate claim")
        })
    }

    private fun measure(
        entries: Int,
        membership: RemoteClientStatesBenchmark.Membership,
        operation: Operation,
        plan: PerformancePlan,
    ): JsonObject {
        val batch = maxOf(1, 10_000 / entries)
        return RemoteClientStatesBenchmark.Scene().use { scene ->
            scene.entries = entries
            scene.membership = membership
            scene.setUp()
            val sample = JvmPerformanceRunner.measure("$entries/$membership/$operation", plan) {
                var result = 0L
                repeat(batch) { result = operation.run(scene) }
                result
            }
            sample.evidence.apply {
                addProperty("entries", entries)
                addProperty("membership", membership.name)
                addProperty("operation", operation.name)
                addProperty("operations_per_sample", batch)
                addProperty("warmup", plan.warmup)
                addProperty("processed_operations", (plan.warmup + plan.samples).toLong() * batch)
                addProperty("last_result", sample.value)
            }
        }
    }

    /**
     * Executable operations corresponding exactly to the compiled JMH methods.
     */
    private enum class Operation(val run: (RemoteClientStatesBenchmark.Scene) -> Long) {
        Idle(RemoteClientStatesBenchmark.Scene::idle),
        Edits(RemoteClientStatesBenchmark.Scene::edit),
        PreAction(RemoteClientStatesBenchmark.Scene::action),
        Incoming({ it.update(false) }),
        Churn({ it.update(true) }),
    }
}
