package dev.s7a.strata.integration.web

import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs shared declarations through Minecraft sessions and the headless rasterizer, exporting browser comparison data.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RuntimeParityTest {
    @Test
    fun conditionalAndKeyedTransitionsAgreeWithHeadlessPresentation() {
        val scenario = ReactiveScenario()
        val snapshots = ArrayList<List<String>>()
        createMinecraftUiHost(scenario.definition(), ParityProfile.create()).use { host ->
            host.attach()
            val actions = listOf<() -> Unit>({}, scenario::advance, scenario::toggle, scenario::reorder, scenario::toggle)
            for (action in actions) {
                action()
                val frame = host.frame(ReactiveScenario.viewport)
                val image = rasterizeHeadless(frame.drawCommands, ReactiveScenario.viewport)
                assertEquals(ReactiveScenario.viewport, image.size)
                assertTrue(image.copyArgb().any { it != 0 })
                snapshots.add(frame.semantics.mapNotNull { (it.semantics.label as? UiText.Literal)?.value })
            }
        }
        val prefix = listOf("Strata runtime parity", "Advance", "Toggle", "Reorder", "Unavailable")
        assertEquals(
            listOf(
                prefix + listOf("Initial", "Alpha", "Beta"),
                prefix + listOf("Changed", "Alpha", "Beta"),
                prefix + listOf("Alpha", "Beta"),
                prefix + listOf("Beta", "Alpha"),
                prefix + listOf("Changed", "Beta", "Alpha"),
            ),
            snapshots,
        )
        val output = Path.of("build/parity/jvm.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, snapshots.joinToString(prefix = "[", postfix = "]") { labels -> labels.joinToString(prefix = "[", postfix = "]") { label -> "\"$label\"" } })
    }
}
