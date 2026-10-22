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
        val source =
            DetektTestSources.read("boolean-prefix-negation-rule/reports-boolean-prefix-negation")

        assertEquals(4, BooleanPrefixNegationRule(Config.empty).lint(source).size)
    }

    /**
     * Leaves inequality and negated membership operators unchanged.
     */
    @Test
    internal fun ignoresRelatedOperators() {
        val source =
            DetektTestSources.read("boolean-prefix-negation-rule/ignores-related-operators")

        assertEquals(0, BooleanPrefixNegationRule(Config.empty).lint(source).size)
    }

    /**
     * Reports literal, type-check, equality, and membership expressions as Boolean operands.
     */
    @Test
    internal fun reportsBooleanExpressions() {
        val literalSource = DetektTestSources.read("boolean-prefix-negation-rule/reports-boolean-literals")
        val typeCheckSource = DetektTestSources.read("boolean-prefix-negation-rule/reports-boolean-type-check")
        val equalitySource = DetektTestSources.read("boolean-prefix-negation-rule/reports-boolean-equality")
        val membershipSource = DetektTestSources.read("boolean-prefix-negation-rule/reports-boolean-membership")

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
        val source =
            DetektTestSources.read("boolean-prefix-negation-rule/reports-gradle-script-boolean-negation")

        assertEquals(1, BooleanPrefixNegationRule(Config.empty).lint(source).size)
    }
}
