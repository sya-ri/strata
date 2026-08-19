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
        val equality = "=" + "="
        val inequality = "!" + "="
        val source =
            """
            fun check(state: String): Boolean {
                return state $equality "ready" || "failed" $inequality state || when (state) {
                    "ready" -> true
                    else -> false
                }
            }
            """.trimIndent()

        assertEquals(3, StringLiteralComparisonRule(Config.empty).lint(source).size)
    }

    /**
     * Ignores literal construction, map keys, annotations, and interpolated values.
     */
    @Test
    internal fun ignoresNonComparisonLiterals() {
        val source =
            """
            @Suppress("unused")
            fun build(value: String): Map<String, String> {
                val map = mapOf("ready" to value)
                val text = "state: ${'$'}value"
                return map + ("text" to text)
            }
            """.trimIndent()

        assertEquals(0, StringLiteralComparisonRule(Config.empty).lint(source).size)
    }
}
