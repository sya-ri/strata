package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Checks only proven root counts and preserves multi-child layouts and empty observed regions.
 */
internal class InvalidRootCountTest {
    @Test
    fun reportsEmptyDefinitionsAndFlatSiblingRoots() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                InvalidRootCount(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.ui.UiDefinition as Definition
                import dev.s7a.strata.state.StateSource
                fun invalid(source: StateSource<Int>) {
                    Definition { }
                    Definition { Text("one"); Text("two") }
                    Definition { Observe(source) { Text("one"); Text("two") } }
                }
                """.trimIndent(),
            )
        assertEquals(3, findings.size)
    }

    @Test
    fun permitsIntentionalLayoutEmptyRegionBranchesAndUnknownHelpers() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                InvalidRootCount(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.ui.UiDefinition
                import dev.s7a.strata.state.StateSource
                fun UiScope.helper() { Text("helper root") }
                fun valid(source: StateSource<Int>, ready: Boolean) {
                    UiDefinition { Column { Text("one"); Text("two") } }
                    UiDefinition { Observe(source) { } }
                    UiDefinition { if (ready) Text("ready") else Text("loading") }
                    UiDefinition { helper() }
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
