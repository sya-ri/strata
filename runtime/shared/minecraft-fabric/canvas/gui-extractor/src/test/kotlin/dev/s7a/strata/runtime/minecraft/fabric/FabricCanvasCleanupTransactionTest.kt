package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies queue-discard and terminal shutdown ordering without loading a native client or GPU.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricCanvasCleanupTransactionTest {
    @Test
    fun shutdownStopsCallbacksBeforeDiscardCompletionAndVanillaClose() {
        val trace = mutableListOf<Step>()

        FabricCanvasShutdownTransaction.run(
            { trace += Step.StopScreen },
            { trace += Step.DiscardGui },
            { trace += Step.ReleaseDevice },
            { trace += Step.CloseVanilla },
        )

        assertEquals(Step.entries, trace)
    }

    @Test
    fun discardFailureKeepsDeviceQuarantinedAndStillClosesVanilla() {
        val trace = mutableListOf<Step>()
        val discardFailure = IllegalStateException("discard")

        val actual =
            assertThrows(IllegalStateException::class.java) {
                FabricCanvasShutdownTransaction.run(
                    { trace += Step.StopScreen },
                    {
                        trace += Step.DiscardGui
                        throw discardFailure
                    },
                    { trace += Step.ReleaseDevice },
                    { trace += Step.CloseVanilla },
                )
            }

        assertSame(discardFailure, actual)
        assertEquals(listOf(Step.StopScreen, Step.DiscardGui, Step.CloseVanilla), trace)
    }

    @Test
    fun originalVanillaFailureRemainsPrimaryAfterIndependentCleanupFailures() {
        val trace = mutableListOf<Step>()
        val screenFailure = IllegalStateException("screen")
        val releaseFailure = IllegalArgumentException("release")
        val vanillaFailure = UnsupportedOperationException("vanilla")

        val actual =
            assertThrows(UnsupportedOperationException::class.java) {
                FabricCanvasShutdownTransaction.run(
                    {
                        trace += Step.StopScreen
                        throw screenFailure
                    },
                    { trace += Step.DiscardGui },
                    {
                        trace += Step.ReleaseDevice
                        throw releaseFailure
                    },
                    {
                        trace += Step.CloseVanilla
                        throw vanillaFailure
                    },
                )
            }

        assertEquals(Step.entries, trace)
        assertSame(vanillaFailure, actual)
        assertSame(screenFailure, actual.suppressed.single())
        assertSame(releaseFailure, screenFailure.suppressed.single())
    }

    @Test
    fun firstCleanupFailureRemainsPrimaryWhenVanillaSucceeds() {
        val trace = mutableListOf<Step>()
        val screenFailure = IllegalStateException("screen")
        val discardFailure = IllegalArgumentException("discard")

        val actual =
            assertThrows(IllegalStateException::class.java) {
                FabricCanvasShutdownTransaction.run(
                    {
                        trace += Step.StopScreen
                        throw screenFailure
                    },
                    {
                        trace += Step.DiscardGui
                        throw discardFailure
                    },
                    { trace += Step.ReleaseDevice },
                    { trace += Step.CloseVanilla },
                )
            }

        assertSame(screenFailure, actual)
        assertSame(discardFailure, actual.suppressed.single())
        assertEquals(listOf(Step.StopScreen, Step.DiscardGui, Step.CloseVanilla), trace)
    }

    @Test
    fun terminalDeviceFailureStillReachesVanillaAndIsNotReportedAsSuccess() {
        val releaseFailure = IllegalStateException("release")
        var vanillaCloses = 0

        val actual =
            assertThrows(IllegalStateException::class.java) {
                FabricCanvasShutdownTransaction.run(
                    {},
                    {},
                    { throw releaseFailure },
                    { vanillaCloses += 1 },
                )
            }

        assertSame(releaseFailure, actual)
        assertEquals(1, vanillaCloses)
    }

    @Test
    fun independentQueueCleanupAttemptsEveryActionAndPreservesFailureIdentity() {
        val first = AssertionError("first queue cleanup")
        val second = IllegalStateException("second queue cleanup")
        var attempts = 0

        val actual =
            assertThrows(AssertionError::class.java) {
                FabricCanvasGuiCleanup.run(
                    {
                        attempts += 1
                        throw first
                    },
                    {
                        attempts += 1
                        throw second
                    },
                    { attempts += 1 },
                )
            }

        assertSame(first, actual)
        assertSame(second, actual.suppressed.single())
        assertEquals(3, attempts)
    }

    @Test
    fun nativeDrawingFailureRemainsPrimaryWhenScissorRestorationAlsoFails() {
        val drawing = AssertionError("native drawing")
        val restoration = IllegalStateException("scissor restoration")
        var restores = 0

        val actual =
            assertThrows(AssertionError::class.java) {
                FabricMinecraftFailures.runWithCleanup(
                    { throw drawing },
                    {
                        restores += 1
                        throw restoration
                    },
                )
            }

        assertSame(drawing, actual)
        assertSame(restoration, actual.suppressed.single())
        assertEquals(1, restores)
    }

    @Test
    fun scissorRestorationFailureEscapesAfterSuccessfulNativeDrawing() {
        val restoration = IllegalStateException("scissor restoration")
        var draws = 0
        var restores = 0

        val actual =
            assertThrows(IllegalStateException::class.java) {
                FabricMinecraftFailures.runWithCleanup(
                    { draws += 1 },
                    {
                        restores += 1
                        throw restoration
                    },
                )
            }

        assertSame(restoration, actual)
        assertTrue(actual.suppressed.isEmpty())
        assertEquals(1, draws)
        assertEquals(1, restores)
    }

    @Test
    fun meshReferencesAreRemovedBeforeAnyCloseAndAllIndependentClosesAreAttempted() {
        val meshes = mutableListOf<AutoCloseable>()
        val first = IllegalStateException("first mesh")
        val second = IllegalArgumentException("second mesh")
        var closes = 0
        meshes +=
            AutoCloseable {
                assertTrue(meshes.isEmpty())
                closes += 1
                throw first
            }
        meshes +=
            AutoCloseable {
                assertTrue(meshes.isEmpty())
                closes += 1
                throw second
            }

        val actual =
            assertThrows(IllegalStateException::class.java) {
                FabricCanvasGuiCleanup.closeMeshes(meshes)
            }

        assertSame(first, actual)
        assertSame(second, actual.suppressed.single())
        assertEquals(2, closes)
        assertTrue(meshes.isEmpty())
        FabricCanvasGuiCleanup.closeMeshes(meshes)
        assertEquals(2, closes)
    }

    private enum class Step {
        StopScreen,
        DiscardGui,
        ReleaseDevice,
        CloseVanilla,
    }
}
