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
        val source =
            DetektTestSources.read("greater-than-comparison-rule/reports-greater-than-comparisons")

        assertEquals(2, GreaterThanComparisonRule(Config.empty).lint(source).size)
    }

    /**
     * Preserves less-than, equality, delimiters, strings, and documentation.
     */
    @Test
    internal fun ignoresOtherSyntax() {
        val source =
            DetektTestSources.read("greater-than-comparison-rule/ignores-other-syntax")

        assertEquals(0, GreaterThanComparisonRule(Config.empty).lint(source).size)
    }

    /**
     * Reports side-effectful comparisons with guidance instead of changing evaluation order.
     */
    @Test
    internal fun preservesSideEffectGuidance() {
        val source =
            DetektTestSources.read("greater-than-comparison-rule/preserves-side-effect-guidance")

        val findings = GreaterThanComparisonRule(Config.empty).lint(source)
        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("side-effect"))
    }
}
