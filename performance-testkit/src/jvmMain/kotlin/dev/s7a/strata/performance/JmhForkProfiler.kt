package dev.s7a.strata.performance

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.results.AggregationPolicy
import org.openjdk.jmh.results.IterationResult
import org.openjdk.jmh.results.Result
import org.openjdk.jmh.results.ScalarResult
import org.openjdk.jmh.runner.Runner
import java.nio.file.Path

/**
 * Standard JMH internal profiler that certifies the actual loaded fork before warm-up or measurement.
 * JMH constructs this capability in the fork; it owns no clock, sampling loop or application operation.
 * A missing or failed profiler cannot produce the required per-iteration provenance result.
 *
 * @param options immutable parent-captured JSON identities, passed through JMH's profiler configuration.
 */
public class JmhForkProfiler(
    options: String,
) : InternalProfiler {
    // JMH discovery ignores constructor failures; retain any failure and throw it from its mandatory iteration hook.
    private val identityCheck =
        runCatching {
            require(options.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
            val expected = checkNotNull(Gson().fromJson(options, JsonObject::class.java))
            val loader = ClassLoader.getSystemClassLoader()
            check(JmhForkProfiler::class.java.classLoader === loader && Thread.currentThread().contextClassLoader === loader)
            val targets = expected.getAsJsonObject("representatives").entrySet().associate { it.key to it.value.asString }
            val runtime = LoadedArtifactMetadata.capture(loader, targets, targets.keys)
            LoadedArtifactMetadata.verifyComplete(runtime)
            check(runtime == expected.getAsJsonObject("runtime")) { "JMH fork loaded another target runtime" }
            val fixtureHashes = expected.getAsJsonObject("fixtures").entrySet().associate { it.key to it.value.asString }
            require(fixtureHashes.isNotEmpty() && fixtureHashes.size <= 16_384)
            val fixtures = fixtureHashes.keys.map { Class.forName(it, false, loader) }
            check(ArtifactIdentity.applicationTrees(fixtures) == fixtureHashes) { "JMH fork loaded another benchmark fixture" }
            check(ArtifactIdentity.fullCodeSource(Runner::class.java) == expected.get("harness").asString) { "JMH fork loaded another harness" }
            check(PerformanceJson.collectorIdentity() == expected.getAsJsonObject("collector")) { "JMH fork loaded another collector" }
            expected.getAsJsonObject("inputs").entrySet().forEach { entry ->
                check(ArtifactIdentity.file(Path.of(entry.key)) == entry.value.asString) { "JMH fork input changed: ${entry.key}" }
            }
        }

    override fun getDescription(): String = "Verifies actual fork artifacts before JMH sampling."

    override fun beforeIteration(
        benchmarkParams: BenchmarkParams,
        iterationParams: IterationParams,
    ) {
        identityCheck.getOrThrow()
    }

    override fun afterIteration(
        benchmarkParams: BenchmarkParams,
        iterationParams: IterationParams,
        result: IterationResult,
    ): Collection<Result<*>> = listOf(ScalarResult("strata.provenance", 1.0, "verified", AggregationPolicy.MIN))
}
