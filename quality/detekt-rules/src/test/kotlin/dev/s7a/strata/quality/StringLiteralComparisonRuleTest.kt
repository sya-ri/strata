package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies typed-state guidance for string comparisons and when branches.
 */
internal class StringLiteralComparisonRuleTest {
    /**
     * Reports equality, inequality, and when branches using plain string literals.
     */
    @Test
    internal fun reportsStringDiscrimination() {
        val source =
            DetektTestSources.read("string-literal-comparison-rule/reports-string-discrimination")

        assertEquals(3, StringLiteralComparisonRule(Config.empty).lint(source).size)
    }

    /**
     * Ignores literal construction, map keys, annotations, and interpolated values.
     */
    @Test
    internal fun ignoresNonComparisonLiterals() {
        val source =
            DetektTestSources.read("string-literal-comparison-rule/ignores-non-comparison-literals")

        assertEquals(0, StringLiteralComparisonRule(Config.empty).lint(source).size)
    }
}
