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
        val components = first.getValue("skills/strata/references/components.md")
        val modifiers = first.getValue("skills/strata/references/modifiers-and-layout.md")
        val patterns = first.getValue("skills/strata/references/patterns.md")
        val customComponents = first.getValue("skills/strata/references/custom-components.md")
        assertTrue(components.contains("fun UiScope.Button"))
        assertTrue(components.contains("https://gh.s7a.dev/strata/guide/components.md#button"))
        assertTrue(modifiers.contains("Compiled JVM API fingerprints"))
        assertTrue(modifiers.contains("data class ListLoadRequest(public val suggestedCount: Int)"))
        assertOwnerAwareStateSignatures(modifiers)
        assertTrue(patterns.contains("itemCount = { items.size }"))
        assertTrue(patterns.contains("request.suggestedCount"))
        assertTrue(patterns.contains("listState.refresh()"))
        assertTrue(patterns.contains("listState.jumpToKey"))
        assertTrue(patterns.contains("The `Int` and `List` overloads are immutable snapshots."))
        assertTrue(customComponents.contains("https://gh.s7a.dev/strata/guide/element-spi.md"))
        assertTrue(first.values.none { document -> document.contains("../../../docs/") })
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

    private fun repositoryRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.isDirectory(current.resolve("api"))) current else current.resolve("../..").normalize()
    }
}
