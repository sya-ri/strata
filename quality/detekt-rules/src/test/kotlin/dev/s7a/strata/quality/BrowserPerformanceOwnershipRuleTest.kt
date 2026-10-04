package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.core.config.YamlConfig
import dev.detekt.test.lint
import dev.detekt.utils.PathFilters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks browser ownership with the same untyped PSI analysis available for Kotlin/JS.
 */
internal class BrowserPerformanceOwnershipRuleTest {
    @Test
    fun clocksAliasesReferencesTimestampCallbacksAndRawInteropAreRejected() {
        val samples =
            mapOf(
                "fun read() = window.performance.now()" to 1,
                "fun read() { val clock = window.performance; clock.now() }" to 1,
                "fun read() = Date.now()" to 1,
                "fun read() = window.asDynamic()[\"performance\"].now()" to 1,
                "fun read() = window.asDynamic()[\"Date\"].now()" to 1,
                "import kotlin.js.Date as Clock\nfun read() = Clock.now()" to 1,
                "import kotlin.js.Date.now as clock\nfun read() = clock()" to 1,
                "import kotlin.time.TimeSource.Monotonic.markNow as clock\nfun read() = clock()" to 1,
                "import kotlin.time.measureTime as collect\nfun read() = collect { action() }" to 1,
                "import kotlin.time.*\nfun read() = 1" to 1,
                "fun read() = window.requestAnimationFrame { timestamp -> samples.add(timestamp) }" to 1,
                "fun read() = js(\"globalThis.performance.now()\")" to 1,
                "fun read() = clock::markNow" to 1,
                "fun read() = System.nanoTime()" to 1,
                "import java.lang.System.nanoTime as clock\nfun read() = clock()" to 1,
            )
        samples.forEach { (source, expected) -> assertEquals(expected, BrowserPerformanceOwnershipRule(Config.empty).lint(source).size, source) }
    }

    @Test
    fun sharedMeterDomAccessFixedInputsAndApplicationActionsRemainAllowed() {
        val source =
            """
            import dev.s7a.strata.performance.BrowserPerformanceMeter
            import kotlinx.browser.window
            fun run() = BrowserPerformanceMeter().measure { state.update() }
            fun host() = window.asDynamic().strataMeasurePerformance
            fun input() = InputClock().now()
            val label = "performance.now()"
            """.trimIndent()
        assertEquals(0, BrowserPerformanceOwnershipRule(Config.empty).lint(source).size)
    }

    @Test
    fun policyCoversNewBrowserHelpersAndExcludesTheCollectorAndProductionRuntime() {
        val config = Files.newBufferedReader(Path.of(checkNotNull(System.getProperty("strata.detekt.config")))).use { YamlConfig.load(it).subConfig("strata").subConfig("BrowserPerformanceOwnership") }
        assertTrue(config.valueOrDefault("active", false))
        val filters = checkNotNull(PathFilters.of(config.valueOrDefault("includes", emptyList()), config.valueOrDefault("excludes", emptyList())))
        assertFalse(filters.isIgnored(Path.of("repository/integration/web/src/jsMain/kotlin/NewHelper.kt")))
        assertFalse(filters.isIgnored(Path.of("repository/integration/shared/minecraft-fabric/scenarios/gui-extractor/src/gametest/kotlin/NewHelper.kt")))
        assertTrue(filters.isIgnored(Path.of("repository/quality/performance-testkit/src/jsMain/kotlin/Meter.kt")))
        assertTrue(filters.isIgnored(Path.of("repository/runtime/web/src/jsMain/kotlin/Animation.kt")))
    }
}
