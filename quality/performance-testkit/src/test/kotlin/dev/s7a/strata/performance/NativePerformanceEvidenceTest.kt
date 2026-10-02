package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Preserves class-archive and CPU/native provenance rejection contracts with detached fixture resources.
 */
class NativePerformanceEvidenceTest {
    @field:TempDir lateinit var directory: Path

    @Test
    fun unsafeDuplicateEmptyAndOversizedClassInventoriesFail() {
        val path = directory.resolve("runtime.jar")
        write(path, listOf("fixture/Probe.class", "fixture/Other.class"))
        assertEquals(2, JvmEvidenceFiles.classTree(path).countField("entryCount"))
        listOf("../Probe.class", "/Probe.class", "fixture//Probe.class", "fixture\\Probe.class", "fixture/../Probe.class").forEach { name ->
            write(path, listOf(name))
            assertFails { JvmEvidenceFiles.classTree(path) }
        }
        write(path, emptyList())
        assertFails { JvmEvidenceFiles.classTree(path) }
        write(path, listOf("fixture/Probe.class", "fixture/Other.class"))
        val bytes =
            Files
                .readAllBytes(path)
                .toString(Charsets.ISO_8859_1)
                .replace("fixture/Other.class", "fixture/Probe.class")
                .toByteArray(Charsets.ISO_8859_1)
        Files.write(path, bytes)
        assertFails { JvmEvidenceFiles.classTree(path) }
        ZipOutputStream(Files.newOutputStream(path)).use { archive ->
            archive.putNextEntry(ZipEntry("fixture/Probe.class"))
            archive.write(ByteArray(8 * 1024 * 1024 + 1))
            archive.closeEntry()
        }
        assertFails { JvmEvidenceFiles.classTree(path) }
    }

    @Test
    fun realOriginsAndTreesMustMatchWithoutFilenameVersionRules() {
        val path = directory.resolve("arbitrary-0.2.1-name.jar")
        write(path, listOf("fixture/Probe.class"))
        val origin =
            JsonObject().apply {
                addProperty("url", path.toUri().toString())
                addProperty("sha256", ArtifactIdentity.file(path))
            }
        val resource =
            JsonObject().apply {
                addProperty("entryName", "fixture/Probe.class")
                addProperty("sha256", EntryIdentity.sha256("probe".byteInputStream()))
            }
        val module =
            JsonObject().apply {
                addProperty("module", "runtime")
                addProperty("representativeClass", "fixture.Probe")
                addProperty("status", "resolved")
                add("codeSource", origin)
                add("classResource", resource)
                add("classTree", JvmEvidenceFiles.classTree(path))
            }
        val native = JsonObject().apply { add("strata", JsonObject().apply { add("modules", JsonArray().apply { add(module) }) }) }
        val cpu =
            JsonObject().apply {
                add(
                    "strata_class_sha256",
                    JsonObject().apply {
                        add(
                            "fixture.Probe",
                            JsonObject().apply {
                                add("code_source", origin.deepCopy())
                                add("class_resource", resource.deepCopy())
                            },
                        )
                    },
                )
            }
        assertEquals(1, NativePerformanceEvidence.verify(native, cpu, setOf("fixture.Probe"), emptySet()).size())
        module.objectField("classTree").addProperty("sha256", "0".repeat(64))
        assertFails { NativePerformanceEvidence.verify(native, cpu, setOf("fixture.Probe"), emptySet()) }
        module.add("classTree", JvmEvidenceFiles.classTree(path))
        origin.addProperty("url", "https://example.invalid/runtime.jar")
        assertFails { NativePerformanceEvidence.verify(native, cpu, setOf("fixture.Probe"), emptySet()) }
        origin.addProperty("url", path.toUri().toString() + "?query=1")
        assertFails { NativePerformanceEvidence.verify(native, cpu, setOf("fixture.Probe"), emptySet()) }
        origin.addProperty("url", path.toUri().toString())
        native.objectField("strata").arrayField("modules").add(module.deepCopy())
        assertFails { NativePerformanceEvidence.verify(native, cpu, setOf("fixture.Probe"), emptySet()) }
    }

    private fun write(
        path: Path,
        names: List<String>,
    ) {
        ZipOutputStream(Files.newOutputStream(path)).use { archive ->
            names.forEach { name ->
                archive.putNextEntry(ZipEntry(name))
                archive.write("probe".toByteArray())
                archive.closeEntry()
            }
        }
    }
}
