package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Distinguishes manual subscription acquisition from managed observation and deferred callbacks.
 */
internal class SubscriptionDuringCompositionTest {
    @Test
    fun reportsSourceSubscriptionsAndStandardStateObservers() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                SubscriptionDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.state.*
                class Wrapped(source: StateSource<Int>) : StateSource<Int> by source
                @OptIn(dev.s7a.strata.spi.InternalStrataRuntimeApi::class)
                fun UiScope.invalid(source: StateSource<Int>, state: Wrapped, scroll: ScrollState) {
                    source.subscribe { }
                    state.subscribe { }
                    scroll.observe { }
                }
                """.trimIndent(),
            )
        assertEquals(3, findings.size)
    }

    @Test
    fun permitsManagedBindingsOwnerSubscriptionsAndUnrelatedMethods() {
        val findings =
            AuthoringRuleFixture.analyzePublic(
                SubscriptionDuringComposition(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.state.*
                class Other { fun subscribe(callback: () -> Unit) {} }
                fun owner(source: StateSource<Int>) = source.subscribe { }
                fun UiScope.valid(source: StateSource<String>, other: Other) {
                    Text(source)
                    Observe(source) { Text(it) }
                    other.subscribe { }
                    val callback = { source.subscribe { } }
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
