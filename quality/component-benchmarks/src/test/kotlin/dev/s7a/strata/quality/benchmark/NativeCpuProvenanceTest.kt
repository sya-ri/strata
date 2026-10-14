package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.NativePerformanceEvidence
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Exercises CPU receipt transport against real temporary archive bytes without collecting timings.
 */
internal class NativeCpuProvenanceTest {
    /**
     * Isolated archive directory owned and released by each test invocation.
     */
    @field:TempDir
    internal lateinit var directory: Path

    @Test
    internal fun legacyReportsAndCurrentReceiptsPreserveActualMetadata() {
        listOf("strata", "runtime_metadata").forEach { field ->
            val metadata = metadata(directory.resolve("$field.jar"))
            val cpu = report(field, metadata)
            val original = cpu.deepCopy()
            assertEquals(metadata, NativeComponentPerformanceEvidence.cpuRuntimeMetadata(cpu))
            assertEquals(1, verify(metadata, cpu))
            assertEquals(original, cpu)
        }
    }

    @Test
    internal fun missingAmbiguousMalformedAndFailedCpuReceiptsAreRejected() {
        val metadata = metadata(directory.resolve("malformed.jar"))
        val valid = report("runtime_metadata", metadata)
        assertFails { NativeComponentPerformanceEvidence.cpuRuntimeMetadata(JsonObject()) }
        listOf(JsonNull.INSTANCE, JsonObject(), JsonArray()).forEach { malformed ->
            val cpu = valid.deepCopy().apply { add("runtime_metadata", malformed) }
            assertFails { NativeComponentPerformanceEvidence.cpuRuntimeMetadata(cpu) }
        }
        val ambiguous = valid.deepCopy().apply { add("strata", metadata.deepCopy()) }
        assertFails { NativeComponentPerformanceEvidence.cpuRuntimeMetadata(ambiguous) }
        listOf("status", "contract", "fork_verification").forEach { field ->
            val cpu = valid.deepCopy().apply { remove(field) }
            assertFails { NativeComponentPerformanceEvidence.cpuRuntimeMetadata(cpu) }
        }
        val unresolved = valid.deepCopy()
        unresolved
            .getAsJsonObject("runtime_metadata")
            .getAsJsonArray("modules")[0]
            .asJsonObject
            .addProperty("status", "unresolved")
        assertFails { NativeComponentPerformanceEvidence.cpuRuntimeMetadata(unresolved) }
    }

    @Test
    internal fun changedCpuHashesAndArchiveBytesCannotPassNativeAdmission() {
        val path = directory.resolve("strict-origin.jar")
        val metadata = metadata(path)
        listOf("strata", "runtime_metadata").forEach { field ->
            val valid = report(field, metadata)
            assertEquals(1, verify(metadata, valid))
            listOf("codeSource", "classResource", "classTree").forEach { location ->
                val changed = valid.deepCopy()
                changed
                    .getAsJsonObject(field)
                    .getAsJsonArray("modules")[0]
                    .asJsonObject
                    .getAsJsonObject(location)
                    .addProperty("sha256", "0".repeat(64))
                assertFails { verify(metadata, changed) }
            }
        }
        Files.write(path, Files.readAllBytes(path) + byteArrayOf(1))
        listOf("strata", "runtime_metadata").forEach { field -> assertFails { verify(metadata, report(field, metadata)) } }
    }

    @Test
    internal fun comparisonInvocationsKeepTheirOwnCpuOriginsAndRejectCopiedIds() {
        val baselineJar = directory.resolve("baseline.jar")
        val candidateJar = directory.resolve("candidate.jar")
        val baselineCpu = directory.resolve("baseline-cpu.json")
        val candidateCpu = directory.resolve("candidate-cpu.json")
        val representative = "dev.s7a.strata.render.DrawImage"
        Files.writeString(baselineCpu, report("runtime_metadata", metadata(baselineJar, representative)).toString())
        Files.writeString(candidateCpu, report("runtime_metadata", metadata(candidateJar, representative)).toString())
        val ids = List(6) { UUID.randomUUID() }
        val paths =
            ids.mapIndexed { index, id ->
                directory.resolve("native-$index.json").also { path ->
                    Files.writeString(path, JsonObject().apply { addProperty("run_id", id.toString()) }.toString())
                }
            }
        val inputs = listOf(paths.take(3) to baselineCpu, paths.drop(3) to candidateCpu)
        val bindings = NativeComponentPerformanceEvidence.cpuProvenanceByRun(inputs)
        ids.forEachIndexed { index, id ->
            val jar = if (index < 3) baselineJar else candidateJar
            val actual =
                bindings
                    .getValue(id)
                    .getAsJsonObject("strata")
                    .getAsJsonArray("modules")
                    .single()
                    .asJsonObject
            assertEquals(ArtifactIdentity.file(jar), actual.getAsJsonObject("codeSource").get("sha256").asString)
        }
        Files.writeString(paths[3], Files.readString(paths[0]))
        assertFails { NativeComponentPerformanceEvidence.cpuProvenanceByRun(inputs) }
        Files.writeString(paths[3], JsonObject().apply { addProperty("run_id", ids[3].toString()) }.toString())
        val failed = report("runtime_metadata", metadata(candidateJar, representative)).apply { addProperty("status", "failed") }
        Files.writeString(candidateCpu, failed.toString())
        assertFails { NativeComponentPerformanceEvidence.cpuProvenanceByRun(inputs) }
    }

    private fun verify(
        metadata: JsonObject,
        cpu: JsonObject,
    ): Int =
        NativePerformanceEvidence
            .verify(
                JsonObject().apply { add("strata", metadata.deepCopy()) },
                JsonObject().apply { add("strata", NativeComponentPerformanceEvidence.cpuRuntimeMetadata(cpu)) },
                setOf("fixture.Probe"),
                emptySet(),
            ).size()

    private fun report(
        field: String,
        metadata: JsonObject,
    ): JsonObject =
        JsonObject().apply {
            addProperty("status", "passed")
            addProperty("contract", "strata-jmh-v1")
            addProperty("fork_verification", "loaded-artifacts-per-iteration-v1")
            add(field, metadata.deepCopy())
        }

    private fun metadata(
        path: Path,
        representative: String = "fixture.Probe",
    ): JsonObject {
        val entry = representative.replace('.', '/') + ".class"
        val bytes = "detached archive probe ${path.fileName}".toByteArray(Charsets.UTF_8)
        ZipOutputStream(Files.newOutputStream(path)).use { archive ->
            archive.putNextEntry(ZipEntry(entry))
            archive.write(bytes)
            archive.closeEntry()
        }
        val resourceHash = hash(bytes)
        val module =
            JsonObject().apply {
                addProperty("module", "runtime")
                addProperty("representativeClass", representative)
                addProperty("status", "resolved")
                add(
                    "codeSource",
                    JsonObject().apply {
                        addProperty("status", "available")
                        addProperty("url", path.toUri().toString())
                        addProperty("sha256", ArtifactIdentity.file(path))
                    },
                )
                add(
                    "classResource",
                    JsonObject().apply {
                        addProperty("status", "available")
                        addProperty("entryName", entry)
                        addProperty("sha256", resourceHash)
                    },
                )
                add(
                    "classTree",
                    JsonObject().apply {
                        addProperty("status", "available")
                        addProperty("algorithm", "sha256(path=sha256(resource-bytes)\\n)-utf8-v1")
                        addProperty("entryCount", 1)
                        addProperty("bytes", bytes.size)
                        addProperty("sha256", hash("$entry=$resourceHash\n".toByteArray(Charsets.UTF_8)))
                    },
                )
            }
        return JsonObject().apply { add("modules", JsonArray().apply { add(module) }) }
    }

    private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
