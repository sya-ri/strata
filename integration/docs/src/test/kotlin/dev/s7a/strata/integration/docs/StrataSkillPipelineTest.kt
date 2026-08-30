package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
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
        val launch =
            StrataSkillLaunchArguments.parse(
                arrayOf(
                    root.toString(),
                    root.resolve("integration/docs/build/strata-skill/test").toString(),
                    root.resolve("integration/docs/src/skillExamples/kotlin").toString(),
                    root.resolve("api/build/classes/kotlin/main").toString(),
                ),
            )

        val first = StrataSkillPipeline.prepare(launch)
        val second = StrataSkillPipeline.prepare(launch)

        assertEquals(first, second)
        assertEquals(
            setOf(
                "README.md",
                "docs/modrinth-project.md",
                "skills/strata/references/setup.md",
                "skills/strata/references/components.md",
                "skills/strata/references/modifiers-and-layout.md",
                "skills/strata/references/patterns.md",
                "skills/strata/references/custom-components.md",
            ),
            first.keys,
        )
        val readme = first.getValue("README.md")
        val modrinthProject = first.getValue("docs/modrinth-project.md")
        val setup = first.getValue("skills/strata/references/setup.md")
        val openExample =
            ShowcaseSources
                .extract(
                    SourceReference("dev/s7a/strata/integration/docs/skill/OpenScreenExample.kt", "skill-open"),
                    launch.exampleSourceRoot,
                ).source
        val fencedExample = "```kotlin\n$openExample\n```"
        assertTrue(readme.contains(fencedExample))
        assertTrue(modrinthProject.contains(fencedExample))
        assertTrue(setup.contains(fencedExample))
        listOf(modrinthProject, setup).forEach(::assertUnicodeFontSetup)
        val components = first.getValue("skills/strata/references/components.md")
        val modifiers = first.getValue("skills/strata/references/modifiers-and-layout.md")
        val patterns = first.getValue("skills/strata/references/patterns.md")
        val customComponents = first.getValue("skills/strata/references/custom-components.md")
        assertComponentReferences(components)
        assertTrue(modifiers.contains("Compiled JVM API fingerprints"))
        assertTrue(modifiers.contains("data class ListLoadRequest(public val suggestedCount: Int)"))
        assertTrue(modifiers.contains("TextAreaState"))
        assertTrue(modifiers.contains("TextAreaViewport"))
        assertTrue(modifiers.contains("TextLayout"))
        assertTiledImageReferences(modifiers)
        assertOwnerAwareStateSignatures(modifiers)
        assertTrue(patterns.contains("itemCount = { items.size }"))
        assertTrue(patterns.contains("request.suggestedCount"))
        assertTrue(patterns.contains("listState.refresh()"))
        assertTrue(patterns.contains("listState.jumpToKey"))
        assertTrue(patterns.contains("The `Int` and `List` overloads are immutable snapshots."))
        assertTrue(customComponents.contains("https://github.com/sya-ri/strata/blob/master/docs/element-spi.md"))
        assertDocumentationLinks(first)
    }

    private fun assertComponentReferences(components: String) {
        assertTrue(components.contains("fun UiScope.Button"))
        assertTrue(components.contains("fun UiScope.TextArea"))
        assertTrue(components.contains("fun UiScope.TiledImage"))
        assertTrue(components.contains("https://github.com/sya-ri/strata/blob/master/docs/components.md#text-area"))
        assertTrue(components.contains("https://github.com/sya-ri/strata/blob/master/docs/components.md#tiled-image"))
        assertTrue(components.contains("https://github.com/sya-ri/strata/blob/master/docs/components.md#button"))
    }

    private fun assertDocumentationLinks(documents: Map<String, String>) {
        assertTrue(documents.values.none { document -> document.contains("../../../docs/") })
        assertTrue(documents.values.none { document -> document.contains("strata/guide/") })
        val modrinthProject = documents.getValue("docs/modrinth-project.md")
        assertTrue(modrinthProject.contains("[Dokka API reference](https://gh.s7a.dev/strata/)"))
        assertTrue(modrinthProject.contains("[Reader guides on GitHub](https://github.com/sya-ri/strata/blob/master/README.md#documentation)"))
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

    private fun assertUnicodeFontSetup(document: String) {
        assertTrue(document.contains("makes no other functional change").not())
        assertTrue(document.contains("UiText.withFont"))
        assertTrue(document.contains("Existing overloads without a font argument remain available."))
        assertTrue(document.contains("Unknown font IDs produce missing glyphs instead of silently selecting `minecraft:default`."))
        assertTrue(document.contains("Unicode scalar"))
        assertTrue(document.contains("UTF-16 code units"))
        assertTrue(document.contains("inline IME composition"))
        assertTrue(document.contains("TextLayout.Multiline"))
        assertTrue(document.contains("TextArea"))
        assertTrue(document.contains("TextField"))
        assertTrue(document.contains("adapters that expose only committed characters"))
        assertTrue(document.contains("dev.s7a.strata:strata-runtime-minecraft-fonts-lwjgl:0.1.2"))
        assertTrue(document.contains("does not bundle LWJGL, ICU, Gson, or native binaries"))
        assertTrue(document.contains("unsafe STB coordinate conversions remain invalid"))
        assertTrue(document.contains("https://github.com/sya-ri/strata/blob/master/docs/text.md"))
        assertTrue(document.contains("https://github.com/sya-ri/strata/blob/master/docs/font-resources.md#numeric-provider-settings"))
        assertTrue(document.contains("https://github.com/sya-ri/strata/blob/master/docs/font-resources.md#acceptance-evidence"))
        assertTrue(document.contains("independent GPU evidence"))
    }

    private fun repositoryRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.isDirectory(current.resolve("api"))) current else current.resolve("../..").normalize()
    }
}
