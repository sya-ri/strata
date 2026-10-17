package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.integration.docs.recheck.HistoryEntry
import dev.s7a.strata.integration.docs.recheck.PlayerEntry
import dev.s7a.strata.integration.docs.recheck.messageEditorScreen
import dev.s7a.strata.integration.docs.recheck.playerSearchScreen
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Checks two unedited first outputs from a fresh author who read the improved public skill without API-selection hints.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class SkillRecheckTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun messageEditorFirstOutputPreservesEditingAndHistoryAcrossIndependentUpdates() {
        val clock = SkillTestSource(60L)
        val sending = SkillTestSource(false)
        val loading = SkillTestSource(false)
        val initial = List(10_000) { HistoryEntry(it.toLong(), "Message $it") }
        val history = SkillTestSource(initial)
        val draft = TextAreaState("draft")
        var sends = 0
        val definition = messageEditorScreen(clock, sending, loading, history, draft) { sends += 1 }
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(viewport)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, scanCode = 0))
            val focus = host.textInputFocus
            assertNotNull(focus)
            host.dispatchTextInput(TextInputEvent.Preedit("conversion", 3, listOf("conversion"), 0))
            host.frame(viewport)
            clock.publish(61L)
            host.frame(viewport)
            clock.publish(120L)
            host.frame(viewport)
            assertSame(focus, host.textInputFocus)
            sending.publish(true)
            loading.publish(true)
            host.frame(viewport)
            assertSame(focus, host.textInputFocus)
            assertEquals("draft", draft.value)
            host.dispatchTextInput(TextInputEvent.Character('X'.code))
            assertEquals("draftX", draft.value)
            host.dispatchPointer(PointerEvent.Scroll(IntOffset(12, 130), 0.0, 8.0))
            val before = host.frame(viewport).semantics.filter { it.bounds.top in 108 until 176 }
            assertTrue(before.isNotEmpty())
            history.publish(listOf(HistoryEntry(-1, "New")) + initial + HistoryEntry(10_000, "Last"))
            val after = host.frame(viewport).semantics.filter { it.bounds.top in 108 until 176 }
            assertEquals(before, after)
            // A fractional row offset adds a partially visible row before the two overscan rows.
            assertEquals(0, sends)
        }
    }

    @Test
    fun playerSearchFirstOutputSuppressesEqualProjectionsAndRetainsQueryAndResultAnchor() {
        val clock = SkillTestSource(60L)
        val searching = SkillTestSource(false)
        val initial = List(10_000) { PlayerEntry(it.toString(), "Player $it") }
        val results = SkillTestSource(initial)
        val query = TextFieldState("query")
        val definition = playerSearchScreen(clock, searching, results, query) {}
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(viewport)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, scanCode = 0))
            val focus = host.textInputFocus
            assertNotNull(focus)
            host.dispatchTextInput(TextInputEvent.Preedit("conversion", 3, listOf("conversion"), 0))
            host.frame(viewport)
            clock.publish(119L)
            host.frame(viewport)
            clock.publish(120L)
            host.frame(viewport)
            searching.publish(true)
            host.frame(viewport)
            assertSame(focus, host.textInputFocus)
            assertEquals("query", query.value)
            host.dispatchTextInput(TextInputEvent.Character('X'.code))
            assertEquals("queryX", query.value)
            host.dispatchPointer(PointerEvent.Scroll(IntOffset(12, 120), 0.0, 8.0))
            val before = host.frame(viewport).semantics.filter { it.bounds.top in 84 until 176 }
            assertTrue(before.isNotEmpty())
            results.publish(listOf(PlayerEntry("new", "New")) + initial + PlayerEntry("last", "Last"))
            val after = host.frame(viewport).semantics.filter { it.bounds.top in 84 until 176 }
            assertEquals(before, after)
            results.publish(emptyList())
            searching.publish(false)
            host.frame(viewport)
        }
    }

    private companion object {
        private val viewport = IntSize(200, 180)
    }
}
