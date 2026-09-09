package dev.s7a.strata.integration.docs

import InboxMessage
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import inboxScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import dev.s7a.strata.integration.docs.skill.InboxMessage as AcceptedMessage
import dev.s7a.strata.integration.docs.skill.inboxScreen as acceptedInboxScreen

/**
 * Preserves the independent first attempt and deterministically checks the corrected API-only example.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class SkillForwardTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun uneditedFirstAttemptCompilesButExposesTheSingleLineFixedSizePitfall() {
        val definition =
            inboxScreen(
                SkillTestSource(720L),
                SkillTestSource(false),
                SkillTestSource(listOf(InboxMessage(1, "Message"))),
                SkillTestSource(false),
                TextAreaState("draft"),
            ) {}
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        createMinecraftUiHost(definition, profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val failure = assertThrows(IllegalArgumentException::class.java) { host.frame(IntSize(160, 160)) }
            assertTrue(failure.message.orEmpty().contains("text constraints"))
        }
    }

    @Test
    fun acceptedScreenIsolatesClockAndSendingAndPreservesDraftCompositionAndHistoryAnchor() {
        val clock = SkillTestSource(720L)
        val sending = SkillTestSource(false)
        val initial = List(10_000) { AcceptedMessage(it.toLong(), "Message $it") }
        val history = SkillTestSource(initial)
        val loading = SkillTestSource(false)
        val draft = TextAreaState("draft")
        val definition = acceptedInboxScreen(clock, sending, history, loading, draft) {}
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        createMinecraftUiHost(definition, profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(IntSize(160, 160))
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, scanCode = 0))
            val focus = host.textInputFocus
            assertNotNull(focus)
            host.dispatchTextInput(TextInputEvent.Preedit("A", 1, listOf("A"), 0))
            host.frame(IntSize(160, 160))
            host.startRenderMonitoring().use { monitor ->
                clock.publish(721L)
                host.frame(IntSize(160, 160))
                val changed = monitor.snapshot()
                assertEquals(1L, changed.counts[UiRenderMetric.StateComponentEvaluation])
                assertEquals(0L, changed.counts[UiRenderMetric.ObserveEvaluation])
                assertEquals(0L, changed.counts[UiRenderMetric.RowEvaluation])
                assertSame(focus, host.textInputFocus)
                assertEquals("draft", draft.value)
                monitor.checkpoint()
                clock.publish(721L + 1_440L)
                host.frame(IntSize(160, 160))
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.ContentEvaluation])
                monitor.checkpoint()
                sending.publish(true)
                host.frame(IntSize(160, 160))
                assertEquals(1L, monitor.snapshot().counts[UiRenderMetric.StateComponentEvaluation])
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.RowEvaluation])
                assertSame(focus, host.textInputFocus)
                host.dispatchTextInput(TextInputEvent.Character('B'.code))
                assertEquals("draftB", draft.value)
                host.dispatchPointer(PointerEvent.Scroll(IntOffset(8, 36), 0.0, 6.0))
                val beforePrepend = host.frame(IntSize(160, 160))
                monitor.checkpoint()
                history.publish(listOf(AcceptedMessage(-1, "New")) + initial)
                val afterPrepend = host.frame(IntSize(160, 160))
                val beforeRows = beforePrepend.semantics.filter { it.bounds.top in 32 until 92 }
                val afterRows = afterPrepend.semantics.filter { it.bounds.top in 32 until 92 }
                assertEquals(beforeRows, afterRows)
                assertTrue(monitor.snapshot().counts.getValue(UiRenderMetric.RowEvaluation) <= 7L)
                assertTrue(monitor.snapshot().nodes.size < 100)
            }
        }
    }
}
