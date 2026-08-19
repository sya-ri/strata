package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies greater-than diagnostics without confusing syntax outside expressions.
 */
internal class GreaterThanComparisonRuleTest {
    /**
     * Reports strict and inclusive greater-than comparisons.
     */
    @Test
    internal fun reportsGreaterThanComparisons() {
        val greaterThan = ">"
        val greaterThanOrEqual = greaterThan + "="
        val source =
            """
            fun compare(left: Int, right: Int): Boolean = left $greaterThan right || left $greaterThanOrEqual right
            """.trimIndent()

        assertEquals(2, GreaterThanComparisonRule(Config.empty).lint(source).size)
    }

    /**
     * Preserves less-than, equality, delimiters, strings, and documentation.
     */
    @Test
    internal fun ignoresOtherSyntax() {
        val greaterThan = ">"
        val source =
            """
            /** The text `left $greaterThan right` is documentation, not code. */
            fun compare(left: Int, right: Int): Boolean = left < right && left <= right && left == right
            val text = "left $greaterThan right"
            val values = listOf(1, 2)
            val generic = values.filter { it < 3 }
            """.trimIndent()

        assertEquals(0, GreaterThanComparisonRule(Config.empty).lint(source).size)
    }

    /**
     * Reports side-effectful comparisons with guidance instead of changing evaluation order.
     */
    @Test
    internal fun preservesSideEffectGuidance() {
        val greaterThan = ">"
        val source =
            """
            fun compare(next: () -> Int, current: () -> Int): Boolean = next() $greaterThan current()
            """.trimIndent()

        val findings = GreaterThanComparisonRule(Config.empty).lint(source)
        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("side-effect"))
    }
}
