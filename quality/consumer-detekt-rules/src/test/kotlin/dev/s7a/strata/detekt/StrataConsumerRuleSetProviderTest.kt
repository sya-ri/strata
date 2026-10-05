package dev.s7a.strata.detekt

import dev.detekt.api.RuleName
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/**
 * Verifies that the distributable plugin registers only consumer authoring rules.
 */
internal class StrataConsumerRuleSetProviderTest {
    @Test
    fun loadsOnlyTheConsumerProviderAndBothFactories() {
        val provider = ServiceLoader.load(RuleSetProvider::class.java).filterIsInstance<StrataConsumerRuleSetProvider>().single()
        val rules = provider.instance()
        assertEquals(RuleSetId("strata-consumer"), rules.id)
        assertEquals(setOf(RuleName("StateCreatedDuringComposition"), RuleName("UnusedComponentModifier")), rules.rules.keys)
    }
}
