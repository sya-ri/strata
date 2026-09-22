@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardInputFilter
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.modifier.onDrag
import dev.s7a.strata.modifier.onKeyEvent
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onKeyRelease
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.onPreedit
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.onRelease
import dev.s7a.strata.modifier.onScroll
import dev.s7a.strata.modifier.onTextInput
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real retained input passes through binary transport, server admission, and the common action boundary.
 */
internal class RemoteInputSubscriptionTest {
    @Test
    fun filtersBeforeSendingAndRunsTheServerHandlerOnlyAfterDelivery() {
        val accepted = mutableListOf<KeyboardEvent.Press>()
        val filter = KeyboardInputFilter(setOf(KeyCode('S'.code)), KeyboardModifiers(control = true))
        Harness { Modifier.Empty.initialFocus().onKeyPress(InputResult.Consumed, filter, accepted::add) }.use { fixture ->
            assertEquals(InputResult.Ignored, fixture.host.dispatchKeyboard(KeyboardEvent.Press(KeyCode('S'.code), 31)))
            assertEquals(InputResult.Ignored, fixture.host.dispatchKeyboard(KeyboardEvent.Release(KeyCode('S'.code), 31, KeyboardModifiers(control = true))))
            assertTrue(fixture.actions().isEmpty())
            val event = KeyboardEvent.Press(KeyCode('S'.code), 31, KeyboardModifiers(control = true, capsLock = true))
            assertEquals(InputResult.Consumed, fixture.host.dispatchKeyboard(event))
            assertTrue(accepted.isEmpty())
            val action = fixture.actions().single()
            fixture.server.receive(action)
            fixture.server.receive(action)
            assertEquals(listOf(event), accepted)
        }
    }

    @Test
    fun changedSubscriptionsRetireQueuedEventsWithoutInvokingReplacementHandlers() {
        val selected = mutableStateOf(KeyCode('S'.code))
        val accepted = mutableListOf<KeyCode>()
        Harness { Modifier.Empty.initialFocus().onKeyPress(InputResult.Consumed, KeyboardInputFilter(setOf(selected.value))) { accepted.add(it.key) } }.use { fixture ->
            fixture.host.dispatchKeyboard(KeyboardEvent.Press(KeyCode('S'.code), 31))
            val old = fixture.actions().single()
            selected.value = KeyCode.Enter
            fixture.server.tick()
            fixture.server.receive(old)
            assertTrue(accepted.isEmpty())
            assertEquals(RemoteSessionStatus.Open, fixture.server.status)
            fixture.updateClient()
            assertEquals(InputResult.Ignored, fixture.host.dispatchKeyboard(KeyboardEvent.Press(KeyCode('S'.code), 31)))
            fixture.host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 28))
            val fresh = fixture.actions().last()
            assertNotEquals(old.endpoint, fresh.endpoint)
            fixture.server.receive(fresh)
            assertEquals(listOf(KeyCode.Enter), accepted)
        }
    }

    @Test
    fun carriesPointerCoordinatesAndDeltasWithoutWaitingForTheServer() {
        val accepted = mutableListOf<Pair<PointerEvent, IntOffset>>()
        Harness { Modifier.Empty.onPointerEvent(InputResult.Ignored) { event, position -> accepted.add(event to position) } }.use { fixture ->
            val events =
                listOf(
                    PointerEvent.Press(IntOffset(4, 5), PointerButton.Secondary),
                    PointerEvent.Move(IntOffset(6, 7)),
                    PointerEvent.Drag(IntOffset(8, 9), PointerButton.Secondary, -0.5, 2.25),
                    PointerEvent.Scroll(IntOffset(10, 11), 0.25, -3.5),
                    PointerEvent.Release(IntOffset(12, 13), PointerButton.Secondary),
                )
            events.forEach { assertEquals(InputResult.Ignored, fixture.host.dispatchPointer(it)) }
            assertTrue(accepted.isEmpty())
            fixture.actions().forEach(fixture.server::receive)
            assertEquals(events.map { it to it.position }, accepted)
        }
    }

    @Test
    fun onlySubscribedTextVariantsCrossTheConnection() {
        val accepted = mutableListOf<TextInputEvent.Preedit>()
        Harness { Modifier.Empty.initialFocus().onPreedit(InputResult.Ignored, accepted::add) }.use { fixture ->
            fixture.host.dispatchTextInput(TextInputEvent.Character(65))
            assertTrue(fixture.actions().isEmpty())
            val event = TextInputEvent.Preedit("日本語", 2, listOf("日本", "語"), 1)
            assertEquals(InputResult.Ignored, fixture.host.dispatchTextInput(event))
            fixture.actions().forEach(fixture.server::receive)
            assertEquals(listOf(event), accepted)
        }
    }

    @Test
    fun eachPointerSubscriptionDeliversOnlyItsVariantAndButton() {
        val position = IntOffset(3, 4)
        val events =
            listOf(
                PointerEvent.Press(position, PointerButton.Secondary),
                PointerEvent.Release(position, PointerButton.Secondary),
                PointerEvent.Move(position),
                PointerEvent.Drag(position, PointerButton.Secondary, 1.0, 2.0),
                PointerEvent.Scroll(position, 2.0, 3.0),
            )
        val factories: List<((PointerEvent, IntOffset) -> Unit) -> Modifier> =
            listOf(
                { Modifier.Empty.onPress(InputResult.Consumed, PointerButton.Secondary, it) },
                { Modifier.Empty.onRelease(InputResult.Consumed, PointerButton.Secondary, it) },
                { Modifier.Empty.onMove(InputResult.Consumed, it) },
                { Modifier.Empty.onDrag(InputResult.Consumed, PointerButton.Secondary, it) },
                { Modifier.Empty.onScroll(InputResult.Consumed, it) },
            )
        factories.forEachIndexed { index, factory ->
            val accepted = mutableListOf<PointerEvent>()
            Harness { factory { event, _ -> accepted.add(event) } }.use { fixture ->
                fixture.host.dispatchPointer(PointerEvent.Press(position, PointerButton.Primary))
                assertTrue(fixture.actions().isEmpty())
                events.forEach { fixture.host.dispatchPointer(it) }
                val action = fixture.actions().single()
                fixture.server.receive(action)
                assertEquals(listOf(events[index]), accepted)
            }
        }
    }

    @Test
    fun allKeyboardAndTextOverloadsUseTypedNotifications() {
        val key = KeyboardEvent.Press(KeyCode.Enter, 28)
        val release = KeyboardEvent.Release(key.key, key.scanCode)
        val keyboardFactories: List<((KeyboardEvent) -> Unit) -> Modifier> =
            listOf(
                { Modifier.Empty.initialFocus().onKeyEvent(InputResult.Ignored, action = it) },
                { Modifier.Empty.initialFocus().onKeyRelease(InputResult.Ignored, action = it) },
            )
        keyboardFactories.zip(listOf(listOf(key, release), listOf(release))).forEach { (factory, expected) ->
            val accepted = mutableListOf<KeyboardEvent>()
            Harness { factory(accepted::add) }.use { fixture ->
                fixture.host.dispatchKeyboard(key)
                fixture.host.dispatchKeyboard(release)
                fixture.actions().forEach(fixture.server::receive)
                assertEquals(expected, accepted)
            }
        }
        val character = TextInputEvent.Character(0x1F642)
        val preedit = TextInputEvent.Preedit("a", 1, listOf("a"), 0)
        val textFactories: List<((TextInputEvent) -> Unit) -> Modifier> =
            listOf(
                { Modifier.Empty.initialFocus().onTextInput(InputResult.Ignored, it) },
                { Modifier.Empty.initialFocus().onCharacterInput(InputResult.Ignored, it) },
            )
        textFactories.zip(listOf(listOf(character, preedit), listOf(character))).forEach { (factory, expected) ->
            val accepted = mutableListOf<TextInputEvent>()
            Harness { factory(accepted::add) }.use { fixture ->
                fixture.host.dispatchTextInput(character)
                fixture.host.dispatchTextInput(preedit)
                fixture.actions().forEach(fixture.server::receive)
                assertEquals(expected, accepted)
            }
        }
    }

    @Test
    fun changingCaptureButtonDoesNotRetargetAnActiveGesture() {
        val button = mutableStateOf<PointerButton>(PointerButton.Secondary)
        val accepted = mutableListOf<PointerEvent>()
        Harness { Modifier.Empty.onCapturedPointerEvent(button.value, {}) { event, _ -> accepted.add(event) } }.use { fixture ->
            val press = PointerEvent.Press(IntOffset(3, 3), PointerButton.Secondary)
            fixture.host.dispatchPointer(press)
            fixture.server.receive(fixture.actions().last())
            button.value = PointerButton.Primary
            fixture.server.tick()
            fixture.updateClient()
            fixture.host.dispatchPointer(PointerEvent.Move(IntOffset(-10, -10)))
            fixture.server.receive(fixture.actions().last())
            fixture.host.dispatchPointer(PointerEvent.Release(IntOffset(-10, -10), PointerButton.Secondary))
            fixture.server.receive(fixture.actions().last())
            assertEquals(listOf(press), accepted)
            val next = PointerEvent.Press(IntOffset(4, 4), PointerButton.Primary)
            fixture.host.dispatchPointer(next)
            fixture.server.receive(fixture.actions().last())
            assertEquals(listOf(press, next), accepted)
        }
    }

    @Test
    fun captureAndCancellationUseTheExistingClientPipeline() {
        val accepted = mutableListOf<PointerEvent>()
        val cancelled = mutableListOf<PointerButton>()
        Harness { Modifier.Empty.onCapturedPointerEvent(PointerButton.Secondary, cancelled::add) { event, _ -> accepted.add(event) } }.use { fixture ->
            assertEquals(InputResult.Ignored, fixture.host.dispatchPointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary)))
            assertTrue(fixture.actions().isEmpty())
            val press = PointerEvent.Press(IntOffset(2, 2), PointerButton.Secondary)
            val drag = PointerEvent.Drag(IntOffset(-40, 60), PointerButton.Secondary, -42.0, 58.0)
            assertEquals(InputResult.Consumed, fixture.host.dispatchPointer(press))
            assertEquals(InputResult.Consumed, fixture.host.dispatchPointer(drag))
            fixture.host.resetInputState()
            fixture.actions().forEach(fixture.server::receive)
            assertEquals(listOf(press, drag), accepted)
            assertEquals(listOf(PointerButton.Secondary), cancelled)
            fixture.host.resetInputState()
            assertEquals(3, fixture.actions().size)
        }
    }

    @Test
    fun serverRejectsForgedEventsOutsideTheDeclaredFilter() {
        var calls = 0
        Harness { Modifier.Empty.initialFocus().onKeyPress(InputResult.Consumed, KeyboardInputFilter(setOf(KeyCode.Enter))) { calls += 1 } }.use { fixture ->
            fixture.host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 28))
            val action = fixture.actions().single()
            assertThrows(RemoteProtocolException::class.java) {
                fixture.server.receive(action.copy(value = ProjectionInputCodec.keyboard(KeyboardEvent.Press(KeyCode.Space, 57))))
            }
            assertEquals(0, calls)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), fixture.server.status)
        }
    }

    @Test
    fun missingInputCapabilityRejectsTheWholeScreen() {
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        RemoteServerSession(1, ProjectionValue.Absent, registry.types - BuiltinProjection.KeyPress.type, send = {}) {
            evaluateComponentTree { Spacer(Modifier.Empty.onKeyPress(InputResult.Ignored) {}) }
        }.use { server ->
            assertThrows(RemoteProtocolException::class.java, server::tick)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.UnsupportedType), server.status)
        }
    }

    /**
     * Owns an isolated client, server, binary channel, and retained host on the test thread.
     */
    private class Harness(
        modifier: () -> Modifier,
    ) : AutoCloseable {
        private val codec = RemoteMessageCodec()
        private val outbound = mutableListOf<RemoteMessage>()
        private val incoming = mutableListOf<RemoteMessage>()
        private val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val server =
            RemoteServerSession(1, ProjectionValue.Absent, registry.types, send = { outbound.add(codec.decode(codec.encode(it))) }) {
                evaluateComponentTree { Spacer(modifier()) }
            }
        private val client: RemoteClientSession
        val host: RuntimeUiSession

        init {
            server.tick()
            client = RemoteClientSession(outbound.removeAt(0) as RemoteMessage.Snapshot, registry, send = { incoming.add(codec.decode(codec.encode(it))) })
            val content = client.definition(UiText.Literal("Input")).transfer().content
            host = createRuntimeUiSession { evaluateComponentTree(content) }
            host.attach()
            host.frame(Constraints.fixed(20, 20))
        }

        /**
         * Returns only business events; installation acknowledgements use a separate message kind.
         */
        fun actions(): List<RemoteMessage.Action> = incoming.filterIsInstance<RemoteMessage.Action>()

        /**
         * Applies complete revisions without regenerating the client session or focused editor.
         */
        fun updateClient() {
            outbound.forEach { message ->
                when (message) {
                    is RemoteMessage.Update -> client.receive(message)
                    is RemoteMessage.Acknowledgement -> client.receive(message)
                    else -> error("Unexpected message: $message")
                }
            }
            outbound.clear()
            host.frame(Constraints.fixed(20, 20))
        }

        override fun close() {
            host.close()
            client.close()
            server.close()
        }
    }
}
