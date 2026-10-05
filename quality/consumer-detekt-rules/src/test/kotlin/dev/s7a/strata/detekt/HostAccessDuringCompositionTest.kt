package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Keeps opening at integration boundaries without forbidding deferred navigation.
 */
internal class HostAccessDuringCompositionTest {
    @Test
    fun reportsCallsAndConstructorsFromTheConcreteRuntimeNamespace() {
        val findings =
            ConsumerRuleFixture.analyzePublic(
                HostAccessDuringComposition(Config.empty),
                """
                package dev.s7a.strata.runtime.fixture
                import dev.s7a.strata.component.UiScope
                class ConcreteRuntime { fun frame() {} }
                fun UiScope.invalid(runtime: ConcreteRuntime) { runtime.frame(); ConcreteRuntime() }
                fun integration(runtime: ConcreteRuntime) { runtime.frame(); ConcreteRuntime() }
                """.trimIndent(),
            )
        assertEquals(2, findings.size)
    }

    @Test
    fun reportsOpeningDuringEvaluation() {
        val findings =
            ConsumerRuleFixture.analyzePublic(
                HostAccessDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.ui.UiDefinition
                fun UiScope.invalid(next: UiDefinition) { next.open() }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun permitsIntegrationEventsAndPublicPrimitiveComposition() {
        val findings =
            ConsumerRuleFixture.analyzePublic(
                HostAccessDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.onPress
                import dev.s7a.strata.ui.UiDefinition
                fun integrate(next: UiDefinition) { next.open() }
                fun UiScope.valid(next: UiDefinition) {
                    Text("navigate", modifier = Modifier.Empty.onPress { next.open() })
                    val definition = UiDefinition { Text("future root") }
                }
                class Other { fun open() {} }
                fun UiScope.unrelated(other: Other) { other.open() }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
