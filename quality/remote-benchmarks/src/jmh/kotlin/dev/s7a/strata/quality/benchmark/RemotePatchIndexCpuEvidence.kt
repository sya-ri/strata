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
import java.nio.file.Path
import java.util.UUID

/**
 * Measures owner CPU and allocation for the same 72 patch-application and client operations used by JMH.
 * The shared collector owns clocks and sampling; fixtures, provenance and serialization stay outside sample boundaries.
 * Each invocation uses one fresh process and validates its exact JMH receipt and immutable source/archive plan before collection.
 */
public object RemotePatchIndexCpuEvidence {
    /**
     * Accepts a fresh report path, repetition 0 through 2, paired JMH receipt path and frozen source/archive plan path.
     * The source plan must already be an immutable external input archived by that JMH receipt.
     * Per-operation values divide shared collector totals by the recorded operations per sample.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 4)
        val repetition = args[1].toInt()
        require(repetition in 0..2)
        RemotePatchIndexBenchmark.verifyWork()
        val targets = mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree")
        val runtime = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val fixtures = listOf(RemotePatchIndexBenchmark::class.java, RemotePatchIndexOperation::class.java, RemotePatchIndexCpuEvidence::class.java)
        val identity = ArtifactIdentity.applicationTrees(fixtures)
        val inputFiles = externalInputs()
        val inputs = inputFiles.mapValues { ArtifactIdentity.file(it.value) }
        val pair = RemoteCpuPair(Path.of(args[2]), Path.of(args[3]), repetition, runtime, identity, inputFiles, RemotePatchIndexBenchmark::class.java)
        val plan = PerformancePlan(warmup = 100, samples = 200)
        val phases = JsonArray()
        for (nodes in listOf(1, 128, 8192)) {
            for (shape in RemotePatchIndexBenchmark.Shape.entries) {
                for (change in RemotePatchIndexBenchmark.Change.entries) {
                    for (operation in RemotePatchIndexOperation.entries) phases.add(measure(nodes, shape, change, operation, plan))
                }
            }
        }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(ArtifactIdentity.applicationTrees(fixtures) == identity)
        check(externalInputs().mapValues { ArtifactIdentity.file(it.value) } == inputs)
        pair.verify()
        PerformanceJson.writeNew(
            Path.of(args[0]),
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("workload_id", "remote-patch-index-v1")
                addProperty("status", "passed")
                addProperty("run_id", UUID.randomUUID().toString())
                addProperty("repetition", repetition)
                add("runtime_metadata", runtime)
                add("fixture_identity", Gson().toJsonTree(identity))
                add("inputs", Gson().toJsonTree(inputs))
                add("paired_launch", pair.report)
                add("environment", pair.report.get("environment").deepCopy())
                add("jvm_arguments", pair.report.get("cpu_jvm_arguments").deepCopy())
                add("phases", phases)
                addProperty("native_uploads", "N/A: native-free patch index operations")
                addProperty("gpu_time", "N/A: native-free patch index operations")
                addProperty("fps", "N/A: no frame-rate claim")
            },
        )
    }

    private fun externalInputs(): Map<String, Path> {
        val controls = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs"))))
        val fixture = JmhFixtureSelection.inputs()
        require(controls.keys.intersect(fixture.keys).isEmpty() && ("remote-api" in controls).not() && ("remote-api" in fixture).not())
        return controls + mapOf("remote-api" to Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI())) + fixture
    }

    private fun measure(
        nodes: Int,
        shape: RemotePatchIndexBenchmark.Shape,
        change: RemotePatchIndexBenchmark.Change,
        operation: RemotePatchIndexOperation,
        plan: PerformancePlan,
    ): JsonObject {
        val scene = RemotePatchIndexBenchmark.Scene()
        scene.nodes = nodes
        scene.shape = shape
        scene.change = change
        return scene.use {
            scene.setup()
            val sample = JvmPerformanceRunner.measure("$nodes/$shape/$change/$operation", plan) { operation.run(scene) }
            sample.evidence.apply {
                addProperty("nodes", nodes)
                addProperty("shape", shape.name)
                addProperty("change", change.name)
                addProperty("operation", operation.name)
                addProperty("operations_per_sample", 1)
                addProperty("warmup", plan.warmup)
                addProperty("measured_operations", plan.samples)
                addProperty("processed_operations", plan.warmup + plan.samples)
            }
        }
    }
}
