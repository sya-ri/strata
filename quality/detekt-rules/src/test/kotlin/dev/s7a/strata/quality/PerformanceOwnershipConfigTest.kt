package dev.s7a.strata.quality

import dev.detekt.core.config.YamlConfig
import dev.detekt.rules.style.ForbiddenMethodCall
import dev.detekt.test.utils.KotlinAnalysisApiEngine
import dev.detekt.utils.PathFilters
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises the repository's standard typed prohibition against actual JDK calls and method references.
 */
internal class PerformanceOwnershipConfigTest {
    @Test
    fun clocksAndAccountingCannotBecomeIndependentJmhCollectors() {
        val config = Files.newBufferedReader(Path.of(checkNotNull(System.getProperty("strata.detekt.config")))).use { reader -> YamlConfig.load(reader).subConfig("style").subConfig("ForbiddenMethodCall") }
        assertTrue(config.valueOrDefault("active", false))
        val filters = checkNotNull(PathFilters.of(config.valueOrDefault("includes", emptyList()), config.valueOrDefault("excludes", emptyList())))
        assertFalse(filters.isIgnored(Path.of("repository/quality/new-feature/src/jmh/kotlin/NewWorkload.kt")))
        assertFalse(filters.isIgnored(Path.of("repository/integration/paper/src/main/kotlin/Helper.kt")))
        assertFalse(filters.isIgnored(Path.of("repository/integration/velocity/src/main/kotlin/Helper.kt")))
        assertTrue(filters.isIgnored(Path.of("repository/quality/performance-testkit/src/jvmMain/kotlin/Meter.kt")))
        KotlinAnalysisApiEngine().use { engine ->
            val source =
                engine.compile(
                    code =
                        """
                        import java.lang.management.ManagementFactory
                        fun clocks() {
                            System.nanoTime()
                            System.currentTimeMillis()
                            val clock = System::nanoTime
                            clock()
                            ManagementFactory.getThreadMXBean().currentThreadCpuTime
                            ManagementFactory.getGarbageCollectorMXBeans()
                        }
                        """.trimIndent(),
                )
            assertEquals(6, ForbiddenMethodCall(config).visitFile(source, LanguageVersionSettingsImpl.DEFAULT).size)
            val allowed =
                engine.compile(
                    code =
                        """
                        import java.lang.management.ManagementFactory
                        class InputClock { fun nanoTime(): Long = 1 }
                        fun input() = InputClock().nanoTime()
                        fun configuration() = ManagementFactory.getRuntimeMXBean().inputArguments
                        """.trimIndent(),
                )
            assertEquals(0, ForbiddenMethodCall(config).visitFile(allowed, LanguageVersionSettingsImpl.DEFAULT).size)
        }
    }
}
