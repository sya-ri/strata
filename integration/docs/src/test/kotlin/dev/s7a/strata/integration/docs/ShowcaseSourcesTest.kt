package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies strict source marker extraction, encoding, normalization, and containment.
 */
internal class ShowcaseSourcesTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun extractsVerbatimRegionAndNormalizesAllLineEndings() {
        val source = "package fixture\r// showcase-source-begin:row\r\nimport fixture.Api\r\ninternal fun row() {}\r// showcase-source-end:row\r"
        write("Row.kt", source.toByteArray())

        val region = ShowcaseSources.extract(SourceReference("Row.kt", "row"), temporaryRoot)

        assertEquals("import fixture.Api\ninternal fun row() {}", region.source)
        assertEquals("row", region.slug)
    }

    @Test
    fun textGuidesIncludeTheirCompleteCompiledExamples() {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val root = if (Files.isDirectory(current.resolve("api"))) current else current.resolve("../..").normalize()
        val examples =
            listOf(
                "docs/text.md" to
                    SourceReference(
                        "integration/api/src/main/kotlin/dev/s7a/strata/integration/consumer/ApiOnlyUnicodeTextScreen.kt",
                        "unicode-text",
                    ),
                "docs/text.md" to
                    SourceReference(
                        "integration/api/src/main/kotlin/dev/s7a/strata/integration/consumer/ApiOnlyMultilineTextScreen.kt",
                        "multiline-text",
                    ),
                "docs/font-resources.md" to
                    SourceReference(
                        "integration/docs/src/main/kotlin/dev/s7a/strata/integration/docs/FontResourceExample.kt",
                        "font-resources",
                    ),
            )
        examples.forEach { (path, reference) ->
            val source = ShowcaseSources.extract(reference, root)
            val guide = Files.readString(root.resolve(path)).replace("\r\n", "\n")
            assertTrue(guide.contains("```kotlin\n${source.source}\n```"), path)
        }
    }

    @Test
    fun rejectsEncodingMarkerAndRegionShapeFailures() {
        val invalid =
            listOf(
                "\uFEFF$VALID_SOURCE",
                "package fixture\ninternal fun row() {}\n",
                "package fixture\n// showcase-source-begin:row\nimport fixture.Api\ninternal fun row() {}\n// showcase-source-begin:row\n// showcase-source-end:row\n",
                "package fixture\n// showcase-source-end:row\nimport fixture.Api\ninternal fun row() {}\n// showcase-source-begin:row\n",
                "package fixture\n// showcase-source-begin:row\nimport fixture.Api\ninternal fun row() {}\n// showcase-source-end:column\n",
                "package fixture\n  // showcase-source-begin:row\nimport fixture.Api\ninternal fun row() {}\n// showcase-source-end:row\n",
                "package fixture\n// showcase-source-begin:row\n\nimport fixture.Api\ninternal fun row() {}\n// showcase-source-end:row\n",
                "package fixture\n// showcase-source-begin:row\nimport fixture.Api\ninternal fun row() {}\n\n// showcase-source-end:row\n",
                "package fixture\n// showcase-source-begin:row\nimport fixture.Api\n```\ninternal fun row() {}\n// showcase-source-end:row\n",
            )
        invalid.forEachIndexed { index, value ->
            write("Invalid$index.kt", value.toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseSources.extract(SourceReference("Invalid$index.kt", "row"), temporaryRoot)
            }
        }
        write("Bom.kt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + VALID_SOURCE.toByteArray())
        val bomFailure =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseSources.extract(SourceReference("Bom.kt", "row"), temporaryRoot)
            }
        assertTrue(bomFailure.message.orEmpty().contains("BOM"))
    }

    @Test
    fun wrapsMalformedUtf8AndRejectsPathAndSlugEscapes() {
        write("Malformed.kt", byteArrayOf(0xC3.toByte(), 0x28))
        val malformed =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseSources.extract(SourceReference("Malformed.kt", "row"), temporaryRoot)
            }
        assertTrue(malformed.message.orEmpty().contains("valid UTF-8"))
        assertTrue(malformed.cause is CharacterCodingException)

        assertThrows(IllegalArgumentException::class.java) {
            ShowcaseSources.extract(SourceReference("/absolute.kt", "row"), temporaryRoot)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShowcaseSources.extract(SourceReference("../row.kt", "row"), temporaryRoot)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShowcaseSources.extract(SourceReference("Row.kt", "Bad Slug"), temporaryRoot)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShowcaseSources.extract(SourceReference("Missing.kt", "row"), temporaryRoot)
        }
    }

    @Test
    fun rejectsSourceAndIntermediateLinks() {
        write("Row.kt", VALID_SOURCE.toByteArray())
        val linked = temporaryRoot.resolve("Linked.kt")
        val sourceLinkCreated = runCatching { Files.createSymbolicLink(linked, temporaryRoot.resolve("Row.kt")) }.isSuccess
        if (sourceLinkCreated) {
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseSources.extract(SourceReference("Linked.kt", "row"), temporaryRoot)
            }
        }

        val directory = temporaryRoot.resolve("real")
        Files.createDirectories(directory)
        write("real/Row.kt", VALID_SOURCE.toByteArray())
        val intermediate = temporaryRoot.resolve("alias")
        val intermediateLinkCreated = runCatching { Files.createSymbolicLink(intermediate, directory) }.isSuccess
        if (intermediateLinkCreated) {
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseSources.extract(SourceReference("alias/Row.kt", "row"), temporaryRoot)
            }
        }
    }

    private fun write(
        relative: String,
        bytes: ByteArray,
    ) {
        val path = temporaryRoot.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }

    private companion object {
        const val VALID_SOURCE =
            "package fixture\n// showcase-source-begin:row\nimport fixture.Api\ninternal fun row() {}\n// showcase-source-end:row\n"
    }
}
