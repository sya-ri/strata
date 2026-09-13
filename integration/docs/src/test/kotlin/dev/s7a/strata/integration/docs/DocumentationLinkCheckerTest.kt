package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies current paths and anchors, historical release-link portability, and fenced-example isolation without external requests.
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

    @Test
    fun checksEveryRootDocumentForMissingLinks() {
        val project = createRepository()
        listOf("AGENTS.md", "CONTRIBUTING.md", "CHANGELOG.md").forEach { name ->
            val document = project.resolve(name)
            Files.writeString(document, "[Missing](docs/missing.md)")

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    DocumentationLinkChecker.main(arrayOf(project.toString()))
                }
            assertTrue(failure.message.orEmpty().contains(document.toString()))
            Files.writeString(document, "# Restored")
        }
    }

    @Test
    fun acceptsLocalAndCurrentGithubLinksWithMarkdownAndExplicitHtmlAnchors() {
        val project = createRepository()
        Files.writeString(project.resolve("docs/README.md"), "# Documentation")
        Files.writeString(project.resolve("docs/page.html"), "<h1 id='exact'>Heading</h1><a name=\"named\"></a>")
        Files.writeString(
            project.resolve("README.md"),
            """
            # Project

            [Same page](#project)
            [Encoded fragment](docs/font-resources.md#%73ettings)
            [Current file](https://github.com/sya-ri/strata/blob/master/docs/font-resources.md#settings)
            [Current directory](https://github.com/sya-ri/strata/tree/master/docs#documentation)
            <a href='docs/page.html#exact'>HTML anchor</a>
            <a href="docs/page.html#named">Named HTML anchor</a>
            """.trimIndent(),
        )

        DocumentationLinkChecker.main(arrayOf(project.toString()))
    }

    @Test
    fun rejectsMissingLocalAndCurrentGithubFragments() {
        val project = createRepository()
        listOf("#missing", "docs/font-resources.md#missing", "https://github.com/sya-ri/strata/blob/master/docs/font-resources.md#missing").forEach { target ->
            Files.writeString(project.resolve("README.md"), "# Present\n\n[Missing]($target)")

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    DocumentationLinkChecker.main(arrayOf(project.toString()))
                }
            assertTrue(failure.message.orEmpty().contains("Documentation link anchor is missing"))
        }
    }

    @Test
    fun rejectsMissingCurrentGithubTargetsButIgnoresOtherRevisionsAndOrigins() {
        val project = createRepository()
        val readme = project.resolve("README.md")
        listOf("blob", "tree").forEach { kind ->
            Files.writeString(readme, "[Missing](https://github.com/sya-ri/strata/$kind/master/docs/old.md)")
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    DocumentationLinkChecker.main(arrayOf(project.toString()))
                }
            assertTrue(failure.message.orEmpty().contains("Documentation link target is missing"))
        }
        Files.writeString(
            readme,
            """
            [Tag](https://github.com/sya-ri/strata/blob/v0.1.0/docs/old.md#old)
            [Commit](https://github.com/sya-ri/strata/blob/0123456789abcdef0123456789abcdef01234567/docs/old.md#old)
            [Other project](https://github.com/example/strata/blob/master/docs/old.md#old)
            [Other host](https://example.invalid/docs/old.md#old)
            [Protocol-relative external](//example.invalid/docs/old.md#old)
            """.trimIndent(),
        )

        DocumentationLinkChecker.main(arrayOf(project.toString()))
    }

    @Test
    fun preservesHistoricalReleaseBodiesWithOldCurrentLinksAndAnchors() {
        val project = createRepository()
        val release = project.resolve("docs/releases/v0.1.1.md")
        val body =
            """
            [Old current link](https://github.com/sya-ri/strata/blob/master/docs/old.md#removed)
            [Historical fragment](#removed)
            """.trimIndent()
        Files.writeString(release, body)

        DocumentationLinkChecker.main(arrayOf(project.toString()))

        assertTrue(Files.readString(release) == body)
    }

    @Test
    fun rejectsEncodedCurrentGithubTraversalOutsideTheRepository() {
        val project = createRepository()
        Files.writeString(project.resolve("README.md"), "[Outside](https://github.com/sya-ri/strata/blob/master/%2e%2e/outside.md)")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                DocumentationLinkChecker.main(arrayOf(project.toString()))
            }
        assertTrue(failure.message.orEmpty().contains("escapes the repository"))
    }

    @Test
    fun rejectsInventedHtmlHeadingAnchors() {
        val project = createRepository()
        Files.writeString(project.resolve("docs/page.html"), "<h1>Settings</h1>")
        Files.writeString(project.resolve("README.md"), "[Missing](docs/page.html#settings)")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                DocumentationLinkChecker.main(arrayOf(project.toString()))
            }
        assertTrue(failure.message.orEmpty().contains("Documentation link anchor is missing"))
    }

    private fun createRepository(): Path {
        val project = Files.createDirectories(temporaryRoot.resolve("project"))
        Files.createDirectories(project.resolve("docs/releases"))
        Files.createDirectories(project.resolve("skills"))
        Files.writeString(project.resolve("README.md"), "[Guide](docs/font-resources.md#settings)")
        listOf("AGENTS.md", "CONTRIBUTING.md", "CHANGELOG.md").forEach { name -> Files.writeString(project.resolve(name), "# Project") }
        Files.writeString(project.resolve("docs/font-resources.md"), "# Settings")
        return project
    }
}
