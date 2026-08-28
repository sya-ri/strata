package dev.s7a.strata

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies canonical multiline text, UTF-16 bounds, sole-observer ownership, and stable vertical scroll state without a runtime.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class TextAreaStateTest {
    @Test
    fun normalizesEveryExplicitNewlineBeforeApplyingTheUtf16Maximum() {
        for (separator in listOf("\n", "\r\n", "\r", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029")) {
            val value = "日$separator🙂"
            assertEquals("日\n🙂", TextAreaState(value, maxLength = 4).value)
            assertThrows(IllegalArgumentException::class.java) { TextAreaState(value, maxLength = 3) }
        }
        assertEquals("\nA\n\n", TextAreaState("\r\nA\r\r\n", maxLength = 4).value)
        assertEquals("\n".repeat(8), TextAreaState("\r\n".repeat(8), maxLength = 8).value)
        assertThrows(IllegalArgumentException::class.java) { TextAreaState(maxLength = 0) }
        assertThrows(IllegalArgumentException::class.java) { TextAreaState(maxLength = -1) }
    }

    @Test
    fun unsupportedWritesPreserveTheValueAndDoNotNotifyTheEditor() {
        val state = TextAreaState("日本🙂", maxLength = 8)
        val changes = mutableListOf<String>()
        state.observe { value -> changes.add(value) }.use {
            for (unsupported in listOf("\u0000", "\u0001", "\t", "\u001F", "\u007F", "\u00A7", "\uD83D", "\uDE42", "\uD83DA")) {
                assertThrows(IllegalArgumentException::class.java) { TextAreaState(unsupported) }
                assertThrows(IllegalArgumentException::class.java) { state.value = "A${unsupported}B" }
                assertEquals("日本🙂", state.value)
            }
            assertThrows(IllegalArgumentException::class.java) { state.value = "🙂".repeat(5) }
            assertEquals("日本🙂", state.value)
            assertTrue(changes.isEmpty())
            val formatted = "e\u0301\u200D\u2066🙂\u2069"
            state.value = formatted
            assertEquals(formatted, state.value)
            assertEquals(listOf(formatted), changes)
        }
    }

    @Test
    fun observerNotificationsUseNormalizedDistinctValuesAndReleaseOnlyTheirOwnInterval() {
        val state = TextAreaState("A\nB")
        val changes = mutableListOf<String>()
        val callback: (String) -> Unit = { value -> changes.add(value) }
        val first = state.observe(callback)
        assertThrows(IllegalStateException::class.java) { state.observe { } }
        state.value = "A\r\nB"
        assertTrue(changes.isEmpty())
        state.value = "日\r🙂"
        assertEquals(listOf("日\n🙂"), changes)
        first.close()
        val second = state.observe(callback)
        try {
            first.close()
            state.value = "한국어\u2028日本語"
            assertEquals(listOf("日\n🙂", "한국어\n日本語"), changes)
        } finally {
            second.close()
        }
        state.value = "Detached"
        assertEquals(listOf("日\n🙂", "한국어\n日本語"), changes)
    }

    @Test
    fun scrollPositionIsOwnedByTheStateAndSurvivesValueWritesAndEditorRelease() {
        val state = TextAreaState("A")
        val scroll = state.scrollState
        scroll.scrollTo(20.0)
        val editor = state.observe { }
        state.value = "A\r\nB"
        editor.close()
        assertSame(scroll, state.scrollState)
        assertEquals(20.0, state.scrollState.metrics.offset)
        state.scrollState.observe { }.use { origin ->
            scroll.updateGeometry(viewportExtent = 10, contentExtent = 15, origin = origin)
            assertEquals(5.0, scroll.metrics.offset)
        }
        assertEquals("A\nB", state.value)
    }

    @Test
    fun wrongThreadValueScrollObservationAndReleaseFailWithoutChangingOwnership() {
        val state = TextAreaState("A")
        val scroll = state.scrollState
        val editor = state.observe { }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val failures =
                executor
                    .submit<List<Throwable?>> {
                        listOf<() -> Unit>(
                            { state.value },
                            { state.value = "B" },
                            { state.scrollState },
                            { scroll.scrollTo(4.0) },
                            { state.observe { } },
                            { editor.close() },
                        ).map { action -> runCatching(action).exceptionOrNull() }
                    }.get(5, TimeUnit.SECONDS)
            assertEquals(List(6) { IllegalStateException::class.java }, failures.map { failure -> failure?.javaClass })
            assertEquals("A", state.value)
            assertEquals(0.0, scroll.metrics.offset)
            assertThrows(IllegalStateException::class.java) { state.observe { } }
        } finally {
            executor.shutdownNow()
            editor.close()
        }
        state.observe { }.close()
    }

    @Test
    fun observerFailureLeavesTheNormalizedSuccessfulWriteCommitted() {
        val state = TextAreaState("A")
        val expected = IllegalStateException("Observer failed.")
        state.observe { throw expected }.use {
            assertSame(expected, assertThrows(IllegalStateException::class.java) { state.value = "日\r\n🙂" })
            assertEquals("日\n🙂", state.value)
            state.value = "日\u2029🙂"
            assertEquals("日\n🙂", state.value)
        }
    }
}
