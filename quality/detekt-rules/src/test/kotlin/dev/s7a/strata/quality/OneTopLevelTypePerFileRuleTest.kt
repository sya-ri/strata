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
            """
            class First
            interface Second
            """.trimIndent()

        assertEquals(1, OneTopLevelTypePerFileRule(Config.empty).lint(source).size)
    }

    /**
     * Allows one type and any number of nested types.
     */
    @Test
    internal fun allowsNestedTypes() {
        val source =
            """
            class First {
                class Nested
            }
            """.trimIndent()

        assertEquals(0, OneTopLevelTypePerFileRule(Config.empty).lint(source).size)
    }

    /**
     * Counts a typealias as a named top-level type.
     */
    @Test
    internal fun countsTopLevelTypeAliases() {
        val source =
            """
            typealias First = String
            class Second
            """.trimIndent()

        assertEquals(1, OneTopLevelTypePerFileRule(Config.empty).lint(source).size)
    }
}
