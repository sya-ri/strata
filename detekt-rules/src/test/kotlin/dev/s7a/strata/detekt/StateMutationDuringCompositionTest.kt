package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Separates reevaluation writes from deferred events and unrelated application properties.
 */
internal class StateMutationDuringCompositionTest {
    @Test
    fun reportsResolvedAssignmentsAndMutationMethodsInImmediateLambdas() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                StateMutationDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.state.MutableState
                fun UiScope.invalid(state: MutableState<Int>, scroll: ScrollState, checkbox: CheckboxState) {
                    state.value = 1
                    state.value += 1
                    state.value++
                    --state.value
                    scroll.scrollTo(10.0)
                    listOf(1).forEach { checkbox.toggle() }
                }
                """.trimIndent(),
            )
        assertEquals(6, findings.size)
    }

    @Test
    fun permitsOwnerUpdatesDeferredActionsAndQueries() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                StateMutationDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.onPress
                import dev.s7a.strata.state.MutableState
                fun ownerUpdate(state: MutableState<Int>, scroll: ScrollState) {
                    state.value = 1
                    scroll.scrollTo(10.0)
                }
                class Other(var value: Int) { fun scrollTo(offset: Double) {} }
                fun UiScope.valid(state: MutableState<Int>, scroll: ScrollState, other: Other) {
                    Text(state.value.toString(), modifier = Modifier.Empty.onPress { state.value += 1; state.value++; scroll.scrollTo(0.0) })
                    val callback = { state.value = 2 }
                    other.value = 3
                    other.scrollTo(0.0)
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
