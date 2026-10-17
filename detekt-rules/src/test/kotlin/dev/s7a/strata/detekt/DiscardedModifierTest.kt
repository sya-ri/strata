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

    @Test
    fun permitsUsedConditionalAndTryResults() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                DiscardedModifier(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.padding
                fun UiScope.panel(enabled: Boolean, modifier: Modifier) {
                    val assigned = if (enabled) { modifier.padding(4) } else { modifier.padding(8) }
                    Column(modifier = assigned) { Text("assigned") }
                    Column(modifier = when (enabled) {
                        true -> { modifier.padding(4) }
                        false -> { modifier.padding(8) }
                    }) { Text("passed") }
                    val recovered = try { modifier.padding(4) } catch (error: Exception) { modifier.padding(8) }
                    Column(modifier = recovered) { Text("recovered") }
                }
                fun factory(enabled: Boolean, modifier: Modifier): Modifier =
                    if (enabled) { modifier.padding(4) } else { modifier.padding(8) }
                fun returned(enabled: Boolean, modifier: Modifier): Modifier {
                    return run { if (enabled) { modifier.padding(4) } else { modifier.padding(8) } }
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun reportsDiscardedBranchesNonterminalStatementsAndFinallyResults() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                DiscardedModifier(Config.empty),
                """
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.padding
                fun discarded(enabled: Boolean, modifier: Modifier) {
                    if (enabled) { modifier.padding(1) } else { modifier.padding(2) }
                    when (enabled) {
                        true -> { modifier.padding(3) }
                        false -> { modifier.padding(4) }
                    }
                    try { modifier.padding(5) } catch (error: Exception) { modifier.padding(6) }
                    val used = if (enabled) {
                        modifier.padding(7)
                        modifier.padding(8)
                    } else { modifier.padding(9) }
                    println(used)
                    val recovered = try { modifier.padding(10) } finally { modifier.padding(11) }
                    println(recovered)
                }
                """.trimIndent(),
            )
        assertEquals(8, findings.size)
    }

    @Test
    fun checksUnbracedBranchesAndUnitCallbacks() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                DiscardedModifier(Config.empty),
                """
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.padding
                fun discarded(enabled: Boolean, modifier: Modifier) {
                    if (enabled) modifier.padding(1) else modifier.padding(2)
                    val action: () -> Unit = { modifier.padding(3) }
                    action()
                    val used = if (enabled) modifier.padding(4) else modifier.padding(5)
                    println(used)
                }
                """.trimIndent(),
            )
        assertEquals(3, findings.size)
    }
}
