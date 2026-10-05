package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.SelectionList
import dev.s7a.strata.component.SelectionListState
import dev.s7a.strata.component.Slider
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.docs.skill.selectionViewportScreen
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Preserves the independent fixed-viewport/fill mismatch and checks matching allocated geometry.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class SkillViewportContractTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun fillingAProgressBarDoesNotRewriteItsExplicitSize() {
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        val definition =
            UiDefinition("Mismatched progress") {
                Column {
                    ProgressBar(0.5, size = IntSize(140, 8), modifier = Modifier.Empty.fillMaxWidth())
                }
            }
        createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val failure = assertThrows(IllegalArgumentException::class.java) { host.frame(IntSize(420, 300)) }
            assertTrue(failure.message.orEmpty().contains("requested size"))
        }
    }

    @Test
    fun documentedMinecraftControlWidthsMatchTheProfileBoundary() {
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        for (width in listOf(200, 201)) {
            val cycle = CycleButtonState(listOf("One", "Two"), "One")
            val slider = SliderState(0.5)
            val definitions =
                listOf(
                    UiDefinition("Cycle width") { Column { CycleButton(cycle, width = width) } },
                    UiDefinition("Slider width") { Column { Slider("Power", slider, width = width) } },
                )
            for (definition in definitions) {
                createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
                    if (width == 200) {
                        host.attach()
                        host.frame(IntSize(420, 300))
                    } else {
                        val failure = assertThrows(IllegalArgumentException::class.java) { host.attach() }
                        assertTrue(failure.message.orEmpty().contains("no larger than 200"))
                    }
                }
            }
        }
    }

    @Test
    fun fillingAListDoesNotRewriteItsExplicitViewport() {
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        val state = SelectionListState<String>()
        val definition =
            UiDefinition("Mismatched viewport") {
                SelectionList(
                    items = listOf("one", "two"),
                    keyOf = { it },
                    state = state,
                    viewportSize = IntSize(130, 180),
                    rowHeight = 28,
                    modifier = Modifier.Empty.fillMaxSize(),
                ) { Text(it) }
            }
        createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val failure = assertThrows(IllegalArgumentException::class.java) { host.frame(IntSize(420, 300)) }
            assertTrue(failure.message.orEmpty().contains("requested viewport size"))
        }
    }

    @Test
    fun allocatedViewportRendersAtBothEndpointsAndRetainsSelection() {
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        val items = List(30) { "Template $it" }
        val state = SelectionListState(initialSelection = items[3])
        for (viewport in listOf(IntSize(420, 300), IntSize(800, 500))) {
            createMinecraftUiHost(selectionViewportScreen(viewport, items, state), profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
                host.attach()
                host.frame(viewport)
                assertEquals(items[3], state.selectedKey)
            }
        }
    }
}
