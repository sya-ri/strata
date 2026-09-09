package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies source-backed text and editable node retention through the actual Minecraft screen host.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftObserveTest {
    @Test
    fun textSourcesShareTheirAncestorSubscriptionAndUpdateWithoutReopeningTheDefinition() {
        val source = Source("A")
        val unresolved = Source<UiText>(UiText.Literal("B"))
        val font = ResourceId("minecraft", "default")
        var definitions = 0
        val definition =
            ScreenDefinition("observed") {
                definitions += 1
                Observe(source) { prefix ->
                    Column(modifier = Modifier.Empty.size(80, 60)) {
                        Text(prefix)
                        Text(source)
                        Text(unresolved)
                        Text(source, font = font)
                        Text(unresolved, font = font)
                    }
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            val size = IntSize(80, 60)
            assertEquals(
                listOf("A", "A", "B", "A", "B").mapIndexed { index, value ->
                    if (index < 3) UiText.Literal(value) else UiText.WithFont(UiText.Literal(value), font)
                },
                host.frame(size).semantics.map { it.semantics.label },
            )
            source.publish("C")
            unresolved.publish(UiText.Literal("D"))
            assertEquals(
                listOf("C", "C", "D", "C", "D").mapIndexed { index, value ->
                    if (index < 3) UiText.Literal(value) else UiText.WithFont(UiText.Literal(value), font)
                },
                host.frame(size).semantics.map { it.semantics.label },
            )
            assertEquals(1, definitions)
            assertEquals(1, source.subscriptions)
        }
        assertEquals(1, source.releases)
        assertEquals(1, unresolved.releases)
    }

    @Test
    fun parentStateUpdatesPreserveEditorFocusCompositionAndCommittedText() {
        val source = Source(false)
        val state = TextAreaState("日A")
        val size = IntSize(32, 26)
        MinecraftTextAreaFixture().use { fixture ->
            val definition =
                ScreenDefinition("editor") {
                    Observe(source) { normal ->
                        element(fixture.description(state, size, style = if (normal) TextStyle.Normal else TextStyle.TextField))
                    }
                }
            createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
                host.attach()
                host.frame(size)
                val focus = host.textInputFocus
                assertNotNull(focus)
                host.dispatchTextInput(TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0))
                val composed = host.frame(size)
                source.publish(true)
                val updated = host.frame(size)
                assertSame(focus, host.textInputFocus)
                assertEquals(composed.semantics.single().semantics, updated.semantics.single().semantics)
                assertEquals("日A", state.value)
                host.dispatchTextInput(TextInputEvent.Character('한'.code))
                host.frame(size)
                assertEquals("日A한", state.value)
            }
            state.observe {}.close()
        }
    }

    /**
     * Sequential test source exposing its real subscription lifecycle.
     */
    private class Source<T>(
        initial: T,
    ) : StateSource<T> {
        private var current = StateSnapshot(StateRevision(0), initial)
        private var observer: ((StateSnapshot<T>) -> Unit)? = null
        var subscriptions = 0
        var releases = 0

        override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
            check(this.observer == null)
            this.observer = observer
            subscriptions += 1
            return StateSubscription(current) {
                this.observer = null
                releases += 1
            }
        }

        /**
         * Publishes the next immutable value synchronously.
         */
        fun publish(value: T) {
            current = StateSnapshot(StateRevision(current.revision.value + 1), value)
            observer?.invoke(current)
        }
    }
}
