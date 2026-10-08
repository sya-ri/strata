package dev.s7a.strata.gradle.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Exercises the actual fixture's detached pacing contract against the JDK alone; no loaded game or substitute policy is used.
 */
internal class MinecraftNativePerformancePacingTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun formalCollectionAcceptsMenuCapsAndExplicitLegacyUnavailability() {
        fixture().use { fixture ->
            val menu = fixture.snapshot(reason = Reason.OUT_OF_LEVEL_MENU, selected = 60, applied = 60)
            fixture.validate(menu)
            assertTrue(fixture.ready(menu))
            fixture.verifyStable(menu, menu)
            val legacy = fixture.snapshot(Inactivity.UNAVAILABLE, Reason.UNAVAILABLE, 60, 60, AppliedLimitSource.DIRECT_SELECTOR)
            fixture.validate(legacy)
            assertTrue(fixture.ready(legacy))
            assertThrows(IllegalStateException::class.java) { fixture.validate(legacy, Inactivity.AFK) }
        }
    }

    @Test
    fun selectedAndExtractedLimitersMustAgreeBeforeReadiness() {
        fixture().use { fixture ->
            val pending = fixture.snapshot(selected = 120, applied = 60)
            fixture.validate(pending)
            assertFalse(fixture.ready(pending))
            val applied = fixture.snapshot()
            assertTrue(fixture.ready(applied))
            assertThrows(IllegalStateException::class.java) { fixture.verifyStable(pending, applied) }
            listOf(0, -1).forEach { limit ->
                val invalid = fixture.snapshot(selected = limit, applied = limit)
                assertFalse(fixture.ready(invalid))
                assertThrows(IllegalStateException::class.java) { fixture.validate(invalid) }
            }
        }
    }

    @Test
    fun safetyThrottleAndIconifiedWindowsRemainRejectedInBothModes() {
        fixture().use { fixture ->
            listOf(Inactivity.MINIMIZED, Inactivity.AFK).forEach { mode ->
                val iconified = fixture.snapshot(mode, iconified = true)
                assertThrows(IllegalStateException::class.java) { fixture.validate(iconified, mode) }
                val safety = fixture.snapshot(mode, Reason.WINDOW_ICONIFIED, 10, 10)
                assertThrows(IllegalStateException::class.java) { fixture.validate(safety, mode) }
            }
        }
    }

    @Test
    fun afkCapsRemainDiagnosticAndCannotCertifyFormalCollection() {
        fixture().use { fixture ->
            listOf(Reason.SHORT_AFK to 30, Reason.LONG_AFK to 10).forEach { (reason, cap) ->
                val formal = fixture.snapshot(reason = reason, selected = cap, applied = cap)
                assertThrows(IllegalStateException::class.java) { fixture.validate(formal) }
                val diagnostic = fixture.snapshot(Inactivity.AFK, reason, cap, cap)
                fixture.validate(diagnostic, Inactivity.AFK)
                assertTrue(fixture.ready(diagnostic))
            }
            val changedOption = fixture.snapshot(Inactivity.AFK)
            assertThrows(IllegalStateException::class.java) { fixture.validate(changedOption) }
            assertThrows(IllegalStateException::class.java) { fixture.validate(fixture.snapshot(), Inactivity.AFK) }
        }
    }

    @Test
    fun formalStabilityIncludesReasonCapsWindowOptionAndObservationSource() {
        fixture().use { fixture ->
            val initial = fixture.snapshot()
            val changed =
                listOf(
                    fixture.snapshot(Inactivity.AFK),
                    fixture.snapshot(reason = Reason.OUT_OF_LEVEL_MENU),
                    fixture.snapshot(selected = 60, applied = 60),
                    fixture.snapshot(applied = 60),
                    fixture.snapshot(source = AppliedLimitSource.DIRECT_SELECTOR),
                    fixture.snapshot(iconified = true),
                )
            changed.forEach { snapshot ->
                assertThrows(IllegalStateException::class.java) { fixture.verifyStable(snapshot, initial) }
            }
            fixture.verifyStable(fixture.snapshot(), initial)
        }
    }

    @Test
    fun externalOptionDecodingDefaultsOnlyMissingValuesToFormalCollection() {
        fixture().use { fixture ->
            assertEquals(Inactivity.MINIMIZED.name, fixture.decode(null))
            assertEquals(Inactivity.MINIMIZED.name, fixture.decode("minimized"))
            assertEquals(Inactivity.AFK.name, fixture.decode("afk"))
            listOf("", "MINIMIZED", "unknown", "unavailable").forEach { value ->
                assertThrows(IllegalArgumentException::class.java) { fixture.decode(value) }
            }
        }
    }

    private fun fixture(): CompiledPacing = CompiledPacing(directory.compileNativePerformanceFixture("MinecraftNativePerformancePacing"))

    /**
     * Typed test values cross only the compiled helper's isolated enum boundary.
     */
    private enum class Inactivity {
        MINIMIZED,
        AFK,
        UNAVAILABLE,
    }

    /**
     * Only actual native reason names enter the fixture record; no cap-to-reason inference is tested.
     */
    private enum class Reason {
        NONE,
        WINDOW_ICONIFIED,
        LONG_AFK,
        SHORT_AFK,
        OUT_OF_LEVEL_MENU,
        UNAVAILABLE,
    }

    /**
     * The helper retains whether the limiter was independently extracted.
     */
    private enum class AppliedLimitSource {
        DIRECT_SELECTOR,
        GAME_RENDER_STATE,
    }

    /**
     * Owns the actual fixture class loader and unwraps its reflection failures without replacing their cause.
     */
    private class CompiledPacing(
        private val loader: URLClassLoader,
    ) : AutoCloseable {
        private val type = loader.loadClass("dev.s7a.strata.integration.minecraft.fabric.MinecraftNativePerformancePacing")
        private val inactivity = type.declaredClasses.single { it.simpleName.contentEquals("Inactivity") }
        private val reason = type.declaredClasses.single { it.simpleName.contentEquals("Reason") }
        private val source = type.declaredClasses.single { it.simpleName.contentEquals("AppliedLimitSource") }
        private val constructor = type.getDeclaredConstructor(inactivity, reason, checkNotNull(Int::class.javaPrimitiveType), checkNotNull(Int::class.javaPrimitiveType), source, checkNotNull(Boolean::class.javaPrimitiveType)).apply { isAccessible = true }
        private val validate = type.getDeclaredMethod("validate", inactivity).apply { isAccessible = true }
        private val ready = type.getDeclaredMethod("ready").apply { isAccessible = true }
        private val stable = type.getDeclaredMethod("verifyStable", type).apply { isAccessible = true }
        private val decode = inactivity.getDeclaredMethod("fromProperty", String::class.java).apply { isAccessible = true }

        /**
         * Constructs one actual detached observation with typed test inputs.
         */
        fun snapshot(
            mode: Inactivity = Inactivity.MINIMIZED,
            reason: Reason = Reason.NONE,
            selected: Int = 120,
            applied: Int = 120,
            source: AppliedLimitSource = AppliedLimitSource.GAME_RENDER_STATE,
            iconified: Boolean = false,
        ): Any = invoke { constructor.newInstance(enumValue(inactivity, mode), enumValue(this.reason, reason), selected, applied, enumValue(this.source, source), iconified) }

        /**
         * Applies the actual safety and option guard.
         */
        fun validate(
            snapshot: Any,
            mode: Inactivity = Inactivity.MINIMIZED,
        ) {
            invoke { validate.invoke(snapshot, enumValue(inactivity, mode)) }
        }

        /**
         * Reads the actual selected/applied agreement.
         */
        fun ready(snapshot: Any): Boolean = invoke { ready.invoke(snapshot) as Boolean }

        /**
         * Exercises the actual equality-based formal interval guard.
         */
        fun verifyStable(
            snapshot: Any,
            expected: Any,
        ) {
            invoke { stable.invoke(snapshot, expected) }
        }

        /**
         * Decodes an external property through the actual fixture boundary.
         */
        fun decode(value: String?): String = invoke { (decode.invoke(null, value) as Enum<*>).name }

        private fun enumValue(
            type: Class<*>,
            value: Enum<*>,
        ): Any = type.enumConstants.single { (it as Enum<*>).name.contentEquals(value.name) }

        private fun <T> invoke(action: () -> T): T =
            try {
                action()
            } catch (failure: InvocationTargetException) {
                throw checkNotNull(failure.cause)
            }

        override fun close() {
            loader.close()
        }
    }
}
