package dev.s7a.strata.performance

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.CommandLineOptions
import org.openjdk.jmh.runner.options.Options
import org.openjdk.jmh.runner.options.OptionsBuilder
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
     * External fixture inputs are preserved separately; generated inputs are identified by the fixture class tree.
     * The standard JMH fork classpath requires fixtures and collector on the application/context classloader.
     */
    public fun run(
        arguments: Array<String>,
        fixtures: List<Class<*>>,
        targets: Map<String, String>,
        output: Path,
        repetition: Int,
        expectedWorkloads: Set<String>,
        inputs: Map<String, Path> = emptyMap(),
    ) {
        val cpuCosts = if (System.getProperty("strata.performance.cpuContext") != null) JmhCpuCosts() else null
        require(fixtures.isNotEmpty() && targets.isNotEmpty() && expectedWorkloads.isNotEmpty() && 0 <= repetition)
        val inputArguments = arguments.copyOf()
        val inputFixtures = fixtures.toList()
        val inputTargets = targets.toMap()
        val inputWorkloads = expectedWorkloads.toSet()
        val cpuContext = System.getProperty("strata.performance.cpuContext")?.let { JvmEvidenceFiles.document(Path.of(it)) }
        val (probe, inputFiles) = cpuInputs(inputs, cpuContext)
        require(System.getProperty("strata.performance.cpuAdmission") == null || System.getProperty("strata.performance.cpuContext") == null) { "CPU admission and measurement are separate invocations" }
        System.getProperty("strata.performance.cpuAdmission")?.let { directory ->
            JmhCpuAdmission.capture(Path.of(directory), inputArguments, inputFixtures, inputTargets, inputWorkloads, inputFiles)
            return
        }
        val inputHashes = inputFiles.mapValues { ArtifactIdentity.file(it.value) }
        val destination = output.toAbsolutePath().normalize()
        require(Files.exists(destination).not()) { "JMH evidence already exists: $destination" }
        val loader = forkLoader(inputFixtures)
        val cli = CommandLineOptions(*inputArguments)
        val forkIdentity = JmhForkConfiguration.capture(cli, inputFixtures, inputTargets, inputFiles)
        val runId = UUID.randomUUID().toString()
        attachCpuIdentity(forkIdentity, cpuContext, probe, destination, runId)
        val runtime = forkIdentity.getAsJsonObject("runtime")
        val fixtureIdentity = forkIdentity.getAsJsonObject("fixtures").entrySet().associate { it.key to it.value.asString }
        val certifiedClasses = fixtureIdentity.keys.map { Class.forName(it, false, loader) }
        val harnessIdentity = forkIdentity.get("harness").asString
        val collectorIdentity = forkIdentity.getAsJsonObject("collector")
        val gson = Gson()
        val options =
            OptionsBuilder()
                .parent(cli)
                // Own profilers precede the parent CLI's GC profiler; reverse completion closes GC before CPU journals.
                .addProfiler(JmhForkProfiler::class.java, forkIdentity.toString())
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.JSON)
                .result(destination.resolve("results.json").toString())
                .build()
        executeHarness(destination, options, cpuCosts, inputWorkloads)
        check(ArtifactIdentity.applicationTrees(certifiedClasses) == fixtureIdentity) { "Benchmark fixture changed during JMH execution" }
        check(ArtifactIdentity.fullCodeSource(Runner::class.java) == harnessIdentity) { "JMH harness changed during execution" }
        check(PerformanceJson.collectorIdentity() == collectorIdentity) { "Collector changed during JMH execution" }
        check(LoadedArtifactMetadata.capture(loader, inputTargets, inputTargets.keys) == runtime) { "Measured runtime changed during JMH execution" }
        val archivedInputs = JmhEvidenceArchives.inputs(inputFiles, inputHashes, destination)
        val archivedTargets = JmhEvidenceArchives.targets(runtime, destination)
        JmhEvidenceArchives.copy(sourceUrl(Runner::class.java), destination.resolve("harness.jar"), harnessIdentity)
        JmhEvidenceArchives.copy(sourceUrl(JvmPerformanceMeter::class.java), destination.resolve("collector.jar"), collectorIdentity.get("code_source_sha256").asString)
        PerformanceJson.writeNew(
            destination.resolve("receipt.json"),
            receiptHeader(destination, runId, repetition, cpuContext, harnessIdentity).apply {
                add("arguments", gson.toJsonTree(inputArguments))
                add("registered_workloads", gson.toJsonTree(inputWorkloads.sorted()))
                add("fixture_identity", gson.toJsonTree(fixtureIdentity))
                add("runtime_metadata", runtime)
                add("target_archives", archivedTargets)
                add("inputs", archivedInputs)
                add("environment", environment())
            },
        )
        if (cpuContext != null) cpuCosts?.publish(destination, runId, cpuContext)
    }

    private fun executeHarness(
        destination: Path,
        options: Options,
        costs: JmhCpuCosts?,
        workloads: Set<String>,
    ) {
        Files.createDirectories(checkNotNull(destination.parent))
        Files.createDirectory(destination)
        costs?.startHarness()
        val results = Runner(options).run()
        costs?.finishHarness()
        verifyMatrix(results, workloads)
    }

    private fun cpuInputs(
        inputs: Map<String, Path>,
        context: JsonObject?,
    ): Pair<Path?, Map<String, Path>> {
        val cpu = System.getProperty("strata.performance.cpuAdmission") != null || context != null
        val probe = if (cpu) Path.of(checkNotNull(System.getProperty("strata.performance.cpuProbe")) { "CPU plans require their frozen host probe" }) else null
        val probeInput = probe?.let { mapOf("cpu-executor-probe" to it) }.orEmpty()
        require(inputs.keys.intersect(probeInput.keys).isEmpty()) { "CPU probe input label overlaps fixture controls" }
        return probe to fixtureInputs(inputs + probeInput)
    }

    private fun attachCpuIdentity(
        identity: JsonObject,
        context: JsonObject?,
        probe: Path?,
        destination: Path,
        runId: String,
    ) {
        if (context != null) {
            check(ArtifactIdentity.file(checkNotNull(probe)) == context.textField("executor_probe_sha256")) { "Actual CPU probe bytes differ" }
            identity.add(
                "cpu",
                JsonObject().apply {
                    addProperty("run_id", runId)
                    addProperty("directory", destination.resolve("cpu-forks").toString())
                    add("context", context)
                },
            )
        }
    }

    private fun receiptHeader(
        destination: Path,
        runId: String,
        repetition: Int,
        context: JsonObject?,
        harnessIdentity: String,
    ): JsonObject =
        JsonObject().apply {
            addProperty("contract", "strata-jmh-v1")
            addProperty("status", "passed")
            addProperty("run_id", runId)
            context?.let { add("cpu_context", it) }
            addProperty("repetition", repetition)
            addProperty("fork_verification", "loaded-artifacts-per-iteration-v1")
            addProperty("results_sha256", ArtifactIdentity.file(destination.resolve("results.json")))
            addProperty("harness_sha256", harnessIdentity)
        }

    private fun environment(): JsonObject =
        Gson()
            .toJsonTree(
                listOf("java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch").associateWith(System::getProperty) +
                    mapOf("available_processors" to Runtime.getRuntime().availableProcessors().toString()),
            ).asJsonObject

    private fun sourceUrl(type: Class<*>): String = checkNotNull(type.protectionDomain.codeSource).location.toExternalForm()

    private fun verifyMatrix(
        results: Collection<RunResult>,
        expected: Set<String>,
    ) {
        check(results.isNotEmpty()) { "JMH completed without any benchmark results" }
        val actual =
            results.map { result ->
                val parameters = result.params
                workloadIdentity(parameters.benchmark, parameters.mode.shortLabel(), parameters.paramsKeys.associateWith(parameters::getParam))
            }
        check(actual.size == actual.toSet().size && actual.toSet() == expected) { "JMH did not complete the registered workload matrix" }
        check(results.all { result -> result.primaryResult.score.isFinite() && 0 <= result.primaryResult.score }) { "JMH returned an unavailable primary measurement" }
        check(
            results.all { run ->
                0 < run.params.forks && run.benchmarkResults.size == run.params.forks &&
                    run.benchmarkResults.all { fork ->
                        fork.iterationResults.size == run.params.measurement.count &&
                            fork.iterationResults.all { iteration ->
                                iteration.secondaryResults["strata.provenance"]?.score == 1.0
                            }
                    }
            },
        ) { "JMH fork provenance did not complete every measured iteration" }
    }

    private fun forkLoader(fixtures: List<Class<*>>): ClassLoader {
        val loader = ClassLoader.getSystemClassLoader()
        require(loader === Thread.currentThread().contextClassLoader && JmhPerformanceRunner::class.java.classLoader === loader) {
            "JMH evidence requires the standard application/context classloader"
        }
        require(fixtures.all { Class.forName(it.name, false, loader) === it }) { "JMH fixture resolves outside its fork classpath" }
        return loader
    }

    private fun fixtureInputs(inputs: Map<String, Path>): Map<String, Path> {
        val files = inputs.toSortedMap().mapValues { it.value.toAbsolutePath().normalize() }
        require(files.size <= 16_384 && files.keys.all(String::isNotBlank))
        var total = 0L
        files.values.forEach { path ->
            require(Files.isRegularFile(path)) { "JMH fixture input is not a regular file: $path" }
            total = Math.addExact(total, Files.size(path))
            require(total <= 64L * 1024 * 1024) { "JMH fixture input inventory exceeds its byte bound" }
        }
        return files
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
}
