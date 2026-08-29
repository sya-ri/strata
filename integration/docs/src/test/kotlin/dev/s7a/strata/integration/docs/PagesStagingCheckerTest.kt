package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies Dokka entrypoints, HTML links, source receipts, and legacy-snapshot isolation in temporary staged sites.
 */
internal class PagesStagingCheckerTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun acceptsDokkaWithoutGuideAndDiscoversNestedModuleEntrypoints() {
        val (project, site) = createBaseTrees("dokka")
        Files.writeString(
            project.resolve("README.md"),
            "$PAGES_BASE ${PAGES_BASE}api/index.html#api",
        )
        writeStagedFile(
            site,
            "index.html",
            """
            <h1 id="main">Strata API</h1>
            <a href="api/index.html#api">API</a>
            <a href="runtime\core/index.html">Core</a>
            <a href="https://github.com/sya-ri/strata/blob/master/docs/components.md">Components</a>
            """.trimIndent(),
        )
        writeStagedFile(
            site,
            "api/index.html",
            """
            <h1 id="api">API</h1><a name="entry"></a><a href="#entry">Entry</a>
            <a href="../index.html#main">Home</a>
            <img src="../images/logo.svg">
            <script src="../scripts/main.js"></script>
            <link href="../styles/main.css?theme=light&amp;version=1" rel="stylesheet">
            """.trimIndent(),
        )
        writeStagedFile(site, "runtime/core/index.html", "<h1>Core</h1><a href='../../index.html#main'>Home</a>")
        writeStagedFile(site, "images/logo.svg", "<svg></svg>")
        writeStagedFile(site, "scripts/main.js", "window.loaded = true;")
        writeStagedFile(site, "styles/main.css", "body { color: black; }")
        val inventory = writeInventory(project, site)

        PagesStagingChecker.check(project, site, inventory)
        assertEquals(
            listOf("/", "/api/index.html", "/index.html", "/runtime/core/index.html", "/source-receipt.json", "/source-revision.txt"),
            Files.readAllLines(inventory),
        )
    }

    @Test
    fun rejectsSourceReceiptWithAnotherRevision() {
        val (project, site) = createBaseTrees("receipt-revision")
        writeSourceEvidence(site, receiptRevision = "v0.1.1")
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("differs from source-revision.txt"))
    }

    @Test
    fun rejectsSourceReceiptWithAbbreviatedCommit() {
        val (project, site) = createBaseTrees("receipt-commit")
        Files.writeString(
            site.resolve("source-receipt.json"),
            "{\"commit\":\"${SOURCE_COMMIT.take(12)}\",\"revision\":\"v0.1.0\"}\n",
        )
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("source receipt is not canonical"))
    }

    @Test
    fun rejectsMissingSourceReceipt() {
        val (project, site) = createBaseTrees("missing-receipt")
        Files.delete(site.resolve("source-receipt.json"))
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("/source-receipt.json"))
    }

    @Test
    fun rejectsDirectoryTargetWithoutItsIndex() {
        val (project, site) = createBaseTrees("missing-index")
        Files.writeString(project.resolve("README.md"), "${PAGES_BASE}api/")
        Files.createDirectories(site.resolve("api"))
        val inventory = writeInventory(project, site)

        assertThrows(IllegalArgumentException::class.java) {
            PagesStagingChecker.check(project, site, inventory)
        }
    }

    @Test
    fun rejectsSourceLinksAdvertisingMarkdownOnPages() {
        val (project, site) = createBaseTrees("markdown-source")
        writeStagedFile(site, "text.md", "# Present")

        listOf("text.md", "releases/../text.md", "releases/%2e%2e/text.md").forEach { target ->
            Files.writeString(project.resolve("README.md"), "$PAGES_BASE$target#present")
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    writeInventory(project, site)
                }
            assertTrue(failure.message.orEmpty().contains("Reader Markdown must link to GitHub"))
        }
    }

    @Test
    fun rejectsHtmlLinksToMarkdownEvenWhenTheFileExists() {
        val (project, site) = createBaseTrees("markdown-html")
        writeStagedFile(site, "index.html", "<a href='api/index.html'>API</a>")
        writeStagedFile(site, "api/index.html", "<a href='../text.md#present'>Text</a>")
        writeStagedFile(site, "text.md", "# Present")
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("Reader Markdown must link to GitHub"))
    }

    @Test
    fun rejectsHtmlHeadingWithoutAnExplicitAnchor() {
        val (project, site) = createBaseTrees("missing-anchor")
        Files.writeString(site.resolve("index.html"), "<h1>Present</h1><a href='#present'>Missing anchor</a>")
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("#present"))
    }

    @Test
    fun rejectsMissingAssetInALinkedModuleEntrypoint() {
        val (project, site) = createBaseTrees("missing-image")
        writeStagedFile(site, "index.html", "<a href='runtime/core/index.html'>Core</a>")
        writeStagedFile(site, "runtime/core/index.html", "<img src='../../images/missing.png'>")
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("images/missing.png"))
    }

    @Test
    fun rejectsRootRelativeLinkOutsideStrataDeployment() {
        val (project, site) = createBaseTrees("host-root-link")
        val inventory = writeInventory(project, site)
        Files.writeString(site.resolve("index.html"), "<a href='/api/index.html'>Wrong host root</a>")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("/api/index.html"))
    }

    @Test
    fun rejectsMissingHtmlLinkTarget() {
        val (project, site) = createBaseTrees("missing-html-link")
        Files.writeString(site.resolve("index.html"), "<a href=\"missing.html\">Missing</a>")
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("missing.html"))
    }

    @Test
    fun rejectsLeftoverCurrentGuideWithRegenerationDiagnostic() {
        val (project, site) = createBaseTrees("leftover-guide")
        writeStagedFile(site, "guide/index.html", "<h1>Old reader guides</h1>")

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                writeInventory(project, site)
            }
        assertTrue(failure.message.orEmpty().contains("clean generated output and regenerate"))
    }

    @Test
    fun preservesLegacyReleaseSnapshotsWithoutInspectingTheirReaderGuides() {
        val (project, site) = createBaseTrees("legacy-snapshot")
        val legacyContent = "<a href='guide/missing.md#absent'>Legacy reader guide</a>"
        Files.writeString(project.resolve("README.md"), "${PAGES_BASE}releases/0.1.0/guide/components.md#row")
        writeStagedFile(site, "index.html", "<a href='releases/0.1.0/index.html'>Previous release</a>")
        writeStagedFile(site, "releases/0.1.0/index.html", legacyContent)
        writeStagedFile(site, "releases/0.1.0/guide/components.md", "# Components")
        val inventory = writeInventory(project, site)

        PagesStagingChecker.check(project, site, inventory)
        assertTrue(Files.readAllLines(inventory).contains("/releases/0.1.0/index.html"))
        assertTrue(Files.readAllLines(inventory).contains("/releases/0.1.0/guide/components.md"))
        assertEquals(legacyContent, Files.readString(site.resolve("releases/0.1.0/index.html")))
    }

    @Test
    fun rejectsMissingDirectlyAdvertisedReleaseTargets() {
        val (project, site) = createBaseTrees("missing-release")
        val target = "releases/not-a-version/missing.html"
        listOf(false, true).forEach { advertiseInHtml ->
            Files.writeString(project.resolve("README.md"), if (advertiseInHtml) "# Project" else "$PAGES_BASE$target")
            Files.writeString(site.resolve("index.html"), if (advertiseInHtml) "<a href='$target'>Release</a>" else "<h1>API</h1>")
            val inventory = writeInventory(project, site)

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    PagesStagingChecker.check(project, site, inventory)
                }
            assertTrue(failure.message.orEmpty().contains(target))
        }
    }

    @Test
    fun rejectsAdvertisedReleaseWithSymbolicAncestryWhenSupported() {
        val (project, site) = createBaseTrees("symbolic-release")
        val externalSnapshot = Files.createDirectories(temporaryRoot.resolve("external-snapshot"))
        Files.writeString(externalSnapshot.resolve("index.html"), "<h1>External release</h1>")
        Files.createDirectories(site.resolve("releases"))
        val created = runCatching { Files.createSymbolicLink(site.resolve("releases/0.1.0"), externalSnapshot) }.isSuccess
        if (created) {
            Files.writeString(project.resolve("README.md"), "${PAGES_BASE}releases/0.1.0/index.html")
            val inventory = writeInventory(project, site)

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    PagesStagingChecker.check(project, site, inventory)
                }
            assertTrue(failure.message.orEmpty().contains("symbolic ancestry"))
        }
    }

    @Test
    fun rejectsEncodedPathTraversalOutsideStagedSite() {
        val (project, site) = createBaseTrees("path-traversal")
        val inventory = writeInventory(project, site)
        Files.writeString(site.parent.resolve("outside.html"), "<h1>Outside</h1>")

        listOf("%2e%2e/outside.html", "releases/0.1.0/%2e%2e/%2e%2e/%2e%2e/outside.html").forEach { target ->
            Files.writeString(site.resolve("index.html"), "<a href='$target'>Outside</a>")
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    PagesStagingChecker.check(project, site, inventory)
                }
            assertTrue(failure.message.orEmpty().contains("escapes the deployed site"))
        }
    }

    @Test
    fun checksLinkedHtmlAnchorsWithoutRecursivelyCrawlingPackagePages() {
        val (project, site) = createBaseTrees("bounded-html-check")
        writeStagedFile(site, "index.html", "<a href='api/index.html'>API</a>")
        writeStagedFile(site, "api/index.html", "<a href='package.html#types'>Package</a>")
        writeStagedFile(site, "api/package.html", "<h1 id='types'>Types</h1><a href='not-crawled.html'>Child</a>")
        val inventory = writeInventory(project, site)

        PagesStagingChecker.check(project, site, inventory)
        assertTrue(Files.readAllLines(inventory).contains("/api/package.html").not())
    }

    @Test
    fun ignoresTemplatedPagesUrlPrefix() {
        val (project, site) = createBaseTrees("templated-pages-url")
        Files.writeString(project.resolve("README.md"), "${PAGES_BASE}releases/${'$'}{version}/")
        val inventory = writeInventory(project, site)

        PagesStagingChecker.check(project, site, inventory)
        assertTrue(Files.readAllLines(inventory).none { path -> path.startsWith("/releases/") })
    }

    @Test
    fun ignoresBashParameterExpansionInPagesUrlPrefix() {
        val (project, site) = createBaseTrees("bash-parameter-pages-url")
        Files.writeString(
            project.resolve("workflow.yml"),
            "pages_base=\"${PAGES_BASE}releases/${'$'}{previous_tag#v}\"",
        )
        val inventory = writeInventory(project, site)

        PagesStagingChecker.check(project, site, inventory)
        assertTrue(Files.readAllLines(inventory).none { path -> path.startsWith("/releases/") })
    }

    private fun createBaseTrees(name: String): Pair<Path, Path> {
        val project = Files.createDirectories(temporaryRoot.resolve("$name-project"))
        Files.writeString(project.resolve("README.md"), "# Project")
        val site = Files.createDirectories(temporaryRoot.resolve("$name-site"))
        Files.writeString(site.resolve("index.html"), "<h1 id=\"api\">API</h1>")
        writeSourceEvidence(site)
        return project to site
    }

    private fun writeSourceEvidence(
        site: Path,
        revision: String = "v0.1.0",
        receiptRevision: String = revision,
    ) {
        Files.writeString(site.resolve("source-revision.txt"), "$revision\n")
        Files.writeString(
            site.resolve("source-receipt.json"),
            "{\"commit\":\"$SOURCE_COMMIT\",\"revision\":\"$receiptRevision\"}\n",
        )
    }

    private fun writeInventory(
        project: Path,
        site: Path,
    ): Path =
        site.resolve("pages-public-urls.txt").also { inventory ->
            PagesPublicUrlInventory.write(project, site, inventory)
        }

    private fun writeStagedFile(
        site: Path,
        relative: String,
        content: String,
    ) {
        val file = site.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private companion object {
        private const val PAGES_BASE = "https://gh.s7a.dev/strata/"
        private const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
