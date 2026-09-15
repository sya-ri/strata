package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies deterministic public-skill generation from real compiled API and example inputs.
 */
internal class StrataSkillPipelineTest {
    @Test
    fun realInputsProduceAllReferencesAndOneSharedOpeningExample() {
        val root = repositoryRoot()
        val releaseVersion = "9.8.7"
        val launch =
            StrataSkillLaunchArguments.parse(
                arrayOf(
                    root.toString(),
                    root.resolve("integration/docs/build/strata-skill/test").toString(),
                    root.resolve("integration/docs/src/skillExamples/kotlin").toString(),
                    releaseVersion,
                    CompiledApiFixture.classes.toString(),
                ),
            )

        val first = StrataSkillPipeline.prepare(launch)
        val second = StrataSkillPipeline.prepare(launch)

        assertEquals(first, second)
        assertEquals(
            setOf(
                "README.md",
                "docs/publication/modrinth-project.md",
                "skills/strata/references/setup.md",
                "skills/strata/references/components.md",
                "skills/strata/references/modifiers-and-layout.md",
                "skills/strata/references/patterns.md",
                "skills/strata/references/custom-components.md",
            ),
            first.keys,
        )
        val readme = first.getValue("README.md")
        val modrinthProject = first.getValue("docs/publication/modrinth-project.md")
        val setup = first.getValue("skills/strata/references/setup.md")
        val openExample =
            ShowcaseSources
                .extract(
                    SourceReference("dev/s7a/strata/integration/docs/skill/OpenScreenExample.kt", "skill-open"),
                    launch.exampleSourceRoot,
                ).source
        val fencedExample = "```kotlin\n$openExample\n```"
        assertTrue(openExample.contains("onActivate"))
        assertGeneratedDocumentContracts(readme, modrinthProject, setup, fencedExample, releaseVersion)
        val components = first.getValue("skills/strata/references/components.md")
        val modifiers = first.getValue("skills/strata/references/modifiers-and-layout.md")
        val patterns = first.getValue("skills/strata/references/patterns.md")
        assertUnicodeFontSetup(patterns, releaseVersion)
        val customComponents = first.getValue("skills/strata/references/custom-components.md")
        assertComponentReferences(components)
        assertModifierReferences(modifiers)
        assertTiledImageReferences(modifiers)
        assertOwnerAwareStateSignatures(modifiers)
        assertTrue(patterns.contains("itemCount = { items.size }"))
        assertTrue(patterns.contains("request.suggestedCount"))
        assertTrue(patterns.contains("listState.refresh()"))
        assertTrue(patterns.contains("listState.jumpToKey"))
        assertTrue(patterns.contains("The `Int` and `List` overloads are immutable snapshots."))
        assertTrue(customComponents.contains("https://github.com/sya-ri/strata/blob/master/docs/reference/element-spi.md"))
        assertDocumentationLinks(first)
    }

    @Test
    fun launcherRejectsNonSemanticReleaseVersion() {
        val root = repositoryRoot()

        assertThrows(IllegalArgumentException::class.java) {
            StrataSkillLaunchArguments.parse(
                arrayOf(
                    root.toString(),
                    root.resolve("integration/docs/build/strata-skill/test").toString(),
                    root.resolve("integration/docs/src/skillExamples/kotlin").toString(),
                    "latest",
                    CompiledApiFixture.classes.toString(),
                ),
            )
        }
    }

    private fun assertModifierReferences(modifiers: String) {
        assertTrue(modifiers.contains("Compiled JVM API fingerprints"))
        assertTrue(modifiers.contains("data class ListLoadRequest(public val suggestedCount: Int)"))
        assertTrue(modifiers.contains("TextAreaState"))
        assertTrue(modifiers.contains("TextAreaViewport"))
        assertTrue(modifiers.contains("TextLayout"))
        assertTrue(modifiers.contains("onActivate"))
        assertTrue(modifiers.contains("focused Enter or Space press"))
    }

    private fun assertComponentReferences(components: String) {
        assertTrue(components.contains("fun UiScope.Button"))
        assertTrue(components.contains("fun UiScope.TextArea"))
        assertTrue(components.contains("fun UiScope.TiledImage"))
        assertTrue(components.contains("https://github.com/sya-ri/strata/blob/master/docs/reference/components.md#text-area"))
        assertTrue(components.contains("https://github.com/sya-ri/strata/blob/master/docs/reference/components.md#tiled-image"))
        assertTrue(components.contains("https://github.com/sya-ri/strata/blob/master/docs/reference/components.md#button"))
    }

    private fun assertDocumentationLinks(documents: Map<String, String>) {
        assertTrue(documents.values.none { document -> document.contains("../../../docs/") })
        assertTrue(documents.values.none { document -> document.contains("strata/guide/") })
        val modrinthProject = documents.getValue("docs/publication/modrinth-project.md")
        assertTrue(modrinthProject.contains("[Dokka API reference](https://gh.s7a.dev/strata/)"))
        assertTrue(modrinthProject.contains("[Reader guides on GitHub](https://github.com/sya-ri/strata/blob/master/docs/README.md)"))
    }

    private fun assertTiledImageReferences(modifiers: String) {
        assertTrue(modifiers.contains("PanZoomState"))
        assertTrue(modifiers.contains("TiledImageSource"))
        assertTrue(modifiers.contains("TiledImageScope.atContentPosition"))
    }

    private fun assertOwnerAwareStateSignatures(modifiers: String) {
        assertTrue(
            modifiers.contains(
                "#### `ImageSource.Resource`\n\n```kotlin\ndata class Resource(public val id: ResourceId) : ImageSource\nval id: ResourceId\n```",
            ),
        )
        assertTrue(modifiers.contains("#### `CycleButtonState.Companion`"))
        assertTrue(modifiers.contains("#### `PlayerSkinSource.Name`"))
    }

    private fun assertExactReleaseVersion(
        document: String,
        releaseVersion: String,
    ) {
        assertTrue(document.contains("dev.s7a.strata:strata-api:$releaseVersion"))
        assertTrue(document.contains("dev.s7a.strata:strata-runtime-minecraft-fabric-<minecraft-version>:$releaseVersion"))
        assertTrue(document.contains("<strata-version>").not())
    }

    private fun assertGeneratedDocumentContracts(
        readme: String,
        modrinthProject: String,
        setup: String,
        fencedExample: String,
        releaseVersion: String,
    ) {
        listOf(readme, modrinthProject, setup).forEach { document -> assertTrue(document.contains(fencedExample)) }
        assertReadmeInstallationVersion(readme, releaseVersion)
        listOf(modrinthProject, setup).forEach { document -> assertExactReleaseVersion(document, releaseVersion) }
        assertTrue(setup.contains("\"strata\": \">=$releaseVersion\""))
    }

    private fun assertReadmeInstallationVersion(
        readme: String,
        releaseVersion: String,
    ) {
        val begin = "<!-- strata-installation:start -->"
        val end = "<!-- strata-installation:end -->"
        val beginIndex = readme.indexOf(begin)
        val endIndex = readme.indexOf(end)
        assertTrue(beginIndex < endIndex)
        val installation = readme.substring(beginIndex + begin.length, endIndex)
        assertExactReleaseVersion(installation, releaseVersion)
        assertTrue(installation.contains("\"strata\": \">=$releaseVersion\""))
        val outsideInstallation = readme.substring(0, beginIndex) + readme.substring(endIndex + end.length)
        assertTrue(outsideInstallation.contains(releaseVersion).not())
    }

    private fun assertUnicodeFontSetup(
        document: String,
        releaseVersion: String,
    ) {
        assertTrue(document.contains("makes no other functional change").not())
        assertTrue(document.contains("UiText.withFont"))
        assertTrue(document.contains("An inner font wrapper wins over outer or component selection."))
        assertTrue(document.contains("unknown font IDs produce missing glyphs"))
        assertTrue(document.contains("Unicode scalar"))
        assertTrue(document.contains("UTF-16 code units"))
        assertTrue(document.contains("keep preedit separate until committed"))
        assertTrue(document.contains("Focus loss and terminal cleanup clear composition."))
        assertTrue(document.contains("TextLayout.Multiline"))
        assertTrue(document.contains("TextArea"))
        assertTrue(document.contains("TextField"))
        assertTrue(document.contains("target-specific IME support"))
        assertTrue(document.contains("dev.s7a.strata:strata-runtime-minecraft-fonts-lwjgl:$releaseVersion"))
        assertTrue(document.contains("does not bundle LWJGL, ICU, Gson, or native binaries"))
        assertTrue(document.contains("incompatible native generations must run in separate processes"))
        assertTrue(document.contains("Each host owns and closes its backend and bounded caches"))
        assertTrue(document.contains("https://github.com/sya-ri/strata/blob/master/docs/guides/text.md"))
        assertTrue(document.contains("https://github.com/sya-ri/strata/blob/master/docs/guides/fonts.md#numeric-provider-settings"))
    }

    private fun repositoryRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.isDirectory(current.resolve("api"))) current else current.resolve("../..").normalize()
    }
}
