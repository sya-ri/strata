package dev.s7a.strata.performance

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

/**
 * Exercises the packaged CLI against complete original JMH archives and live role-specific inputs.
 * Synthetic counters test validation and aggregation without claiming measured performance.
 */
class JvmOperationComparisonTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun identicalInputsMayUseDifferentRoleClasspathsWithoutDiscardingTheirPaths() {
        PackagedEvidenceFixture().use { kit ->
            val baseline = reports(kit, directory.resolve("baseline"))
            val candidate = reports(kit, directory.resolve("candidate"))
            val result = compare(kit, baseline, candidate)
            val before = result.objectField("before").objectField("conditions").objectField("input_sources")
            val after = result.objectField("after").objectField("conditions").objectField("input_sources")
            assertNotEquals(before, after)
            assertEquals(before.objectField("files").objectField("font").textField("sha256"), after.objectField("files").objectField("font").textField("sha256"))
            assertEquals(
                200.0,
                result
                    .arrayField("phases")
                    .single()
                    .asJsonObject
                    .objectField("before")
                    .get("wall_ns_per_sample")
                    .asDouble,
            )
        }
    }

    @Test
    fun differentLogicalInputBytesCannotCompareEvenWhenBothRolesAreIndividuallyValid() {
        PackagedEvidenceFixture().use { kit ->
            val baseline = reports(kit, directory.resolve("baseline"))
            val candidate = reports(kit, directory.resolve("candidate"), input = "different font")
            assertFails { compare(kit, baseline, candidate) }
        }
    }

    @Test
    fun changedAndMissingLiveBytesStillFailDespitePreservedOriginalCopies() {
        PackagedEvidenceFixture().use { kit ->
            val baseline = reports(kit, directory.resolve("baseline"))
            val candidate = reports(kit, directory.resolve("candidate"))
            val live = directory.resolve("candidate/live.bin")
            Files.writeString(live, "changed")
            assertFails { compare(kit, baseline, candidate) }
            Files.delete(live)
            assertFails { compare(kit, baseline, candidate) }
        }
    }

    @Test
    fun oneRepetitionCannotRedirectItsMappingToAnotherFileWithIdenticalBytes() {
        PackagedEvidenceFixture().use { kit ->
            val baseline = reports(kit, directory.resolve("baseline"))
            val candidate = reports(kit, directory.resolve("candidate"))
            val replacement = Files.copy(directory.resolve("candidate/live.bin"), directory.resolve("candidate/replacement.bin"))
            val report = JvmEvidenceFiles.document(candidate.last())
            report
                .objectField("input_sources")
                .objectField("files")
                .objectField("font")
                .addProperty("path", replacement.toString())
            Files.writeString(candidate.last(), report.toString())
            assertFails { compare(kit, baseline, candidate) }
        }
    }

    @Test
    fun changedConsumedManifestStillFailsWhenItsReplacementHasIdenticalBytes() {
        PackagedEvidenceFixture().use { kit ->
            val baseline = reports(kit, directory.resolve("baseline"))
            val candidate = reports(kit, directory.resolve("candidate"))
            val replacement = Files.copy(directory.resolve("candidate/live.bin"), directory.resolve("candidate/replacement.bin"))
            writeManifest(directory.resolve("candidate/inputs.properties"), replacement)
            assertFails { compare(kit, baseline, candidate) }
        }
    }

    /**
     * Creates three independent supplemental records, each bound to its own complete preserved JMH invocation.
     */
    private fun reports(
        kit: PackagedEvidenceFixture,
        root: Path,
        input: String = "font data",
    ): List<Path> {
        val fixture = JmhEvidenceFixtureData(root.resolve("jmh"), kit.archive)
        val originals = fixture.create()
        val live = Files.writeString(root.resolve("live.bin"), input).toAbsolutePath().normalize()
        val manifest = root.resolve("inputs.properties").toAbsolutePath().normalize()
        writeManifest(manifest, live)
        return originals.mapIndexed { index, original ->
            Files.writeString(original.resolve("input-0.bin"), input)
            fixture.mutateReceipt(original) { it.objectField("inputs").objectField("font").addProperty("sha256", ArtifactIdentity.file(live)) }
            val receipt = JvmEvidenceFiles.document(original.resolve("receipt.json"))
            val inputs = JmhArchiveEvidence.inputs(original, receipt)
            val sources =
                JsonObject().apply {
                    add("files", JvmOperationInputs.bind(mapOf("font" to live), inputs))
                    add(
                        "manifests",
                        JsonObject().apply {
                            add(
                                "inputs",
                                JsonObject().apply {
                                    addProperty("path", manifest.toString())
                                    add("files", JsonObject().apply { addProperty("font", live.toString()) })
                                },
                            )
                        },
                    )
                }
            val report =
                JsonObject().apply {
                    addProperty("schema_version", 1)
                    addProperty("status", "passed")
                    addProperty("workload_id", "jvm-operation-reports")
                    addProperty("protocol", "strata-owner-thread-operation-v1")
                    addProperty("run_id", UUID.randomUUID().toString())
                    addProperty("repetition", index)
                    addProperty("selection", "fixture.Frame.idle")
                    add(
                        "plan",
                        JsonObject().apply {
                            addProperty("warmup", 0)
                            addProperty("samples", 2)
                        },
                    )
                    add("rows", JsonArray().apply { add("operation") })
                    add("fixture_identity", receipt.get("fixture_identity").deepCopy())
                    add("adapter_identity", JsonObject().apply { addProperty("fixture.Adapter", "1".repeat(64)) })
                    add("inputs", inputs)
                    add("input_sources", sources)
                    add("environment", receipt.get("environment").deepCopy())
                    add("source_bindings", JsonObject().apply { addProperty("fixture", "frozen") })
                    add("runtime_identities", JmhArchiveEvidence.targets(original, receipt))
                    add("collector_identity", receipt.get("collector_identity").deepCopy())
                    addProperty("jmh_directory", original.toString())
                    addProperty("jmh_receipt_sha256", ArtifactIdentity.file(original.resolve("receipt.json")))
                    add("jmh_run_id", receipt.get("run_id").deepCopy())
                    add("phases", JsonArray().apply { add(phase()) })
                }
            Files.writeString(root.resolve("operation-$index.json"), report.toString())
        }
    }

    /**
     * Supplies complete literal distributions for one indivisible operation, outside any timing run.
     */
    private fun phase(): JsonObject =
        JsonObject().apply {
            addProperty("operation_id", "operation")
            addProperty("benchmark", "fixture.Frame.idle")
            add("parameters", JsonObject().apply { addProperty("component", "Row") })
            addProperty("boundary", "Complete operation")
            addProperty("operations_per_sample", 1)
            addProperty("control", false)
            addProperty("samples", 2)
            addProperty("wall_total_ns", 400)
            addProperty("owner_thread_cpu_ns", 100)
            addProperty("owner_thread_allocated_bytes", 64)
            add("wall_distribution", distribution(100, 300))
            add("owner_thread_cpu_distribution", distribution(50, 50))
            add("owner_thread_allocation_distribution", distribution(32, 32))
        }

    /**
     * Creates exact two-sample distribution data consumed by all registered metric paths.
     */
    private fun distribution(
        first: Int,
        second: Int,
    ): JsonObject =
        JsonObject().apply {
            add("raw", Gson().toJsonTree(listOf(first, second)))
            addProperty("samples", 2)
            addProperty("p50_ns", first)
            addProperty("p95_ns", second)
            addProperty("p99_ns", second)
            addProperty("max_ns", second)
        }

    /**
     * Records the actual live-file mapping whose redirection must remain independently detectable.
     */
    private fun writeManifest(
        path: Path,
        input: Path,
    ) {
        Files.newBufferedWriter(path).use { writer -> Properties().apply { setProperty("font", input.toString()) }.store(writer, null) }
    }

    /**
     * Invokes the actual packaged operation-comparison CLI rather than bypassing its provenance callbacks.
     */
    private fun compare(
        kit: PackagedEvidenceFixture,
        baseline: List<Path>,
        candidate: List<Path>,
    ): JsonObject {
        val token = UUID.randomUUID().toString()
        val output = directory.resolve("comparison-$token.json")
        val request =
            JsonObject().apply {
                addProperty("command", PerformanceEvidenceCommand.OperationComparison.label)
                addProperty("collector", kit.archive.toString())
                addProperty("output", output.toString())
                add("baseline", Gson().toJsonTree(baseline.map(Path::toString)))
                add("candidate", Gson().toJsonTree(candidate.map(Path::toString)))
            }
        val path = Files.writeString(directory.resolve("request-$token.json"), request.toString())
        kit.type("PerformanceEvidenceCli").getMethod("main", Array<String>::class.java).invoke(null, arrayOf(path.toString()))
        return JvmEvidenceFiles.document(output)
    }
}
