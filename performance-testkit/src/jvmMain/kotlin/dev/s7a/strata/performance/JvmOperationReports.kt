package dev.s7a.strata.performance

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.openjdk.jmh.runner.options.OptionsBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Optional compiled operation-report connection to a completed frozen JMH invocation.
 * The existing runner owns sampling and the existing report processor owns comparison.
 * Original JMH archives, loaded identities and inputs remain mandatory independent evidence.
 */
public object JvmOperationReports {
    private val gson = Gson()
    private val conditions = setOf("protocol", "selection", "plan", "rows", "fixture_identity", "adapter_identity", "inputs", "input_sources", "environment", "source_bindings")
    private val phaseConditions = setOf("samples", "benchmark", "parameters", "boundary", "operations_per_sample", "control")
    private val metrics =
        listOf(
            PerformanceReportMetric("owner_thread_cpu_ns_per_sample", listOf("owner_thread_cpu_ns"), listOf("samples")),
            PerformanceReportMetric("owner_thread_allocated_bytes_per_sample", listOf("owner_thread_allocated_bytes"), listOf("samples")),
            PerformanceReportMetric("wall_ns_per_sample", listOf("wall_total_ns"), listOf("samples")),
        ) +
            listOf("p50", "p95", "p99", "max").flatMap { percentile ->
                listOf(
                    PerformanceReportMetric("owner_thread_cpu_${percentile}_ns", listOf("owner_thread_cpu_distribution", "${percentile}_ns")),
                    PerformanceReportMetric("owner_thread_allocation_${percentile}_bytes", listOf("owner_thread_allocation_distribution", "${percentile}_ns")),
                    PerformanceReportMetric("wall_${percentile}_ns", listOf("wall_distribution", "${percentile}_ns")),
                )
            }

    /**
     * Collects one fresh supplemental invocation through the existing selected fixture classpath.
     */
    @JvmStatic
    public fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Provide one operation collection request" }
        collect(JvmEvidenceFiles.document(Path.of(arguments.single())))
    }

    private fun collect(request: JsonObject) {
        val collector = Path.of(request.textField("collector"))
        val binding = Path.of(request.textField("jmh_directory"))
        val original = JmhRunEvidence.load(binding, collector)
        require(JmhPerformanceRunner.environment() == original.receipt.get("environment")) { "Operation JVM or host differs from original JMH evidence" }
        val fixtures = JmhFixtureSelection.select(emptyList())
        val expectedAdapters = request.objectField("adapters").entrySet().associate { it.key to it.value.textValue() }
        val adapters = JmhFixtureSelection.operationAdapters(fixtures, expectedAdapters)
        val rows = adapters.flatMap { it.rows }.map { it.copy(parameters = it.parameters.toSortedMap()) }
        validateRows(rows, request.countField("expected_rows"), request.countField("expected_controls"))
        val selected = selection(fixtures, rows, original.receipt)
        val plan = PerformancePlan(warmup = request.countField("warmup"), samples = request.countField("samples"))
        val targets = original.receipt.objectField("runtime_metadata").arrayField("modules").associate { value -> value.asJsonObject.let { it.textField("module") to it.textField("representativeClass") } }
        val inputs = original.receipt.objectField("inputs").entrySet().associate { (name, value) -> name to JvmEvidenceFiles.preservedPath(binding, value.asJsonObject.textField("archive")) }
        val consumedInputs = JvmOperationInputs.capture(adapters, original.inputs)
        val fixtureClasses = original.receipt.objectField("fixture_identity").keySet().map { Class.forName(it, false, javaClass.classLoader) }
        val identity = JmhForkConfiguration.capture(OptionsBuilder().include(".*").forks(1).build(), fixtures, targets, inputs)
        require(identity.get("runtime") == original.receipt.get("runtime_metadata") && identity.get("fixtures") == original.receipt.get("fixture_identity")) { "Loaded operation closure differs from original JMH binding" }
        val adapterIdentity = ArtifactIdentity.applicationTrees(adapters.map { it.javaClass })
        require(adapterIdentity.values.all { it in original.receipt.objectField("fixture_identity").entrySet().map { entry -> entry.value.textValue() } }) { "Operation adapter is outside the frozen fixture closure" }
        val destination = Path.of(request.textField("output")).toAbsolutePath().normalize()
        require(Files.exists(destination).not()) { "Operation evidence already exists" }
        JmhFixtureSelection.verifyWork(fixtures)
        val collected = adapters.flatMap { adapter -> adapter.collect(plan).entries.map { it.key to it.value } }
        require(collected.map { it.first }.toSet().size == collected.size && collected.map { it.first }.toSet() == rows.map { it.id }.toSet()) { "Incomplete or duplicate operation reports" }
        val phases = phases(rows, collected.toMap(), plan.samples)
        require(JmhForkConfiguration.capture(OptionsBuilder().include(".*").forks(1).build(), fixtures, targets, inputs) == identity) { "Loaded operation identities changed during collection" }
        require(ArtifactIdentity.applicationTrees(fixtureClasses) == original.receipt.objectField("fixture_identity").entrySet().associate { it.key to it.value.textValue() } && ArtifactIdentity.applicationTrees(adapters.map { it.javaClass }) == adapterIdentity) { "Operation fixture or adapter changed" }
        require(gson.toJsonTree(adapters.flatMap { it.rows }.map { it.copy(parameters = it.parameters.toSortedMap()) }) == gson.toJsonTree(rows)) { "Operation adapter rows changed during collection" }
        require(JvmOperationInputs.capture(adapters, original.inputs) == consumedInputs) { "Consumed operation inputs changed during collection" }
        JmhRunEvidence.load(binding, collector)
        Files.createDirectories(checkNotNull(destination.parent))
        Files.createDirectory(destination)
        PerformanceJson.writeNew(
            destination.resolve("report.json"),
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("workload_id", "jvm-operation-reports")
                addProperty("protocol", "strata-owner-thread-operation-v1")
                addProperty("status", "passed")
                addProperty("run_id", UUID.randomUUID().toString())
                addProperty("repetition", request.countField("repetition"))
                addProperty("jmh_directory", binding.toAbsolutePath().normalize().toString())
                addProperty("jmh_receipt_sha256", ArtifactIdentity.file(binding.resolve("receipt.json")))
                add("jmh_run_id", original.receipt.get("run_id"))
                add("selection", gson.toJsonTree(selected.sorted()))
                add("plan", gson.toJsonTree(plan))
                add("rows", gson.toJsonTree(rows))
                add("fixture_identity", identity.get("fixtures"))
                add("adapter_identity", gson.toJsonTree(adapterIdentity))
                add("runtime_identities", original.targets)
                add("inputs", original.inputs)
                add("input_sources", consumedInputs)
                add("environment", original.receipt.get("environment"))
                add("source_bindings", request.get("source_bindings"))
                add("phases", phases)
            },
        )
    }

    private fun selection(
        fixtures: List<Class<*>>,
        rows: List<JvmOperationReportRow>,
        original: JsonObject,
    ): Set<String> {
        val includes = JmhFixtureSelection.methods(fixtures).map { "^${Regex.escape(it)}$" }
        val selected = JmhWorkloadInventory.capture(fixtures, setOf("avgt", "sample"), JmhFixtureSelection.parameters(), includes)
        val rowCases = rows.flatMap { row -> listOf("avgt", "sample").map { JmhPerformanceRunner.workloadIdentity(row.benchmark, it, row.parameters) } }.toSet()
        require(selected == rowCases && selected.size == rows.size * 2) { "Operation rows differ from the complete selected JMH inventory" }
        val originalCases = original.arrayField("registered_workloads").map { it.textValue() }.toSet()
        require(originalCases == rows.map { JmhPerformanceRunner.workloadIdentity(it.benchmark, "avgt", it.parameters) }.toSet()) { "Operation binding differs from original JMH rows" }
        return selected
    }

    private fun phases(
        rows: List<JvmOperationReportRow>,
        collected: Map<String, JsonObject>,
        samples: Int,
    ): JsonArray =
        JsonArray().apply {
            rows.forEach { row ->
                val phase = collected.getValue(row.id).deepCopy()
                validateSample(phase, samples)
                require(phase.textField("name").contentEquals(row.id)) { "Operation report identity differs" }
                phase.addProperty("operation_id", row.id)
                phase.addProperty("benchmark", row.benchmark)
                phase.add("parameters", gson.toJsonTree(row.parameters))
                phase.addProperty("boundary", row.boundary)
                phase.addProperty("operations_per_sample", row.operationsPerSample)
                phase.addProperty("control", row.control)
                add(phase)
            }
        }

    /**
     * Validates both supplemental sides with the existing immutable raw-report processor.
     */
    internal fun compare(request: JsonObject): JsonObject {
        val collector = Path.of(request.textField("collector"))
        val baseline = request.arrayField("baseline").map { Path.of(it.textValue()) }
        val candidate = request.arrayField("candidate").map { Path.of(it.textValue()) }
        val reference = JvmEvidenceFiles.document(baseline.first())
        val rowCount = reference.arrayField("rows").size()
        val contract = PerformanceReportContract("jvm-operation-reports", listOf("operation_id"), rowCount, conditions, phaseConditions, setOf("runtime_identities"))
        return JvmPerformanceReports.compare(baseline, candidate, collector, contract, metrics) { report ->
            require(report.textField("protocol").contentEquals("strata-owner-thread-operation-v1")) { "Wrong operation report protocol" }
            val binding = Path.of(report.textField("jmh_directory"))
            JvmEvidenceFiles.verifyFile(binding.resolve("receipt.json"), report.textField("jmh_receipt_sha256"))
            val original = JmhRunEvidence.load(binding, collector)
            require(report.get("jmh_run_id") == original.receipt.get("run_id") && report.get("run_id") != original.receipt.get("run_id")) { "Copied operation invocation" }
            require(report.get("fixture_identity") == original.receipt.get("fixture_identity") && report.get("inputs") == original.inputs && report.get("runtime_identities") == original.targets && report.get("environment") == original.receipt.get("environment")) { "Changed operation provenance" }
            JvmOperationInputs.verify(report.objectField("input_sources"), original.inputs)
            val samples = report.objectField("plan").countField("samples")
            report.arrayField("phases").forEach { validateSample(it.asJsonObject, samples) }
        }
    }

    /**
     * Rejects duplicate logical IDs, incomplete bindings and ambiguous row declarations before sampling.
     */
    internal fun validateRows(rows: List<JvmOperationReportRow>, count: Int, controls: Int) {
        require(0 < count && rows.size == count && rows.map { it.id }.distinct().size == count && rows.count { it.control } == controls) { "Incomplete or duplicate operation row inventory" }
        require(rows.all { it.id.isNotBlank() && it.benchmark.isNotBlank() && it.boundary.isNotBlank() && 0 < it.operationsPerSample && it.parameters.keys.all(String::isNotBlank) }) { "Invalid operation row" }
    }

    /**
     * Requires complete original distributions; genuinely unsupported counters remain explicit JSON null.
     */
    internal fun validateSample(phase: JsonObject, samples: Int) {
        require(phase.countField("samples") == samples) { "Partial operation samples" }
        listOf("wall_distribution", "owner_thread_cpu_distribution", "owner_thread_allocation_distribution").forEach { field ->
            val distribution = checkNotNull(phase.get(field)) { "Missing operation distribution" }
            if (distribution.isJsonNull.not()) {
                require(distribution.asJsonObject.countField("samples") == samples && distribution.asJsonObject.arrayField("raw").size() == samples) { "Partial operation distribution" }
            }
        }
        listOf("owner_thread_cpu_ns", "owner_thread_allocated_bytes").forEach { checkNotNull(phase.get(it)) { "Missing nullable operation counter" } }
    }
}
