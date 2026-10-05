package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Checks discarded caller modifiers without treating a shadowed name as a parameter read.
 */
internal class UnusedComponentModifierTest {
    @Test
    fun rejectsUnusedAndShadowedResolvedModifierParameters() {
        val findings =
            ConsumerRuleFixture.analyze(
                UnusedComponentModifier(Config.empty),
                """
                import dev.s7a.strata.component.UiScope
                import dev.s7a.strata.modifier.Modifier as Decoration
                fun UiScope.panel(decoration: Decoration = Decoration.Empty) { println("panel") }
                fun UiScope.shadow(modifier: Decoration) {
                    run { val modifier = "unrelated"; println(modifier) }
                }
                """.trimIndent(),
            )
        assertEquals(2, findings.size)
    }

    @Test
    fun allowsForwardingDifferentTypesAndNonCompositionFunctions() {
        val findings =
            ConsumerRuleFixture.analyze(
                UnusedComponentModifier(Config.empty),
                """
                import dev.s7a.strata.component.UiScope
                import dev.s7a.strata.component.RowScope
                import dev.s7a.strata.modifier.Modifier
                fun root(modifier: Modifier) {}
                fun UiScope.panel(modifier: Modifier = Modifier.Empty) { root(modifier) }
                fun RowScope.cell(modifier: Modifier) { root(modifier) }
                fun integration(modifier: Modifier) {}
                class OtherModifier
                fun UiScope.helper(modifier: OtherModifier) {}
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
