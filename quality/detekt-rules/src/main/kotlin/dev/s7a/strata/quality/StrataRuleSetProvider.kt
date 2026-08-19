package dev.s7a.strata.quality

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Provides Strata's project-specific source quality rules to Detekt.
 */
public class StrataRuleSetProvider : RuleSetProvider {
    /**
     * Identifies the rule set in Detekt configuration and reports.
     */
    override val ruleSetId: RuleSetId = RuleSetId("strata")

    /**
     * Creates the immutable rule factory map used by each Detekt analysis.
     *
     * @return the Strata rule set.
     */
    override fun instance(): RuleSet =
        RuleSet(
            id = ruleSetId,
            rules =
                mapOf(
                    RuleName("NoNonNullAssertion") to ::NoNonNullAssertionRule,
                    RuleName("BodyQualifiedName") to ::BodyQualifiedNameRule,
                    RuleName("OneTopLevelTypePerFile") to ::OneTopLevelTypePerFileRule,
                    RuleName("MultilineKDoc") to ::MultilineKDocRule,
                    RuleName("BooleanPrefixNegation") to ::BooleanPrefixNegationRule,
                    RuleName("GreaterThanComparison") to ::GreaterThanComparisonRule,
                    RuleName("StringLiteralComparison") to ::StringLiteralComparisonRule,
                ),
        )
}
