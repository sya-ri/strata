package dev.s7a.strata.detekt

import dev.detekt.api.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Checks caller-modifier forwarding without banning mutually exclusive component roots.
 */
internal class MultipleModifierApplicationsTest {
    @Test
    fun reportsRootChildAndImmutableAliasReuse() {
        val findings =
            ConsumerRuleFixture.analyzePublic(
                MultipleModifierApplications(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.padding
                fun UiScope.rootAndChild(modifier: Modifier) {
                    Column(modifier = modifier) { Text("child", modifier = modifier) }
                }
                fun UiScope.expressionBody(modifier: Modifier) = Column(modifier = modifier) { Text("child", modifier = modifier) }
                fun UiScope.aliases(modifier: Modifier) {
                    val first = modifier.padding(2)
                    val second = first
                    Text("one", modifier = first)
                    Text("two", modifier = second)
                }
                fun UiScope.forward(modifier: Modifier) { Text("forwarded", modifier = modifier) }
                fun UiScope.helpers(modifier: Modifier) {
                    forward(modifier)
                    forward(modifier)
                }
                """.trimIndent(),
            )
        assertEquals(4, findings.size)
    }

    @Test
    fun permitsExclusiveRootsIndependentChildrenAndValueHelpers() {
        val findings =
            ConsumerRuleFixture.analyzePublic(
                MultipleModifierApplications(Config.empty),
                """
                import dev.s7a.strata.component.*
                import dev.s7a.strata.modifier.Modifier
                import dev.s7a.strata.modifier.padding
                fun UiScope.exclusive(modifier: Modifier, ready: Boolean, page: Int) {
                    if (ready) Text("ready", modifier = modifier) else Text("loading", modifier = modifier)
                }
                fun UiScope.choice(modifier: Modifier, page: Int) {
                    when (page) {
                        0 -> Text("one", modifier = modifier)
                        else -> Column(modifier = modifier) { Text("other") }
                    }
                }
                fun UiScope.correct(modifier: Modifier) {
                    Column(modifier = modifier.padding(2)) {
                        Text("one", modifier = Modifier.Empty.padding(1))
                        Text("two")
                    }
                }
                fun UiScope.value(modifier: Modifier): Modifier = modifier.padding(1)
                fun UiScope.values(modifier: Modifier) {
                    val first = value(modifier)
                    val second = value(modifier)
                    Text("root", modifier = first.then(second))
                }
                fun UiScope.future(modifier: Modifier) {
                    val first = dev.s7a.strata.ui.UiDefinition { Text("first", modifier = modifier) }
                    val second = dev.s7a.strata.ui.UiDefinition { Text("second", modifier = modifier) }
                    Text("current", modifier = modifier)
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
