package dev.s7a.strata.quality.benchmark

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

/**
 * Verifies fixture registration adapters against compiler-format inputs, without collecting synthetic performance results.
 */
internal class PublishedHostInventoryEvidenceTest {
    @field:TempDir internal lateinit var directory: Path

    @Test
    internal fun physicalHostsSelectTheirOwnCompilerFormatsAndKeepOverloads() {
        registrations(listOf(":fixture\tJvm\tfixture.JvmEntry\tInitial", ":fixture\tWeb\tfixture.WebEntry\tInitial"))
        abi("fixture.api", "public final class fixture/First {\npublic final fun render (I)V\npublic final fun render (J)V\n}\n")
        abi("fixture.klib.api", "// Klib ABI Dump\nfinal fun fixture/render(kotlin/Int) // fixture/render|render(kotlin.Int){}[0]\n")
        val output = directory.resolve("prospective.tsv")
        PublishedHostInventoryEvidence.capture(directory, output)
        val rows = Files.readAllLines(output)
        assertEquals(
            listOf(
                ":fixture@Jvm\t:fixture@Jvm\tjvm:fixture/First#public final class fixture/First {",
                ":fixture@Jvm\t:fixture@Jvm\tjvm:fixture/First#public final fun render (I)V",
                ":fixture@Jvm\t:fixture@Jvm\tjvm:fixture/First#public final fun render (J)V",
                ":fixture@Web\t:fixture@Web\tklib:fixture/render|render(kotlin.Int){}[0]#final fun fixture/render(kotlin/Int)",
            ),
            rows,
        )
        assertFails { PublishedHostInventoryEvidence.capture(directory, output) }
        assertEquals(rows, Files.readAllLines(output))
    }

    @Test
    internal fun absentWebAbiCannotBeFilledWithJvmDeclarations() {
        registrations(listOf(":fixture\tWeb\tfixture.WebEntry\tInitial"))
        abi("fixture.api", "public final class fixture/First {\n}\n")
        val output = directory.resolve("prospective.tsv")
        assertFails { PublishedHostInventoryEvidence.capture(directory, output) }
        assertFalse(Files.exists(output))
    }

    @Test
    internal fun traversalAndUnknownHostsFailBeforeProducingAnInventory() {
        registrations(listOf(":..\tJvm\tfixture.Entry\tInitial"))
        assertFails { PublishedHostInventoryEvidence.capture(directory, directory.resolve("traversal.tsv")) }
        assertFalse(Files.exists(directory.resolve("traversal.tsv")))
        registrations(listOf(":fixture\tUnknown\tfixture.Entry\tInitial"))
        assertFails { PublishedHostInventoryEvidence.capture(directory, directory.resolve("unknown.tsv")) }
        assertFalse(Files.exists(directory.resolve("unknown.tsv")))
    }

    private fun registrations(rows: List<String>) {
        Files.createDirectories(directory.resolve("gradle"))
        Files.writeString(directory.resolve("gradle/performance-modules.tsv"), rows.joinToString("\n", postfix = "\n"))
    }

    private fun abi(
        name: String,
        content: String,
    ) {
        val folder = directory.resolve("fixture/api")
        Files.createDirectories(folder)
        Files.writeString(folder.resolve(name), content)
    }
}
