package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies explicit prefix-negation diagnostics without confusing related operators.
 */
internal class BooleanPrefixNegationRuleTest {
    /**
     * Reports Boolean values, parameters, calls, and expressions using prefix negation.
     */
    @Test
    internal fun reportsBooleanPrefixNegation() {
        val negation = "!"
        val source =
            """
            fun ready(): Boolean = true
            fun check(value: Boolean): Boolean {
                val other: Boolean = false
                return ${negation}value || ${negation}other || ${negation}ready() || $negation(value == other)
            }
            """.trimIndent()

        assertEquals(4, BooleanPrefixNegationRule(Config.empty).lint(source).size)
    }

    /**
     * Leaves inequality and negated membership operators unchanged.
     */
    @Test
    internal fun ignoresRelatedOperators() {
        val negation = "!"
        val inequality = negation + "="
        val source =
            """
            fun check(text: String, values: List<String>, value: Any): Boolean {
                return text $inequality "" || text !in values || value !is String
            }
            """.trimIndent()

        assertEquals(0, BooleanPrefixNegationRule(Config.empty).lint(source).size)
    }

    /**
     * Reports literal, type-check, equality, and membership expressions as Boolean operands.
     */
    @Test
    internal fun reportsBooleanExpressions() {
        val negation = "!"
        val literalSource = "fun check(): Boolean = ${negation}true || ${negation}false"
        val typeCheckSource = "fun check(value: Any): Boolean = $negation(value is String)"
        val equalitySource = "fun check(value: Any, other: Any): Boolean = $negation(value == other)"
        val membershipSource = "fun check(item: String, items: List<String>): Boolean = $negation(item in items)"

        assertEquals(2, BooleanPrefixNegationRule(Config.empty).lint(literalSource).size)
        assertEquals(1, BooleanPrefixNegationRule(Config.empty).lint(typeCheckSource).size)
        assertEquals(1, BooleanPrefixNegationRule(Config.empty).lint(equalitySource).size)
        assertEquals(1, BooleanPrefixNegationRule(Config.empty).lint(membershipSource).size)
    }

    /**
     * Reports the same rule in a Gradle Kotlin script-shaped input.
     */
    @Test
    internal fun reportsGradleScriptBooleanNegation() {
        val negation = "!"
        val source =
            """
            val enabled: Boolean = true
            val disabled = ${negation}enabled
            """.trimIndent()

        assertEquals(1, BooleanPrefixNegationRule(Config.empty).lint(source).size)
    }
}
