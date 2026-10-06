package dev.s7a.strata.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/**
 * Verifies that the distributable plugin registers only Strata authoring rules.
 */
internal class StrataRuleSetProviderTest {
    @Test
    fun loadsOnlyTheAuthoringProviderAndFactories() {
        val provider = ServiceLoader.load(RuleSetProvider::class.java).filterIsInstance<StrataRuleSetProvider>().single()
        val rules = provider.instance()
        assertEquals(RuleSetId("strata"), rules.id)
        assertEquals(
            setOf(
                "StateCreatedDuringComposition",
                "UnusedComponentModifier",
                "DiscardedModifier",
                "MultipleModifierApplications",
                "ParentDataOnWrongParent",
                "StateMutationDuringComposition",
                "SubscriptionDuringComposition",
                "HostAccessDuringComposition",
                "InvalidRootCount",
            ).map(::RuleName).toSet(),
            rules.rules.keys,
        )
    }
}
