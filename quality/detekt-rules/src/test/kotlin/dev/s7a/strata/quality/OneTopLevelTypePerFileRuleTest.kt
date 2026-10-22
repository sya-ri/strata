package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the one-top-level-type file boundary.
 */
internal class OneTopLevelTypePerFileRuleTest {
    /**
     * Reports a file containing more than one named top-level type.
     */
    @Test
    internal fun reportsMultipleTopLevelTypes() {
        val source =
            DetektTestSources.read("one-top-level-type-per-file-rule/reports-multiple-top-level-types")

        assertEquals(1, OneTopLevelTypePerFileRule(Config.empty).lint(source).size)
    }

    /**
     * Allows one type and any number of nested types.
     */
    @Test
    internal fun allowsNestedTypes() {
        val source =
            DetektTestSources.read("one-top-level-type-per-file-rule/allows-nested-types")

        assertEquals(0, OneTopLevelTypePerFileRule(Config.empty).lint(source).size)
    }

    /**
     * Counts a typealias as a named top-level type.
     */
    @Test
    internal fun countsTopLevelTypeAliases() {
        val source =
            DetektTestSources.read("one-top-level-type-per-file-rule/counts-top-level-type-aliases")

        assertEquals(1, OneTopLevelTypePerFileRule(Config.empty).lint(source).size)
    }
}
