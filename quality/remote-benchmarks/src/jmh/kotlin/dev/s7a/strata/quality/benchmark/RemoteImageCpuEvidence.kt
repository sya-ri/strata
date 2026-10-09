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
 * Shared-kit owner CPU/allocation for the same 36 actual image codec/server operations used by JMH.
 * Input preparation, independent checks and serialization are outside sample boundaries.
 */
public object RemoteImageCpuEvidence {
    /**
     * Accepts fresh output, repetition 0..2, exact paired JMH receipt and common frozen source/archive plan.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 4)
        val repetition = args[1].toInt()
        require(repetition in 0..2)
        RemoteImageBenchmark.verifyWork()
        val targets = mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteServerSession")
        val runtime = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val fixtures = listOf(RemoteImageBenchmark::class.java, RemoteImageCpuEvidence::class.java)
        val identity = ArtifactIdentity.applicationTrees(fixtures)
        val inputFiles = externalInputs()
        val inputs = inputFiles.mapValues { ArtifactIdentity.file(it.value) }
        val pair = RemoteCpuPair(Path.of(args[2]), Path.of(args[3]), repetition, runtime, identity, inputFiles, RemoteImageBenchmark::class.java)
        val plan = PerformancePlan(warmup = 100, samples = 200)
        val phases = JsonArray()
        for (extent in RemoteImageBenchmark.Extent.entries) {
            for (workload in RemoteImageBenchmark.Workload.entries) phases.add(measure(extent, workload, plan))
        }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(ArtifactIdentity.applicationTrees(fixtures) == identity)
        check(externalInputs().mapValues { ArtifactIdentity.file(it.value) } == inputs)
        pair.verify()
        PerformanceJson.writeNew(
            Path.of(args[0]),
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("workload_id", "remote-image-encoding-v1")
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
                addProperty("native_uploads", "N/A: native-free image codec/server projection")
                addProperty("gpu_time", "N/A: no GUI/native consumption")
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
        extent: RemoteImageBenchmark.Extent,
        workload: RemoteImageBenchmark.Workload,
        plan: PerformancePlan,
    ): JsonObject =
        RemoteImageBenchmark.Scene().use { scene ->
            scene.extent = extent
            scene.workload = workload
            scene.setup()
            val controls = scene.controls()
            val sample = JvmPerformanceRunner.measure("$extent/$workload", plan) { scene.perform() }
            sample.evidence.apply {
                addProperty("extent", extent.name)
                addProperty("workload", workload.name)
                controls.forEach { (name, value) -> addProperty(name, value) }
                addProperty("operations_per_sample", 1)
                addProperty("warmup", plan.warmup)
                addProperty("measured_operations", plan.samples)
                addProperty("processed_operations", plan.warmup + plan.samples)
            }
        }
}
