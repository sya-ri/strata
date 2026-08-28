package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies release-link portability without changing ordinary repository-link checks or requesting external URLs.
 */
internal class DocumentationLinkCheckerTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun releaseMarkdownRejectsRelativeLinksEvenWhenTheirTargetsExist() {
        val project = createRepository()
        val release = project.resolve("docs/releases/v0.1.1.md")
        Files.writeString(project.resolve("docs/preview.png"), "image")
        val relativeLinks =
            listOf(
                "[Guide](../font-resources.md#settings)",
                "![Preview](../preview.png)",
                "<a href=\"../font-resources.md\">Guide</a>",
            )

        relativeLinks.forEach { link ->
            Files.writeString(release, link)

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    DocumentationLinkChecker.main(arrayOf(project.toString()))
                }
            assertTrue(failure.message.orEmpty().contains("Release notes require absolute HTTP(S) URLs"))
            assertTrue(failure.message.orEmpty().contains(release.toString()))
        }
    }

    @Test
    fun releaseLinksRequireAWebSchemeAndHost() {
        val project = createRepository()
        val release = project.resolve("docs/releases/v0.1.1.md")
        val nonPortableTargets =
            listOf(
                "/strata/guide/font-resources.md",
                "//example.invalid/font-resources.md",
                "file:///font-resources.md",
                "mailto:author@example.invalid",
                "https:font-resources.md",
                "https:///font-resources.md",
            )

        nonPortableTargets.forEach { target ->
            Files.writeString(release, "[Guide]($target)")

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    DocumentationLinkChecker.main(arrayOf(project.toString()))
                }
            assertTrue(failure.message.orEmpty().contains("Release notes require absolute HTTP(S) URLs"))
        }
    }

    @Test
    fun releaseNotesAcceptAbsoluteWebLinksAndSameDocumentFragments() {
        val project = createRepository()
        Files.writeString(
            project.resolve("docs/releases/v0.1.1.md"),
            """
            # Details

            [Guide](https://example.invalid/guide/font-resources.md#settings)
            [HTTP guide](http://example.invalid/guide/font-resources.md)
            [Uppercase scheme](HTTPS://example.invalid/guide/font-resources.md)
            [Titled guide](https://example.invalid/guide/font-resources.md "Font resources")
            [Details](#details)
            <a href="#details">Details</a>
            """.trimIndent(),
        )

        DocumentationLinkChecker.main(arrayOf(project.toString()))
    }

    @Test
    fun ordinaryDocumentsKeepRelativeLinksAndReleaseCodeFencesAreIgnored() {
        val project = createRepository()
        Files.writeString(project.resolve("skills/setup.md"), "[Guide](../docs/font-resources.md#settings)")
        Files.writeString(project.resolve("docs/releases/index.html"), "<a href=\"../font-resources.md\">Guide</a>")
        Files.createDirectories(project.resolve("docs/releases-extra"))
        Files.writeString(project.resolve("docs/releases-extra/guide.md"), "[Guide](../font-resources.md)")
        Files.writeString(
            project.resolve("docs/releases/v0.1.1.md"),
            """
            ```markdown
            [Example](../missing-example.md)
            ```
            """.trimIndent(),
        )

        DocumentationLinkChecker.main(arrayOf(project.toString()))
    }

    @Test
    fun missingOrdinaryTargetsStillFail() {
        val project = createRepository()
        Files.writeString(project.resolve("README.md"), "[Missing](docs/missing.md)")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                DocumentationLinkChecker.main(arrayOf(project.toString()))
            }
        assertTrue(failure.message.orEmpty().contains("Documentation link target is missing"))
    }

    private fun createRepository(): Path {
        val project = Files.createDirectories(temporaryRoot.resolve("project"))
        Files.createDirectories(project.resolve("docs/releases"))
        Files.createDirectories(project.resolve("skills"))
        Files.writeString(project.resolve("README.md"), "[Guide](docs/font-resources.md#settings)")
        Files.writeString(project.resolve("docs/font-resources.md"), "# Settings")
        return project
    }
}
