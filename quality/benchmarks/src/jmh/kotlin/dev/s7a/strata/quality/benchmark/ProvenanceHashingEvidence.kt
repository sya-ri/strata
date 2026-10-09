package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.JvmPerformanceReports
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.performance.PerformanceReportContract
import dev.s7a.strata.performance.PerformanceReportMetric
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Complete provenance workload registration for the existing fixed phase engine.
 * Freeze this fixture archive, outer testkit and controls once before collecting either library variant.
 * Compilation, actual isolation certification and missing work observations are separate acceptance gates.
 */
@Suppress("TooManyFunctions") // Registration, collection and independent processing have separate contracts.
internal object ProvenanceHashingEvidence {
    private enum class Command { Collect, Compare }

    private val rejected = setOf(ProvenanceHashingCorpus.Mode.EmptyDirectory, ProvenanceHashingCorpus.Mode.RejectedLoadedTree, ProvenanceHashingCorpus.Mode.RejectedPreservedTree)
    private val measured = ProvenanceHashingCorpus.cases.filter { rejected.contains(it.mode).not() }
    private val contract = PerformanceReportContract(
        workloadId = "artifact-provenance-hashing-v1",
        phaseKeys = listOf("mode", "entries", "shape"),
        phaseCount = measured.size,
        reportConditions = setOf("fixture_identity", "controlled_inputs", "input_manifest_sha256", "environment", "warmup", "rejected_inputs", "work_proof_scope", "non_cpu_scopes"),
        phaseConditions = setOf("samples", "warmup", "input", "verification"),
        variantReportFields = setOf("measured_library"),
        variantPhaseFields = setOf("work_observation"),
    )
    private val metrics = listOf(
        PerformanceReportMetric("cpu_ns_per_operation", listOf("owner_thread_cpu_ns"), listOf("samples")),
        PerformanceReportMetric("allocated_bytes_per_operation", listOf("owner_thread_allocated_bytes"), listOf("samples")),
        PerformanceReportMetric("complete_hash_wall_ns_per_operation", listOf("wall_total_ns"), listOf("samples")),
        PerformanceReportMetric("process_cpu_ns_per_operation", listOf("process_cpu_ns"), listOf("samples")),
        PerformanceReportMetric("all_threads_allocated_bytes_per_operation", listOf("all_threads_allocated_bytes"), listOf("samples")),
    )

    /**
     * Collect takes target JAR, control manifest, corpus manifest, fresh output and repetition index.
     * Compare takes the frozen collector JAR, fresh output, three baseline and three candidate reports.
     * Every command preserves failed raw evidence; subset/quick collection is not supported.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isNotEmpty())
        val command = enumValues<Command>().single { it.name.equals(args.first(), ignoreCase = true) }
        when (command) {
            Command.Collect -> {
                require(args.size == 6)
                collect(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), args[5].toInt())
            }
            Command.Compare -> {
                require(args.size == 9)
                val collector = Path.of(args[1])
                require(Files.isSameFile(origin(PerformanceJson::class.java), collector))
                val comparison = JvmPerformanceReports.compare(args.slice(3..5).map { Path.of(it) }, args.slice(6..8).map { Path.of(it) }, collector, contract, metrics, ::validateReport)
                comparison.addProperty("acceptance_status", "pending-work-proof-and-whole-issue-gates")
                comparison.addProperty("improvement_claim_accepted", false)
                PerformanceJson.writeNew(Path.of(args[2]), comparison)
            }
        }
    }

    private fun collect(target: Path, controlsManifest: Path, corpusManifest: Path, output: Path, repetition: Int) {
        require(repetition in 0..2 && Files.exists(output).not())
        require(Files.isRegularFile(target) && Files.isRegularFile(origin(PerformanceJson::class.java)))
        val report = JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("workload_id", contract.workloadId)
            addProperty("run_id", UUID.randomUUID().toString())
            addProperty("repetition", repetition)
            addProperty("warmup", PerformancePlan().warmup)
            addProperty("work_proof_scope", "Actual entry-read identities and available target tree counters; materialization/encoding counts remain unverified")
            addProperty("improvement_claim_accepted", false)
            add("non_cpu_scopes", JsonObject().apply { listOf("rendering", "upload", "gpu", "gui_gpu").forEach { addProperty(it, "N/A") } })
        }
        try {
            val complete = JvmPerformanceRunner.measure("complete-preparation-collection-verification", PerformancePlan(warmup = 0, samples = 1)) {
                collectAll(target, controlsManifest, corpusManifest, report)
                report.addProperty("status", "passed")
                validateReport(report)
                Unit
            }
            report.add("complete_preparation_collection_verification", complete.report)
            PerformanceJson.writeNew(output, report)
        } catch (failure: Throwable) {
            report.addProperty("status", "failed")
            report.addProperty("failure_type", failure.javaClass.name)
            report.addProperty("failure_message", failure.message)
            runCatching { PerformanceJson.writeNew(output.resolveSibling("${output.fileName}.failed.json"), report) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun collectAll(target: Path, controlsManifest: Path, corpusManifest: Path, report: JsonObject) {
        val controls = JvmPerformanceInputs.read(controlsManifest)
        require(controls.isNotEmpty() && controls.values.none { Files.isSameFile(it, target) || Files.isSameFile(it, origin(PerformanceJson::class.java)) })
        val manifest = readManifest(corpusManifest)
        val rows = manifest.getAsJsonArray("cases").map { it.asJsonObject }
        verifyInputs(rows)
        val controlled = inputIdentity(controls)
        val fixture = fixtureIdentity()
        val manifestHash = ArtifactIdentity.file(corpusManifest)
        report.add("controlled_inputs", controlled)
        report.add("fixture_identity", fixture)
        report.addProperty("input_manifest_sha256", manifestHash)
        report.add("environment", environment())
        ProvenanceHashingTarget(target, controls.values).use { library ->
            val before = library.certify()
            report.add("measured_library", stableMetadata(before))
            report.add("target_before", before)
            val phases = JsonArray()
            val rejections = JsonArray()
            report.add("phases", phases)
            report.add("rejected_inputs", rejections)
            rows.forEach { row ->
                library.prepare(row).use { prepared ->
                    if (mode(row) in rejected) {
                        rejections.add(prepared.rejection())
                    } else {
                        var verification: JsonObject? = null
                        prepared.validate(prepared.operation())
                        val result = JvmPerformanceRunner.measure(rowIdentity(row).id, afterOperation = { _, value -> verification = prepared.validate(value) }) { prepared.operation() }
                        val phase = result.report.apply {
                            listOf("mode", "entries", "shape").forEach { add(it, row.get(it).deepCopy()) }
                            addProperty("warmup", PerformancePlan().warmup)
                            add("input", row.deepCopy())
                            add("verification", checkNotNull(verification))
                            add("work_observation", workObservation(library, row))
                        }
                        phases.add(phase)
                    }
                }
            }
            val after = library.certify()
            check(after == before) { "Measured library provenance changed during collection" }
            report.add("target_after", after)
        }
        verifyInputs(rows)
        check(inputIdentity(controls) == controlled && fixtureIdentity() == fixture && ArtifactIdentity.file(corpusManifest).contentEquals(manifestHash)) { "Frozen fixture or control bytes changed" }
    }

    private fun workObservation(library: ProvenanceHashingTarget, row: JsonObject): JsonObject =
        if (mode(row) in setOf(ProvenanceHashingCorpus.Mode.EntryDirectory, ProvenanceHashingCorpus.Mode.EntryArchive, ProvenanceHashingCorpus.Mode.ResourceSelection)) {
            library.probe(row)
        } else {
            JsonObject().apply {
                addProperty("scope", "Actual public operation and independent complete golden; tree counters appear in verification where available")
                add("observed_distinct_read_arrays", null)
                add("class_materializations", null)
                add("digest_encodings", null)
                addProperty("remaining_gap", "Opaque operation needs independent allocation/work observations before acceptance")
            }
        }

    private fun readManifest(path: Path): JsonObject {
        require(Files.isRegularFile(path) && Files.size(path) <= 4L * 1024 * 1024)
        val manifest = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
        require(manifest.get("contract").asString.contentEquals("strata-provenance-corpus-v1") && manifest.get("measured_rows").asInt == measured.size && manifest.get("rejected_controls").asInt == rejected.size)
        val rows = manifest.getAsJsonArray("cases").map { it.asJsonObject }
        require(rows.map(::rowIdentity) == ProvenanceHashingCorpus.cases) { "Changed or incomplete provenance corpus" }
        return manifest
    }

    private fun validateReport(report: JsonObject) {
        require(report.get("warmup").asInt == PerformancePlan().warmup)
        val phases = report.getAsJsonArray("phases").map { it.asJsonObject }
        require(phases.map(::rowIdentity) == measured && phases.all { it.get("samples").asInt == PerformancePlan().samples && it.get("warmup").asInt == PerformancePlan().warmup })
        require(report.getAsJsonArray("rejected_inputs").map { rowIdentity(it.asJsonObject.getAsJsonObject("input")) } == ProvenanceHashingCorpus.cases.filter { it.mode in rejected })
        require(report.get("improvement_claim_accepted").asBoolean.not())
        val metadata = report.getAsJsonObject("target_before")
        require(metadata == report.getAsJsonObject("target_after") && stableMetadata(metadata) == report.getAsJsonObject("measured_library"))
        LoadedArtifactMetadata.verifyComplete(metadata)
        val module = metadata.getAsJsonArray("modules").first().asJsonObject
        val target = Path.of(URI.create(module.getAsJsonObject("codeSource").get("url").asString))
        val controls = report.getAsJsonObject("controlled_inputs").entrySet().associate { (label, value) ->
            val identity = value.asJsonObject
            val path = Path.of(identity.get("path").asString)
            check(ArtifactIdentity.file(path).contentEquals(identity.get("sha256").asString))
            label to path
        }
        ProvenanceHashingTarget(target, controls.values).use { require(stableMetadata(it.certify()) == report.getAsJsonObject("measured_library")) }
        require(fixtureIdentity() == report.getAsJsonObject("fixture_identity"))
    }

    private fun verifyInputs(rows: List<JsonObject>) {
        rows.forEach { row ->
            val path = Path.of(row.get("path").asString)
            val hash = if (Files.isRegularFile(path)) ArtifactIdentity.file(path) else if (mode(row) == ProvenanceHashingCorpus.Mode.EmptyDirectory) {
                require(Files.isDirectory(path) && Files.list(path).use { it.findAny().isEmpty })
                row.get("physical_sha256").asString
            } else ArtifactIdentity.tree(path)
            check(hash.contentEquals(row.get("physical_sha256").asString)) { "Input bytes differ from frozen construction: $row" }
        }
    }

    private fun fixtureIdentity(): JsonObject {
        val type = ProvenanceHashingEvidence::class.java
        require(Files.isRegularFile(origin(type))) { "Freeze the actual fixture archive before collection" }
        val report = LoadedArtifactMetadata.capture(checkNotNull(type.classLoader), mapOf("fixture" to type.name), setOf("fixture"))
        LoadedArtifactMetadata.verifyComplete(report)
        return stableMetadata(report)
    }

    private fun stableMetadata(report: JsonObject): JsonObject = report.deepCopy().apply {
        // JVM object identity is not an archive identity; retain each full raw capture separately.
        getAsJsonArray("modules").forEach { it.asJsonObject.remove("classLoader") }
    }

    private fun inputIdentity(inputs: Map<String, Path>): JsonObject = JsonObject().apply {
        inputs.toSortedMap().forEach { (label, path) -> add(label, JsonObject().apply { addProperty("path", path.toString()); addProperty("sha256", ArtifactIdentity.file(path)) }) }
    }

    private fun environment(): JsonObject = JsonObject().apply {
        listOf("java.version", "java.vendor", "java.vm.name", "java.vm.version", "os.name", "os.version", "os.arch").forEach { addProperty(it, System.getProperty(it)) }
        addProperty("available_processors", Runtime.getRuntime().availableProcessors())
    }

    private fun mode(row: JsonObject): ProvenanceHashingCorpus.Mode = enumValues<ProvenanceHashingCorpus.Mode>().single { it.name.contentEquals(row.get("mode").asString) }

    private fun rowIdentity(row: JsonObject): ProvenanceHashingCorpus.Case = ProvenanceHashingCorpus.Case(mode(row), row.get("entries").asInt, enumValues<ProvenanceHashingCorpus.Shape>().single { it.name.contentEquals(row.get("shape").asString) })

    private fun origin(type: Class<*>): Path = Path.of(checkNotNull(type.protectionDomain.codeSource).location.toURI())
}
