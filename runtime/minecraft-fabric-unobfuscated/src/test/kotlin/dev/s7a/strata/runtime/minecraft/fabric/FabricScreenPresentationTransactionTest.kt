package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.screen.ScreenOpenThreadException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies thread validation and exact cleanup behavior around native Fabric screen installation.
 */
internal class FabricScreenPresentationTransactionTest {
    @Test
    fun wrongThreadFailsBeforeScreenCreation() {
        var created = false

        assertThrows(ScreenOpenThreadException::class.java) {
            FabricScreenPresentationTransaction.present(
                { false },
                {
                    created = true
                    TestScreen()
                },
                { error("unused") },
            )
        }

        assertFalse(created)
    }

    @Test
    fun creationFailureRemainsExactAndDoesNotInventOwnership() {
        val expected = IllegalArgumentException("create")

        val actual =
            assertThrows(IllegalArgumentException::class.java) {
                FabricScreenPresentationTransaction.present<TestScreen>(
                    { true },
                    { throw expected },
                    { error("unused") },
                )
            }

        assertSame(expected, actual)
    }

    @Test
    fun installationFailureClosesTransferredScreen() {
        val screen = TestScreen()
        val expected = IllegalStateException("install")

        val actual =
            assertThrows(IllegalStateException::class.java) {
                FabricScreenPresentationTransaction.present(
                    { true },
                    { screen },
                    { throw expected },
                )
            }

        assertSame(expected, actual)
        assertTrue(screen.closed)
    }

    @Test
    fun cleanupFailureIsSuppressedUnderInstallationFailure() {
        val cleanup = IllegalArgumentException("cleanup")
        val screen = TestScreen(cleanup)
        val expected = IllegalStateException("install")

        val actual =
            assertThrows(IllegalStateException::class.java) {
                FabricScreenPresentationTransaction.present(
                    { true },
                    { screen },
                    { throw expected },
                )
            }

        assertSame(expected, actual)
        assertEquals(listOf(cleanup), actual.suppressed.toList())
    }

    @Test
    fun successfulInstallationLeavesScreenOpenForNativeLifecycle() {
        val screen = TestScreen()

        FabricScreenPresentationTransaction.present(
            { true },
            { screen },
            { installed -> assertSame(screen, installed) },
        )

        assertFalse(screen.closed)
    }

    private class TestScreen(
        private val closeFailure: Throwable? = null,
    ) : AutoCloseable {
        var closed: Boolean = false

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }
}
