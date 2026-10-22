package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the non-null assertion rule's operator and literal handling.
 */
internal class NoNonNullAssertionRuleTest {
    /**
     * Reports each non-null assertion in production-shaped source.
     */
    @Test
    internal fun reportsNonNullAssertions() {
        val source =
            DetektTestSources.read("no-non-null-assertion-rule/reports-non-null-assertions")

        assertEquals(1, NoNonNullAssertionRule(Config.empty).lint(source).size)
    }

    /**
     * Ignores safe calls and non-nullable values.
     */
    @Test
    internal fun ignoresSafeCode() {
        val source =
            DetektTestSources.read("no-non-null-assertion-rule/ignores-safe-code")

        assertEquals(0, NoNonNullAssertionRule(Config.empty).lint(source).size)
    }
}
