package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies immutable-chain use against real extension symbols and lambda return types.
 */
internal class DiscardedModifierTest {
    @Test
    fun reportsOnlyTerminalDiscardedOperations() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                DiscardedModifier(Config.empty),
                """
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.padding as inset
                import dev.s7a.strata.modifier.height
                fun discarded(modifier: Modifier) {
                    modifier.inset(2)
                    modifier.inset(2).height(20)
                }
                """.trimIndent(),
            )
        assertEquals(2, findings.size)
    }

    @Test
    fun permitsReturnedAssignedPassedAndNonModifierResults() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                DiscardedModifier(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.padding
                fun factory(modifier: Modifier): Modifier = modifier.padding(2)
                fun other(modifier: Modifier): Modifier { return modifier.padding(2) }
                fun UiScope.valid(modifier: Modifier) {
                    val assigned = modifier.padding(2)
                    Text("used", modifier = assigned)
                    Text("passed", modifier = modifier.padding(2))
                    modifier.padding(2).toString()
                }
                fun lambda(modifier: Modifier): Modifier = run { modifier.padding(2) }
                class Other { fun padding(size: Int) {} }
                fun unrelated(other: Other) { other.padding(2) }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
