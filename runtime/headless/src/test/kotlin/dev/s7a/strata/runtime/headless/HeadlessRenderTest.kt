package dev.s7a.strata.runtime.headless

import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies synchronous core rendering, logical semantics, and cleanup ownership.
 */
internal class HeadlessRenderTest {
    @Test
    fun renderUsesFixedViewportScalesPaintAndReturnsLogicalSemantics() {
        val probe = HeadlessProbe()
        val owner = Thread.currentThread()
        val description =
            buildUi {
                Row {
                    element(HeadlessPrimitive(probe, width = 2, height = 3, color = ArgbColor(0xFFFF0000.toInt()), label = "first"))
                    element(HeadlessPrimitive(probe, width = 2, height = 4, color = ArgbColor(0xFF00FF00.toInt()), label = "second"))
                }
            }

        val frame = renderHeadless(description, IntSize(4, 4), scale = 2)

        assertEquals(IntSize(4, 4), frame.viewport)
        assertEquals(2, frame.pixelScale)
        assertEquals(IntSize(8, 8), frame.image.size)
        assertEquals(0xFFFF0000.toInt(), frame.image.argbAt(0, 0))
        assertEquals(0xFF00FF00.toInt(), frame.image.argbAt(4, 0))
        assertEquals(
            listOf(
                SemanticsEntry(IntRect(0, 0, 2, 3), semanticsLabel("first")),
                SemanticsEntry(IntRect(2, 0, 4, 4), semanticsLabel("second")),
            ),
            frame.semantics,
        )
        assertEquals(
            listOf(
                HeadlessLifecycleEvent.Attach,
                HeadlessLifecycleEvent.Attach,
                HeadlessLifecycleEvent.Detach,
                HeadlessLifecycleEvent.Dispose,
                HeadlessLifecycleEvent.Detach,
                HeadlessLifecycleEvent.Dispose,
            ),
            probe.lifecycle,
        )
        assertTrue(probe.callbackThreads.all { thread -> thread === owner })
        assertEquals(2, probe.measures)
        assertEquals(2, probe.layouts)
        assertEquals(2, probe.paints)
        assertEquals(2, probe.semantics)
        assertThrows<UnsupportedOperationException> {
            (frame.semantics as MutableList<SemanticsEntry>).clear()
        }
    }

    @Test
    fun viewportAndAreaValidationPrecedeDescriptionLifecycle() {
        val invalidCases =
            listOf(
                InvalidRenderCase(IntSize(0, 1), 1, "Viewport width must be positive.", FailureKind.IllegalArgument),
                InvalidRenderCase(IntSize(1, 0), 1, "Viewport height must be positive.", FailureKind.IllegalArgument),
                InvalidRenderCase(IntSize(1, 1), 0, "Pixel scale must be positive.", FailureKind.IllegalArgument),
                InvalidRenderCase(IntSize(1, 1), -1, "Pixel scale must be positive.", FailureKind.IllegalArgument),
                InvalidRenderCase(
                    IntSize(Int.MAX_VALUE, 1),
                    2,
                    "Physical width exceeds Int.MAX_VALUE.",
                    FailureKind.Arithmetic,
                ),
                InvalidRenderCase(
                    IntSize(1, Int.MAX_VALUE),
                    2,
                    "Physical height exceeds Int.MAX_VALUE.",
                    FailureKind.Arithmetic,
                ),
                InvalidRenderCase(
                    IntSize(50_000, 50_000),
                    1,
                    "Physical pixel area exceeds Int.MAX_VALUE.",
                    FailureKind.Arithmetic,
                ),
            )
        invalidCases.forEach { invalid ->
            val probe = HeadlessProbe()
            val description = HeadlessPrimitive(probe)
            val failure =
                assertThrows<Throwable> {
                    renderHeadless(description, invalid.viewport, invalid.scale)
                }
            assertEquals(invalid.message, failure.message)
            when (invalid.failureKind) {
                FailureKind.IllegalArgument -> assertTrue(failure is IllegalArgumentException)
                FailureKind.Arithmetic -> assertTrue(failure is ArithmeticException)
            }
            assertTrue(probe.lifecycle.isEmpty())
            assertEquals(0, probe.measures)
            assertEquals(0, probe.validations)
            assertEquals(0, probe.creations)
        }
    }

    @Test
    fun workFailureRemainsPrimaryAndDistinctCleanupFailureIsSuppressed() {
        val probe = HeadlessProbe()
        val workFailure = IllegalStateException("paint failure")
        val cleanupFailure = IllegalArgumentException("detach failure")
        probe.paintFailure = workFailure
        probe.detachFailure = cleanupFailure

        val failure =
            assertThrows<IllegalStateException> {
                renderHeadless(HeadlessPrimitive(probe), IntSize(2, 2))
            }

        assertSame(workFailure, failure)
        assertEquals(1, failure.suppressed.size)
        assertSame(cleanupFailure, failure.suppressed.single())
        assertEquals(
            listOf(
                HeadlessLifecycleEvent.Attach,
                HeadlessLifecycleEvent.Detach,
                HeadlessLifecycleEvent.Dispose,
            ),
            probe.lifecycle,
        )
    }

    @Test
    fun sameInstanceCleanupFailureIsNotSelfSuppressed() {
        val probe = HeadlessProbe()
        val failure = IllegalStateException("shared failure")
        probe.paintFailure = failure
        probe.detachFailure = failure

        val thrown =
            assertThrows<IllegalStateException> {
                renderHeadless(HeadlessPrimitive(probe), IntSize(2, 2))
            }

        assertSame(failure, thrown)
        assertTrue(thrown.suppressed.none { suppressed -> suppressed === failure })
    }

    @Test
    fun closeOnlyFailureHasNoSuccessfulFrameAndPreservesExactThrowable() {
        val probe = HeadlessProbe()
        val failure = IllegalArgumentException("dispose failure")
        probe.disposeFailure = failure

        val thrown =
            assertThrows<IllegalArgumentException> {
                renderHeadless(HeadlessPrimitive(probe), IntSize(2, 2))
            }

        assertSame(failure, thrown)
        assertEquals(
            listOf(
                HeadlessLifecycleEvent.Attach,
                HeadlessLifecycleEvent.Detach,
                HeadlessLifecycleEvent.Dispose,
            ),
            probe.lifecycle,
        )
    }

    @Test
    fun successfulWorkWithDetachAndDisposeFailuresUsesExactCleanupGraph() {
        val probe = HeadlessProbe()
        val detachFailure = IllegalArgumentException("detach failure")
        val disposeFailure = UnsupportedOperationException("dispose failure")
        probe.detachFailure = detachFailure
        probe.disposeFailure = disposeFailure

        val thrown =
            assertThrows<IllegalArgumentException> {
                renderHeadless(HeadlessPrimitive(probe), IntSize(2, 2))
            }

        assertSame(detachFailure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(disposeFailure, thrown.suppressed.single())
        assertEquals(
            listOf(
                HeadlessLifecycleEvent.Attach,
                HeadlessLifecycleEvent.Detach,
                HeadlessLifecycleEvent.Dispose,
            ),
            probe.lifecycle,
        )
    }

    private data class InvalidRenderCase(
        val viewport: IntSize,
        val scale: Int,
        val message: String,
        val failureKind: FailureKind,
    )

    private enum class FailureKind {
        IllegalArgument,
        Arithmetic,
    }

    private fun semanticsLabel(value: String): Semantics = Semantics(label = UiText.Literal(value))
}
