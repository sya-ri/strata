@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies serial logical ownership and actual release of old text/source/callback histories on the JVM.
 */
internal class TextAreaOwnershipTest {
    @Test
    fun serialMigrationRetainsScrollAndRejectsInvalidWritesFromDifferentPhysicalAndLogicalOwnersFirst() {
        val owner = RuntimeExecutionOwner()
        val state = owner.run { TextAreaState("日🙂", maxLength = 8) }
        val scroll = owner.run { state.scrollState }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                assertFailsWith<IllegalStateException> { state.value = "\uD800" }
                owner.run {
                    state.value = "日\r\n🙂"
                    assertEquals("日\n🙂", state.value)
                    assertSame(scroll, state.scrollState)
                    RuntimeExecutionOwner().run {
                        assertFailsWith<IllegalStateException> { state.value = "\uD800" }
                    }
                    state.observe {}.close()
                }
            }.get(5, TimeUnit.SECONDS)
            owner.run { assertEquals("日\n🙂", state.value) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun shortCanonicalReplacementReleasesConvertedTextRejectedInputsDiscardedSourcesAndObserverCaptures() {
        val (state, references) = detachedHistory()
        repeat(20) {
            if (references.any { it.get() != null }) {
                System.gc()
                Thread.sleep(10)
            }
        }
        assertEquals("final", state.value)
        assertTrue(references.all { it.get() == null }, "State retained obsolete text, discarded source, or released observer capture")
        assertTrue(TextAreaState::class.java.declaredFields.none { it.type == StringBuilder::class.java })
    }

    private fun detachedHistory(): Pair<TextAreaState, List<WeakReference<*>>> {
        val references = mutableListOf<WeakReference<*>>()
        val source = "A".repeat(1_048_576)
        references.add(WeakReference(source))
        val substring = source.substring(32, 2_080)
        val concatenation = substring + "日"
        val state = TextAreaState(substring)
        state.value = concatenation
        references.add(WeakReference(substring))
        references.add(WeakReference(concatenation))
        val converted = "🙂\r\n".repeat(4_096)
        state.value = converted
        references.add(WeakReference(converted))
        references.add(WeakReference(state.value))
        val rejected = "日".repeat(16_384) + "\uD800"
        assertFailsWith<IllegalArgumentException> { state.value = rejected }
        references.add(WeakReference(rejected))
        val captured = Any()
        references.add(WeakReference(captured))
        val release = state.observe {
            captured.hashCode()
            state.value = "nested"
        }
        state.value = "nested"
        release.close()
        state.value = "final"
        return state to references
    }
}
