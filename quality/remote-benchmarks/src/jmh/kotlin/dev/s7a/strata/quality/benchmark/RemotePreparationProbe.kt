package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import java.lang.management.ManagementFactory
import java.nio.file.Path

/**
 * Untimed child executes two actual operations per case to include both sides of changed/unchanged declaration controls.
 * No production hook, map wrapper or clock is used; all observed archive/fixture and executable identities are published.
 */
public object RemotePreparationProbe {
    /**
     * Runs the complete frozen fixture with debug-suspension admission explicitly separate from timing.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1)
        RemotePreparationBenchmark.verifyWork()
        val targets = mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteServerSession")
        val runtime = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val fixtures = listOf(RemotePreparationBenchmark::class.java, RemotePreparationProbe::class.java)
        val identity = ArtifactIdentity.applicationTrees(fixtures)
        for (size in RemotePreparationBenchmark.Size.entries) {
            for (chain in RemotePreparationBenchmark.Chain.entries) for (workload in RemotePreparationBenchmark.Workload.entries) {
                RemotePreparationBenchmark.Scene(60000).use { scene ->
                    scene.size = size
                    scene.chain = chain
                    scene.workload = workload
                    scene.setup()
                    begin(size.name, chain.name, workload.name)
                    repeat(2) { scene.perform() }
                    finish()
                }
            }
        }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(ArtifactIdentity.applicationTrees(fixtures) == identity)
        PerformanceJson.writeNew(
            Path.of(args.single()),
            JsonObject().apply {
                addProperty("workload_id", "remote-client-preparation-construction-observation-v1")
                addProperty("status", "passed")
                addProperty("observed_operations_per_case", 2)
                addProperty("reconstruction_millis", 60000)
                val executable = Path.of(ProcessHandle.current().info().command().orElseThrow())
                addProperty("java_executable", executable.toString())
                addProperty("java_executable_sha256", ArtifactIdentity.file(executable))
                addProperty("java_version", System.getProperty("java.version"))
                addProperty("java_classpath", System.getProperty("java.class.path"))
                add("jvm_arguments", Gson().toJsonTree(ManagementFactory.getRuntimeMXBean().inputArguments))
                add("runtime_metadata", runtime)
                add("fixture_identity", Gson().toJsonTree(identity))
            },
        )
    }

    /**
     * Supplies typed case names to the standard debugger without retaining a production callback.
     */
    @JvmStatic
    public fun begin(size: String, chain: String, workload: String) {
        require(size.isNotEmpty() && chain.isNotEmpty() && workload.isNotEmpty())
    }

    /**
     * Ends production observation before fixture cleanup and provenance publication.
     */
    @JvmStatic
    public fun finish() = Unit
}
