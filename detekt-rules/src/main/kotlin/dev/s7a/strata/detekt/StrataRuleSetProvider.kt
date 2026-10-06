package dev.s7a.strata.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Provides opt-in Strata authoring checks without imposing the library's own source-style policies.
 */
public class StrataRuleSetProvider : RuleSetProvider {
    /**
     * Identifies Strata authoring rules separately from Strata's internal quality profile.
     */
    override val ruleSetId: RuleSetId = RuleSetId("strata")

    /**
     * Creates factories whose type-aware checks use Detekt's analysis classpath.
     */
    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf(
                RuleName("StateCreatedDuringComposition") to ::StateCreatedDuringComposition,
                RuleName("UnusedComponentModifier") to ::UnusedComponentModifier,
                RuleName("DiscardedModifier") to ::DiscardedModifier,
                RuleName("MultipleModifierApplications") to ::MultipleModifierApplications,
                RuleName("ParentDataOnWrongParent") to ::ParentDataOnWrongParent,
                RuleName("StateMutationDuringComposition") to ::StateMutationDuringComposition,
                RuleName("SubscriptionDuringComposition") to ::SubscriptionDuringComposition,
                RuleName("HostAccessDuringComposition") to ::HostAccessDuringComposition,
                RuleName("InvalidRootCount") to ::InvalidRootCount,
            ),
        )
}
