package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/**
 * Verifies service registration and the public rule-set factory contract.
 */
internal class StrataRuleSetProviderTest {
    /**
     * Loads the provider through its service descriptor and checks every rule factory.
     */
    @Test
    internal fun loadsProviderAndExposesExpectedRules() {
        val providers = ServiceLoader.load(RuleSetProvider::class.java).toList()
        val provider = providers.filterIsInstance<StrataRuleSetProvider>().single()
        val ruleSet = provider.instance()

        assertEquals(RuleSetId("strata"), ruleSet.id)
        assertEquals(
            setOf(
                RuleName("NoNonNullAssertion"),
                RuleName("BodyQualifiedName"),
                RuleName("OneTopLevelTypePerFile"),
                RuleName("MultilineKDoc"),
                RuleName("BooleanPrefixNegation"),
                RuleName("GreaterThanComparison"),
                RuleName("StringLiteralComparison"),
            ),
            ruleSet.rules.keys,
        )
        ruleSet.rules.values.forEach { factory ->
            factory.invoke(Config.empty)
        }
    }
}
