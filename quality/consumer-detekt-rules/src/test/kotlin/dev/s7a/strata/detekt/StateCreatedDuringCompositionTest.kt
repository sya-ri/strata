package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Checks agreed state ownership examples and nearby valid callbacks and factories.
 */
internal class StateCreatedDuringCompositionTest {
    @Test
    fun followsImmediateCollectionLambdasButNotDeferredOrLazyCallbacks() {
        val findings =
            ConsumerRuleFixture.analyzePublic(
                StateCreatedDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.UiScope
                import dev.s7a.strata.component.ScrollState
                fun UiScope.panel() {
                    listOf(1).forEach { ScrollState() }
                    run { ScrollState() }
                    val deferred = { ScrollState() }
                    val lazy = sequenceOf(1).map { ScrollState() }
                }
                """.trimIndent(),
            )
        assertEquals(2, findings.size)
    }

    @Test
    fun checksControlStatesBeyondTheOriginalScrollingAndEditingCases() {
        val findings =
            ConsumerRuleFixture.analyze(
                StateCreatedDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.UiScope
                import dev.s7a.strata.component.CheckboxState
                import dev.s7a.strata.component.CycleButtonState
                import dev.s7a.strata.component.SliderState
                import dev.s7a.strata.component.SelectionListState
                fun UiScope.controls() {
                    CheckboxState()
                    CycleButtonState()
                    SliderState()
                    SelectionListState()
                }
                class Owner {
                    val checkbox = CheckboxState()
                    val cycle = CycleButtonState()
                    val slider = SliderState()
                    val selection = SelectionListState()
                }
                """.trimIndent(),
            )
        assertEquals(4, findings.size)
    }

    @Test
    fun rejectsResolvedStateCreationInFunctionsContentLambdasAndDerivedScopes() {
        val findings =
            ConsumerRuleFixture.analyze(
                StateCreatedDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.UiScope
                import dev.s7a.strata.component.ScrollState as Position
                import dev.s7a.strata.component.Row
                import dev.s7a.strata.component.screen
                import dev.s7a.strata.state.mutableStateOf as state
                import dev.s7a.strata.state.map
                fun UiScope.panel() {
                    val scroll = Position()
                    val value = state(1)
                    val display = value.map { it.toString() }
                }
                fun create() = screen { Row { val scroll = Position() } }
                """.trimIndent(),
            )
        assertEquals(4, findings.size)
    }

    @Test
    fun allowsCallerOwnedStateDeferredActionsAndUnrelatedFactories() {
        val findings =
            ConsumerRuleFixture.analyze(
                StateCreatedDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.UiScope
                import dev.s7a.strata.component.ScrollState
                import dev.s7a.strata.component.action
                val retained = ScrollState()
                fun create() = ScrollState()
                class OtherScope
                fun OtherScope.panel() = ScrollState()
                class Local { fun ScrollState() = 1
                    fun UiScope.panel() { val local = ScrollState() }
                }
                fun UiScope.panel(scroll: ScrollState) {
                    action { val next = ScrollState() }
                    fun reset() = ScrollState()
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
