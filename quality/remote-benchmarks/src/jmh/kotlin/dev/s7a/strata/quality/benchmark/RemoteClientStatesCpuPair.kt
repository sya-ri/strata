package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.PerformanceJson
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import org.openjdk.jmh.runner.Runner

/**
 * Immutable supplemental-CPU pairing to one complete JMH run on the same actual JVM and runtime archives.
 * Input, result, collector and source-plan bytes are checked before and after CPU collection; no timing lives here.
 * Source plans contain both revisions and archive inventories so the same external file can be frozen for both sides.
 */
internal class RemoteClientStatesCpuPair(
    receiptPath: Path,
    sourcePath: Path,
    repetition: Int,
    runtime: JsonObject,
    fixtureIdentity: Map<String, String>,
    inputs: Map<String, Path>,
) {
    private val receiptFile = receiptPath.toAbsolutePath().normalize()
    private val sourceFile = sourcePath.toAbsolutePath().normalize()
    private val directory = checkNotNull(receiptFile.parent)
    private val resultFile = directory.resolve("results.json")
    private val collectorFile = directory.resolve("collector.jar")
    private val harnessFile = directory.resolve("harness.jar")
    private val executable = Path.of(ProcessHandle.current().info().command().orElseThrow())
    private val hashes = listOf(receiptFile, resultFile, collectorFile, harnessFile, sourceFile, executable).associateWith(ArtifactIdentity::file).toMutableMap()
    private val environment = environment()
    private val jvmArguments = Gson().toJsonTree(ManagementFactory.getRuntimeMXBean().inputArguments)

    /**
     * Detached run IDs, exact receipt/result paths and hashes, actual environment and validated source/launch identities.
     */
    val report: JsonObject

    init {
        val receipt = read(receiptFile)
        require(receipt.get("contract").asString.contentEquals("strata-jmh-v1") && receipt.get("status").asString.contentEquals("passed"))
        require(receipt.get("repetition").asInt == repetition)
        require(receipt.get("collector_identity") == PerformanceJson.collectorIdentity())
        require(hashes.getValue(collectorFile) == PerformanceJson.collectorIdentity().get("code_source_sha256").asString)
        require(receipt.get("results_sha256").asString == hashes.getValue(resultFile))
        require(receipt.get("harness_sha256").asString == hashes.getValue(harnessFile) && hashes.getValue(harnessFile) == ArtifactIdentity.fullCodeSource(Runner::class.java))
        verifyArchives(receipt, runtime, inputs)
        val fixture = RemoteClientStatesBenchmark::class.java.name
        require(receipt.getAsJsonObject("fixture_identity").get(fixture).asString == fixtureIdentity.getValue(fixture))
        receipt.getAsJsonObject("environment").entrySet().forEach { (key, value) -> require(environment.get(key) == value) }
        val expected = JmhWorkloadInventory.capture(listOf(RemoteClientStatesBenchmark::class.java), setOf("avgt"))
        require(receipt.getAsJsonArray("registered_workloads").map { it.asString }.toSet() == expected)
        val rows =
            Files.newBufferedReader(resultFile, Charsets.UTF_8)
                .use { JsonParser.parseReader(it).asJsonArray }
                .map { it.asJsonObject }
        require(rows.size == expected.size && rows.map(::workload).toSet() == expected)
        require(rows.all { row ->
            row.getAsJsonObject("primaryMetric").get("scoreUnit").asString.contentEquals("us/op") &&
                row.getAsJsonObject("secondaryMetrics").getAsJsonObject("gc.alloc.rate.norm").get("scoreUnit").asString.contentEquals("B/op")
        })
        val controls = controls(rows.first())
        require(rows.all { controls(it) == controls }) { "Paired JMH controls differ across cases" }
        verifyControls(controls)
        val plan = read(sourceFile)
        val source = source(plan, runtime, fixtureIdentity.getValue(fixture))
        report =
            JsonObject().apply {
                addProperty("jmh_run_id", receipt.get("run_id").asString)
                addProperty("jmh_receipt", receiptFile.toString())
                addProperty("jmh_receipt_sha256", hashes.getValue(receiptFile))
                addProperty("jmh_results", resultFile.toString())
                addProperty("jmh_results_sha256", hashes.getValue(resultFile))
                addProperty("source_plan", sourceFile.toString())
                addProperty("source_plan_sha256", hashes.getValue(sourceFile))
                addProperty("source_revision", source.get("revision").asString)
                addProperty("fixture_revision", plan.get("fixture_revision").asString)
                add("source_archive_plan", plan.deepCopy())
                add("environment", environment.deepCopy())
                add("cpu_jvm_arguments", jvmArguments.deepCopy())
                addProperty("cpu_executable", executable.toString())
                addProperty("cpu_executable_sha256", hashes.getValue(executable))
                addProperty("cpu_command", System.getProperty("sun.java.command"))
                addProperty("cpu_classpath", System.getProperty("java.class.path"))
                add("jmh_arguments", receipt.get("arguments").deepCopy())
                add("jmh_controls", controls)
                add("registered_workloads", receipt.get("registered_workloads").deepCopy())
                add("target_archive_identity", archiveIdentity(runtime))
            }
    }

    /**
     * Rejects mutation of any paired receipt, results, archives, source plan or actual launch condition before publication.
     */
    fun verify() {
        require(hashes.all { (path, hash) -> ArtifactIdentity.file(path) == hash })
        require(environment() == environment)
        require(Gson().toJsonTree(ManagementFactory.getRuntimeMXBean().inputArguments) == jvmArguments)
    }

    private fun verifyArchives(
        receipt: JsonObject,
        runtime: JsonObject,
        inputs: Map<String, Path>,
    ) {
        val targets = archiveIdentity(runtime)
        require(archiveIdentity(receipt.getAsJsonObject("runtime_metadata")) == targets)
        require(receipt.getAsJsonObject("target_archives").keySet() == targets.keySet())
        receipt.getAsJsonObject("target_archives").entrySet().forEach { (module, filename) ->
            archive(directory.resolve(filename.asString), targets.get(module).asString)
        }
        require(inputs.keys == receipt.getAsJsonObject("inputs").keySet())
        inputs.forEach { (label, path) ->
            val archived = receipt.getAsJsonObject("inputs").getAsJsonObject(label)
            val hash = ArtifactIdentity.file(path)
            require(archived.get("sha256").asString == hash)
            archive(directory.resolve(archived.get("archive").asString), hash)
            hashes[path.toAbsolutePath().normalize()] = hash
        }
        require(inputs.values.any { it.toAbsolutePath().normalize() == sourceFile }) { "Archive the source plan as an immutable JMH fixture input" }
    }

    private fun archive(
        path: Path,
        hash: String,
    ) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.parent == directory && ArtifactIdentity.file(normalized) == hash)
        hashes[normalized] = hash
    }

    private fun verifyControls(controls: JsonObject) {
        require(controls.get("mode").asString.contentEquals("avgt") && controls.get("forks").asInt == 1 && controls.get("threads").asInt == 1)
        require(controls.get("warmupIterations").asInt == 3 && controls.get("warmupTime").asString.contentEquals("1 s"))
        require(controls.get("measurementIterations").asInt == 5 && controls.get("measurementTime").asString.contentEquals("1 s"))
        require(controls.get("warmupBatchSize").asInt == 1 && controls.get("measurementBatchSize").asInt == 1)
        require(controls.get("jdkVersion").asString == System.getProperty("java.version"))
        require(controls.get("vmName").asString == System.getProperty("java.vm.name") && controls.get("vmVersion").asString == System.getProperty("java.vm.version"))
        require(Files.isSameFile(Path.of(controls.get("jvm").asString), executable)) { "Supplemental CPU and JMH must use the same actual Java executable" }
        require(controls.get("jvmArgs") == jvmArguments) { "Supplemental CPU and JMH must use identical actual JVM arguments" }
    }

    private fun source(
        plan: JsonObject,
        runtime: JsonObject,
        fixture: String,
    ): JsonObject {
        require(plan.get("fixture_revision").asString.matches(REVISION))
        require(plan.get("fixture_tree_sha256").asString == fixture)
        val sources = plan.getAsJsonArray("sources").map { it.asJsonObject }
        require(sources.size == 2 && sources.map { it.get("revision").asString }.toSet().size == 2)
        sources.forEach { source ->
            require(source.get("revision").asString.matches(REVISION))
            val archives = source.getAsJsonObject("archives")
            require(archives.keySet() == setOf("api", "core", "remote"))
            require(archives.entrySet().all { it.value.asString.matches(SHA256) })
        }
        return sources.single { archiveIdentity(runtime) == it.getAsJsonObject("archives") }
    }

    private fun read(path: Path): JsonObject = Files.newBufferedReader(path, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    private fun archiveIdentity(runtime: JsonObject): JsonObject =
        JsonObject().apply {
            runtime.getAsJsonArray("modules").forEach { entry ->
                val module = entry.asJsonObject
                addProperty(module.get("module").asString, module.getAsJsonObject("codeSource").get("sha256").asString)
            }
        }

    private fun workload(row: JsonObject): String = JmhPerformanceRunner.workloadIdentity(row.get("benchmark").asString, row.get("mode").asString, row.getAsJsonObject("params").entrySet().associate { it.key to it.value.asString })

    private fun controls(row: JsonObject): JsonObject =
        JsonObject().apply {
            listOf("mode", "threads", "forks", "jvm", "jvmArgs", "jdkVersion", "vmName", "vmVersion", "warmupIterations", "warmupTime", "warmupBatchSize", "measurementIterations", "measurementTime", "measurementBatchSize").forEach { add(it, row.get(it).deepCopy()) }
        }

    private fun environment(): JsonObject =
        JsonObject().apply {
            listOf("java.version", "java.vendor", "java.vm.name", "java.vm.version", "java.home", "os.name", "os.version", "os.arch").forEach { addProperty(it, System.getProperty(it)) }
            addProperty("available_processors", Runtime.getRuntime().availableProcessors().toString())
            addProperty("host", System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"))
        }

    private companion object {
        val REVISION = Regex("[0-9a-f]{40}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
