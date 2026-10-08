package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import java.lang.management.ManagementFactory
import java.nio.file.Path

/**
 * Debuggee for untimed actual Bytes snapshot observation; no hooks or counters are installed in production code.
 * Debug suspension can exceed a normal reconstruction deadline, so this probe records its extended deadline explicitly.
 * It is never a JMH or supplemental CPU input and does not provide any timing or allocation-rate evidence.
 */
public object RemoteBytesCopyProbe {
    /**
     * Runs each prepared operation once, publishing actual child-VM archive and fixture identities to a fresh file.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1)
        val targets = mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree")
        val runtime = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val fixtures = listOf(RemoteBytesBenchmark::class.java, RemoteBytesOperation::class.java, RemoteBytesCopyProbe::class.java)
        val identity = ArtifactIdentity.applicationTrees(fixtures)
        for (corpus in RemoteBytesBenchmark.Corpus.entries) {
            val scene = RemoteBytesBenchmark.Scene(RemoteLimits(reconstructionMillis = 60000))
            scene.corpus = corpus
            scene.setup()
            for (operation in RemoteBytesOperation.entries) {
                begin(corpus.name, operation.name)
                finish(operation.run(scene))
            }
        }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(ArtifactIdentity.applicationTrees(fixtures) == identity)
        PerformanceJson.writeNew(
            Path.of(args.single()),
            JsonObject().apply {
                addProperty("workload_id", "remote-bytes-copy-observation-v1")
                addProperty("status", "passed")
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
     * Marks one operation's entry for JDI; arguments are read from the suspended frame without a retained callback.
     */
    @JvmStatic
    public fun begin(
        corpus: String,
        operation: String,
    ) {
        require(corpus.isNotEmpty() && operation.isNotEmpty())
    }

    /**
     * Marks the end of the operation before debug-side publication; the actual result remains live until this call.
     */
    @JvmStatic
    public fun finish(result: Any) {
        check(result is ByteArray || result is ProjectionValue || result is RemoteMessage)
    }
}
