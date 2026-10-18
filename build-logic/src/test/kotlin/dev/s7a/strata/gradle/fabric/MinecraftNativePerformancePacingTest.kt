package dev.s7a.strata.gradle.fabric

import dev.s7a.strata.integration.minecraft.fabric.MinecraftNativePerformancePacing
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNativePerformancePacing.AppliedLimitSource
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNativePerformancePacing.Inactivity
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNativePerformancePacing.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the actual game-independent fixture pacing contract without a loaded game or substitute policy.
 */
internal class MinecraftNativePerformancePacingTest {
    @Test
    fun formalCollectionAcceptsMenuCapsAndExplicitLegacyUnavailability() {
        val menu = snapshot(reason = Reason.OUT_OF_LEVEL_MENU, selected = 60, applied = 60)
        validate(menu)
        assertTrue(ready(menu))
        verifyStable(menu, menu)
        val legacy = snapshot(Inactivity.UNAVAILABLE, Reason.UNAVAILABLE, 60, 60, AppliedLimitSource.DIRECT_SELECTOR)
        validate(legacy)
        assertTrue(ready(legacy))
        assertThrows(IllegalStateException::class.java) { validate(legacy, Inactivity.AFK) }
    }

    @Test
    fun selectedAndExtractedLimitersMustAgreeBeforeReadiness() {
        val pending = snapshot(selected = 120, applied = 60)
        validate(pending)
        assertFalse(ready(pending))
        val applied = snapshot()
        assertTrue(ready(applied))
        assertThrows(IllegalStateException::class.java) { verifyStable(pending, applied) }
        listOf(0, -1).forEach { limit ->
            val invalid = snapshot(selected = limit, applied = limit)
            assertFalse(ready(invalid))
            assertThrows(IllegalStateException::class.java) { validate(invalid) }
        }
    }

    @Test
    fun safetyThrottleAndIconifiedWindowsRemainRejectedInBothModes() {
        listOf(Inactivity.MINIMIZED, Inactivity.AFK).forEach { mode ->
            val iconified = snapshot(mode, iconified = true)
            assertThrows(IllegalStateException::class.java) { validate(iconified, mode) }
            val safety = snapshot(mode, Reason.WINDOW_ICONIFIED, 10, 10)
            assertThrows(IllegalStateException::class.java) { validate(safety, mode) }
        }
    }

    @Test
    fun afkCapsRemainDiagnosticAndCannotCertifyFormalCollection() {
        listOf(Reason.SHORT_AFK to 30, Reason.LONG_AFK to 10).forEach { (reason, cap) ->
            val formal = snapshot(reason = reason, selected = cap, applied = cap)
            assertThrows(IllegalStateException::class.java) { validate(formal) }
            val diagnostic = snapshot(Inactivity.AFK, reason, cap, cap)
            validate(diagnostic, Inactivity.AFK)
            assertTrue(ready(diagnostic))
        }
        val changedOption = snapshot(Inactivity.AFK)
        assertThrows(IllegalStateException::class.java) { validate(changedOption) }
        assertThrows(IllegalStateException::class.java) { validate(snapshot(), Inactivity.AFK) }
    }

    @Test
    fun formalStabilityIncludesReasonCapsWindowOptionAndObservationSource() {
        val initial = snapshot()
        val changed =
            listOf(
                snapshot(Inactivity.AFK),
                snapshot(reason = Reason.OUT_OF_LEVEL_MENU),
                snapshot(selected = 60, applied = 60),
                snapshot(applied = 60),
                snapshot(source = AppliedLimitSource.DIRECT_SELECTOR),
                snapshot(iconified = true),
            )
        changed.forEach { snapshot ->
            assertThrows(IllegalStateException::class.java) { verifyStable(snapshot, initial) }
        }
        verifyStable(snapshot(), initial)
    }

    @Test
    fun externalOptionDecodingDefaultsOnlyMissingValuesToFormalCollection() {
        assertEquals(Inactivity.MINIMIZED.name, decode(null))
        assertEquals(Inactivity.MINIMIZED.name, decode("minimized"))
        assertEquals(Inactivity.AFK.name, decode("afk"))
        listOf("", "MINIMIZED", "unknown", "unavailable").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { decode(value) }
        }
    }

    private fun snapshot(
        mode: Inactivity = Inactivity.MINIMIZED,
        reason: Reason = Reason.NONE,
        selected: Int = 120,
        applied: Int = 120,
        source: AppliedLimitSource = AppliedLimitSource.GAME_RENDER_STATE,
        iconified: Boolean = false,
    ): MinecraftNativePerformancePacing = MinecraftNativePerformancePacing(mode, reason, selected, applied, source, iconified)

    private fun validate(
        snapshot: MinecraftNativePerformancePacing,
        mode: Inactivity = Inactivity.MINIMIZED,
    ) = snapshot.validate(mode)

    private fun ready(snapshot: MinecraftNativePerformancePacing): Boolean = snapshot.ready()

    private fun verifyStable(
        snapshot: MinecraftNativePerformancePacing,
        expected: MinecraftNativePerformancePacing,
    ) = snapshot.verifyStable(expected)

    private fun decode(value: String?): String = Inactivity.fromProperty(value).name
}
