package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies hard-coded Pages URL coverage against isolated staged sites.
 */
internal class PagesStagingCheckerTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun acceptsRootDirectoryAndFileTargetsWithStagedFiles() {
        val project = Files.createDirectories(temporaryRoot.resolve("project"))
        val site = Files.createDirectories(temporaryRoot.resolve("site"))
        Files.writeString(
            project.resolve("README.md"),
            "https://gh.s7a.dev/strata/ https://gh.s7a.dev/strata/guide/ https://gh.s7a.dev/strata/guide/components.md#row",
        )
        Files.writeString(site.resolve("index.html"), "root")
        Files.createDirectories(site.resolve("guide"))
        Files.writeString(site.resolve("guide/index.html"), "guide")
        Files.writeString(
            site.resolve("guide/components.md"),
            """
            # Components

            <a id="row"></a>
            ## Row

            [Row](#row)
            ![Row](components/row.png)
            """.trimIndent(),
        )
        writeSourceEvidence(site)
        Files.createDirectories(site.resolve("guide/components"))
        Files.writeString(site.resolve("guide/components/row.png"), "image")
        val inventory = site.resolve("pages-public-urls.txt")
        PagesPublicUrlInventory.write(project, site, inventory)

        PagesStagingChecker.check(project, site, inventory)
        val paths = Files.readAllLines(inventory)
        assertTrue(paths.contains("/"))
        assertTrue(paths.contains("/index.html"))
        assertTrue(paths.contains("/guide/"))
        assertTrue(paths.contains("/guide/components.md"))
        assertTrue(paths.contains("/guide/components/row.png"))
        assertTrue(paths.contains("/source-receipt.json"))
        assertTrue(paths.contains("/source-revision.txt"))
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
        val project = Files.createDirectories(temporaryRoot.resolve("missing-project"))
        val site = Files.createDirectories(temporaryRoot.resolve("missing-site"))
        Files.writeString(project.resolve("README.md"), "https://gh.s7a.dev/strata/guide/")
        Files.createDirectories(site.resolve("guide"))
        Files.writeString(site.resolve("index.html"), "root")
        val inventory = site.resolve("pages-public-urls.txt")
        PagesPublicUrlInventory.write(project, site, inventory)

        assertThrows(IllegalArgumentException::class.java) {
            PagesStagingChecker.check(project, site, inventory)
        }
    }

    @Test
    fun rejectsRepositoryValidIntegrationLinkMissingFromStagedGuide() {
        val (project, site) = createBaseTrees("integration-link")
        val repositoryTarget = project.resolve("integration/api/example.md")
        Files.createDirectories(repositoryTarget.parent)
        Files.writeString(repositoryTarget, "# Example")
        val source = project.resolve("docs/guide.md")
        Files.createDirectories(source.parent)
        Files.writeString(source, "[Integration fixture](../integration/api/example.md)")
        Files.writeString(site.resolve("guide/guide.md"), Files.readString(source))
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("../integration/api/example.md"))
    }

    @Test
    fun rejectsRepositoryValidSkillLinkMissingFromNestedStagedGuide() {
        val (project, site) = createBaseTrees("skill-link")
        val repositoryTarget = project.resolve("skills/strata/SKILL.md")
        Files.createDirectories(repositoryTarget.parent)
        Files.writeString(repositoryTarget, "# Strata")
        val source = project.resolve("docs/nested/guide.md")
        Files.createDirectories(source.parent)
        Files.writeString(source, "[Public skill](../../skills/strata/SKILL.md)")
        val staged = site.resolve("guide/nested/guide.md")
        Files.createDirectories(staged.parent)
        Files.writeString(staged, Files.readString(source))
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("../../skills/strata/SKILL.md"))
    }

    @Test
    fun rejectsMissingStagedGuideAnchor() {
        val (project, site) = createBaseTrees("missing-anchor")
        Files.writeString(site.resolve("guide/guide.md"), "# Present\n\n[Missing](#absent)")
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("#absent"))
    }

    @Test
    fun rejectsMissingStagedGuideImage() {
        val (project, site) = createBaseTrees("missing-image")
        Files.writeString(site.resolve("guide/guide.md"), "![Missing](images/missing.png)")
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
        Files.writeString(site.resolve("guide/guide.md"), "[Wrong host root](/guide/index.html)")
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("/guide/index.html"))
    }

    @Test
    fun rejectsMissingHtmlLinkTarget() {
        val (project, site) = createBaseTrees("missing-html-link")
        Files.writeString(site.resolve("guide/index.html"), "<a href=\"missing.html\">Missing</a>")
        val inventory = writeInventory(project, site)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PagesStagingChecker.check(project, site, inventory)
            }
        assertTrue(failure.message.orEmpty().contains("missing.html"))
    }

    @Test
    fun ignoresTemplatedPagesUrlPrefix() {
        val (project, site) = createBaseTrees("templated-pages-url")
        Files.writeString(project.resolve("README.md"), "https://gh.s7a.dev/strata/releases/${'$'}{version}/")
        val inventory = writeInventory(project, site)

        PagesStagingChecker.check(project, site, inventory)
        assertTrue(Files.readAllLines(inventory).none { path -> path.startsWith("/releases/") })
    }

    @Test
    fun ignoresBashParameterExpansionInPagesUrlPrefix() {
        val (project, site) = createBaseTrees("bash-parameter-pages-url")
        Files.writeString(
            project.resolve("workflow.yml"),
            "pages_base=\"https://gh.s7a.dev/strata/releases/${'$'}{previous_tag#v}\"",
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
        Files.createDirectories(site.resolve("guide"))
        Files.writeString(site.resolve("guide/index.html"), "<h1 id=\"guides\">Guides</h1>")
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

    private companion object {
        private const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
