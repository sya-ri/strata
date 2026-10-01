package dev.s7a.strata.performance

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.CommandLineOptions
import org.openjdk.jmh.runner.options.OptionsBuilder
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Provenance adapter around standard JMH; JMH owns forks, warm-up, clocks, samples, profilers and result serialization.
 * Consumers provide their benchmark classes, target representatives and standard CLI options, rather than another engine.
 * JMH is a consumer-supplied dependency; this test-only adapter does not add it to the kit's runtime POM.
 */
public object JmhPerformanceRunner {
    /**
     * Executes one independent JMH invocation and publishes a collector-bound receipt only after complete success.
     * The output directory must not already exist; failed raw output is retained for diagnosis without a success receipt.
     * Actual fixture, harness, and target code sources are verified unchanged around the invocation, outside JMH timing.
     */
    public fun run(
        arguments: Array<String>,
        fixtures: List<Class<*>>,
        targets: Map<String, String>,
        output: Path,
        repetition: Int,
        expectedWorkloads: Set<String>,
    ) {
        require(fixtures.isNotEmpty() && targets.isNotEmpty() && expectedWorkloads.isNotEmpty() && 0 <= repetition)
        val inputArguments = arguments.copyOf()
        val inputFixtures = fixtures.toList()
        val inputTargets = targets.toMap()
        val inputWorkloads = expectedWorkloads.toSet()
        val destination = output.toAbsolutePath().normalize()
        require(Files.exists(destination).not()) { "JMH evidence already exists: $destination" }
        val loader = JmhPerformanceRunner::class.java.classLoader
        val runtime = LoadedArtifactMetadata.capture(loader, inputTargets, inputTargets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val fixtureIdentity = ArtifactIdentity.applicationTrees(inputFixtures)
        val harnessIdentity = ArtifactIdentity.fullCodeSource(Runner::class.java)
        val collectorIdentity = PerformanceJson.collectorIdentity()
        val options =
            OptionsBuilder()
                .parent(CommandLineOptions(*inputArguments))
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.JSON)
                .result(destination.resolve("results.json").toString())
                .build()
        Files.createDirectories(checkNotNull(destination.parent))
        Files.createDirectory(destination)
        val results = Runner(options).run()
        check(results.isNotEmpty()) { "JMH completed without any benchmark results" }
        val actualWorkloads =
            results.map { result ->
                val parameters = result.params
                workloadIdentity(parameters.benchmark, parameters.mode.shortLabel(), parameters.paramsKeys.associateWith(parameters::getParam))
            }
        check(actualWorkloads.size == actualWorkloads.toSet().size && actualWorkloads.toSet() == inputWorkloads) { "JMH did not complete the registered workload matrix" }
        check(results.all { result -> result.primaryResult.score.isFinite() && 0 <= result.primaryResult.score }) { "JMH returned an unavailable primary measurement" }
        check(ArtifactIdentity.applicationTrees(inputFixtures) == fixtureIdentity) { "Benchmark fixture changed during JMH execution" }
        check(ArtifactIdentity.fullCodeSource(Runner::class.java) == harnessIdentity) { "JMH harness changed during execution" }
        check(PerformanceJson.collectorIdentity() == collectorIdentity) { "Collector changed during JMH execution" }
        check(LoadedArtifactMetadata.capture(loader, inputTargets, inputTargets.keys) == runtime) { "Measured runtime changed during JMH execution" }
        val archivedTargets = archiveTargets(runtime, destination)
        archive(sourceUrl(Runner::class.java), destination.resolve("harness.jar"), harnessIdentity)
        archive(sourceUrl(JvmPerformanceMeter::class.java), destination.resolve("collector.jar"), collectorIdentity.get("code_source_sha256").asString)
        val gson = Gson()
        PerformanceJson.writeNew(
            destination.resolve("receipt.json"),
            JsonObject().apply {
                addProperty("contract", "strata-jmh-v1")
                addProperty("status", "passed")
                addProperty("run_id", UUID.randomUUID().toString())
                addProperty("repetition", repetition)
                addProperty("results_sha256", ArtifactIdentity.file(destination.resolve("results.json")))
                addProperty("harness_sha256", harnessIdentity)
                add("arguments", gson.toJsonTree(inputArguments))
                add("registered_workloads", gson.toJsonTree(inputWorkloads.sorted()))
                add("fixture_identity", gson.toJsonTree(fixtureIdentity))
                add("runtime_metadata", runtime)
                add("target_archives", archivedTargets)
                add("environment", environment())
            },
        )
    }

    private fun environment(): JsonObject =
        Gson()
            .toJsonTree(
                listOf("java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch").associateWith(System::getProperty) +
                    mapOf("available_processors" to Runtime.getRuntime().availableProcessors().toString()),
            ).asJsonObject

    private fun sourceUrl(type: Class<*>): String = checkNotNull(type.protectionDomain.codeSource).location.toExternalForm()

    private fun archiveTargets(
        runtime: JsonObject,
        destination: Path,
    ): JsonObject =
        JsonObject().apply {
            runtime.getAsJsonArray("modules").forEachIndexed { index, entry ->
                val module = entry.asJsonObject
                val archiveName = "target-$index.jar"
                val origin = module.getAsJsonObject("codeSource")
                archive(origin.get("url").asString, destination.resolve(archiveName), origin.get("sha256").asString)
                addProperty(module.get("module").asString, archiveName)
            }
        }

    /**
     * Constructs an exact benchmark/mode/parameter identity for a consumer's expected executable matrix.
     * JMH method discovery and real input values remain consumer-owned; the runner rejects missing or duplicate results.
     */
    public fun workloadIdentity(
        benchmark: String,
        mode: String,
        parameters: Map<String, String>,
    ): String {
        require(benchmark.isNotBlank() && mode.isNotBlank() && parameters.keys.all(String::isNotBlank))
        return Gson().toJson(listOf(benchmark, mode, parameters.toSortedMap()))
    }

    private fun archive(
        source: String,
        destination: Path,
        expectedHash: String,
    ) {
        val location = URI(source)
        require(LocalResourceProtocol.decode(location.scheme) == LocalResourceProtocol.File && location.rawAuthority == null)
        val path = Path.of(location)
        require(Files.isRegularFile(path) && Files.size(path) <= 64L * 1024 * 1024) { "JMH provenance requires an actual bounded, separate JAR: $path" }
        require(ArtifactIdentity.file(path) == expectedHash) { "JMH loaded archive changed before preservation" }
        Files.copy(path, destination)
        require(ArtifactIdentity.file(destination) == expectedHash) { "JMH archive snapshot differs from loaded bytes" }
    }
}
