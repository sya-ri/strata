package dev.s7a.strata.performance

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.openjdk.jmh.runner.Runner
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Synthetic JMH output with real preserved collector/harness/target archives for rejection tests.
 * These fixtures exercise evidence validation, not the performance of their invented scores.
 */
internal class JmhEvidenceFixtureData(
    private val root: Path,
    private val collector: Path,
) {
    /**
     * Creates three independent receipts with a complete one-case raw JMH matrix.
     */
    fun create(sample: Boolean = false): List<Path> {
        val harnessType = Runner::class.java
        val harness =
            Path.of(
                harnessType.protectionDomain.codeSource.location
                    .toURI(),
            )
        val representative = JvmPerformanceMeter::class.java.name
        val entry = "${representative.replace('.', '/')}.class"
        val resource = ZipFile(collector.toFile()).use { archive -> archive.getInputStream(archive.getEntry(entry)).use(EntryIdentity::sha256) }
        val tree = JvmEvidenceFiles.classTree(collector)
        return (0..2).map { index ->
            val directory = Files.createDirectories(root.resolve("run-$index"))
            Files.copy(collector, directory.resolve("collector.jar"))
            Files.copy(collector, directory.resolve("target-0.jar"))
            Files.copy(harness, directory.resolve("harness.jar"))
            Files.writeString(directory.resolve("input-0.bin"), "font data")
            val mode = if (sample) JmhEvidenceMode.SampleTime else JmhEvidenceMode.AverageTime
            val rows = JsonArray().apply { add(row(index, mode)) }
            Files.writeString(directory.resolve("results.json"), rows.toString())
            val receipt = header(index, directory, mode, harness)
            receipt.add(
                "runtime_metadata",
                JsonObject().apply {
                    add(
                        "modules",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("module", "fixture")
                                    addProperty("representativeClass", representative)
                                    addProperty("status", "resolved")
                                    add("classTree", tree.deepCopy())
                                    add(
                                        "codeSource",
                                        JsonObject().apply {
                                            addProperty("status", "available")
                                            addProperty("sha256", ArtifactIdentity.file(collector))
                                        },
                                    )
                                    add(
                                        "classResource",
                                        JsonObject().apply {
                                            addProperty("status", "available")
                                            addProperty("sha256", resource)
                                            addProperty("entryName", entry)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            Files.writeString(directory.resolve("receipt.json"), receipt.toString())
            directory
        }
    }

    /**
     * Changes a raw result and updates its digest so semantic validation, rather than hashing alone, must reject it.
     */
    fun mutateRaw(
        directory: Path,
        mutation: (JsonObject) -> Unit,
    ) {
        val path = directory.resolve("results.json")
        val raw = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonArray }
        mutation(raw.single().asJsonObject)
        Files.writeString(path, raw.toString())
        mutateReceipt(directory) { it.addProperty("results_sha256", ArtifactIdentity.file(path)) }
    }

    /**
     * Mutates external receipt fields without changing the original archive files implicitly.
     */
    fun mutateReceipt(
        directory: Path,
        mutation: (JsonObject) -> Unit,
    ) {
        val path = directory.resolve("receipt.json")
        val receipt = JvmEvidenceFiles.document(path)
        mutation(receipt)
        Files.writeString(path, receipt.toString())
    }

    private fun row(
        repetition: Int,
        mode: JmhEvidenceMode,
    ): JsonObject {
        val row =
            JsonParser
                .parseString(
                    """{"jmhVersion":"1.37","benchmark":"fixture.Frame.idle","mode":"avgt","params":{"component":"Row"},"threads":1,"forks":1,"jvm":"java","jvmArgs":[],"jdkVersion":"25","vmName":"OpenJDK","vmVersion":"25","warmupIterations":3,"warmupTime":"1 s","warmupBatchSize":1,"measurementIterations":1,"measurementTime":"1 s","measurementBatchSize":1,"primaryMetric":{"score":1,"scoreUnit":"us/op","rawData":[[1]]},"secondaryMetrics":{"strata.provenance":{"score":1,"scoreUnit":"verified","rawData":[[1]]},"gc.alloc.rate.norm":{"score":16,"scoreUnit":"B/op"},"gc.count":{"score":0,"scoreUnit":"counts"}}}""",
                ).asJsonObject
        row.addProperty("mode", mode.label)
        val metric = row.objectField("primaryMetric")
        metric.addProperty("score", repetition + 1)
        if (mode == JmhEvidenceMode.SampleTime) {
            metric.remove("rawData")
            metric.add("rawDataHistogram", JsonParser.parseString("[[[[$repetition,5]]]]"))
            metric.add("scorePercentiles", JsonParser.parseString("""{"50.0":1,"95.0":2,"99.0":3}"""))
        } else {
            metric.add("rawData", JsonParser.parseString("[[${repetition + 1}]]"))
        }
        return row
    }

    private fun header(
        repetition: Int,
        directory: Path,
        mode: JmhEvidenceMode,
        harness: Path,
    ): JsonObject =
        JsonObject().apply {
            addProperty("contract", "strata-jmh-v1")
            addProperty("status", "passed")
            addProperty("run_id", UUID.randomUUID().toString())
            addProperty("repetition", repetition)
            addProperty("fork_verification", "loaded-artifacts-per-iteration-v1")
            addProperty("results_sha256", ArtifactIdentity.file(directory.resolve("results.json")))
            addProperty("harness_sha256", ArtifactIdentity.file(harness))
            add("arguments", Gson().toJsonTree(listOf("fixture.Frame.*", "-bm", mode.label)))
            add("fixture_identity", JsonObject().apply { addProperty("fixture.Frame", "1".repeat(64)) })
            add("environment", JsonObject().apply { addProperty("java", "25") })
            add(
                "collector_identity",
                JsonObject().apply {
                    addProperty("contract", "strata-performance-testkit-v1")
                    addProperty("code_source_sha256", ArtifactIdentity.file(collector))
                },
            )
            add("registered_workloads", Gson().toJsonTree(listOf(Gson().toJson(listOf("fixture.Frame.idle", mode.label, mapOf("component" to "Row"))))))
            add("target_archives", JsonObject().apply { addProperty("fixture", "target-0.jar") })
            add(
                "inputs",
                JsonObject().apply {
                    add(
                        "font",
                        JsonObject().apply {
                            addProperty("archive", "input-0.bin")
                            addProperty("sha256", ArtifactIdentity.file(directory.resolve("input-0.bin")))
                        },
                    )
                },
            )
        }
}
