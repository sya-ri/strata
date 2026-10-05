package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Uses captured outer scopes to create compilable but incorrectly placed parent data.
 */
internal class ParentDataOnWrongParentTest {
    @Test
    fun reportsDataBelowWrongLayoutsAndManualObservedRegion() {
        val findings =
            ConsumerRuleFixture.analyzePublic(
                ParentDataOnWrongParent(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.layout.VerticalAlignment
                import dev.s7a.strata.state.StateSource
                fun UiScope.invalid(source: StateSource<Int>) {
                    Row {
                        val row = this
                        Stack { Text("wrong weight", modifier = row.run { Modifier.Empty.weight(1f) }) }
                        Column { Text("wrong alignment", modifier = row.run { Modifier.Empty.align(VerticalAlignment.Center) }) }
                        Observe(source) { Text("wrong region", modifier = row.run { Modifier.Empty.weight(1f) }) }
                    }
                }
                """.trimIndent(),
            )
        assertEquals(3, findings.size)
    }

    @Test
    fun permitsDirectChildrenAndLeavesUnknownForwardingToReview() {
        val findings =
            ConsumerRuleFixture.analyzePublic(
                ParentDataOnWrongParent(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.layout.VerticalAlignment
                import dev.s7a.strata.state.StateSource
                fun UiScope.valid(source: StateSource<String>) {
                    Row {
                        Text("direct", modifier = Modifier.Empty.weight(1f).align(VerticalAlignment.Center))
                        Text(source, modifier = Modifier.Empty.weight(1f))
                    Observe(source, modifier = Modifier.Empty.weight(1f)) { Text(it) }
                    val row = this
                    Stack { row.Text("explicit outer child", modifier = row.run { Modifier.Empty.weight(1f) }) }
                    }
                }
                fun RowScope.helper(modifier: Modifier) { Text("review helper", modifier = modifier.weight(1f)) }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
