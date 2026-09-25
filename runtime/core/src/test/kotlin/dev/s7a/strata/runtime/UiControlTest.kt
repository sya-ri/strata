@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Presentation acknowledgement ordering and event ownership independent of native adapters.
 */
internal class UiControlTest {
    @Test
    fun reevaluationAndDeferredContentKeepTheDeliverySessionUntilTerminalCaptureNotification() {
        val source = ObserveTestSource(0)
        val application = mutableStateOf(0)
        val delivered = mutableListOf<Pair<UiSession, Int>>()
        var cleanupReceiver: UiSession? = null
        var retained: RuntimeUiSession? = null
        lateinit var controls: RuntimeUiController
        controls = RuntimeUiController(UiPresentation.Screen, apply = { controls.applied(it.sequence) }, close = { retained?.close() })
        val engine =
            createRuntimeUiSession(controls) {
                val captured = application.value
                evaluateComponentTree {
                    Observe(source) { value ->
                        Spacer(
                            Modifier.Empty
                                .size(10, 10)
                                .onCapturedPointerEvent(PointerButton.Primary, onCancel = {
                                    if (status is UiSessionStatus.Closed) {
                                        cleanupReceiver = this
                                        assertEquals(UiOperationResult.Rejected(UiRejection.Closed), switch(UiPresentation.Hud))
                                    }
                                }) { event, _ ->
                                    if (event is PointerEvent.Press) {
                                        delivered += this to (captured + value)
                                        if (value == 2) close()
                                    }
                                },
                        )
                    }
                }
            }
        retained = engine
        controls.start()
        controls.transaction { engine.attach() }
        repeat(3) { value ->
            application.value = value * 10
            source.publish(value)
            controls.transaction { engine.frame(Constraints.fixed(10, 10)) }
            controls.transaction { engine.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)) }
            if (value < 2) controls.transaction { engine.resetInputState() }
        }
        assertEquals(listOf(0, 11, 22), delivered.map { it.second })
        delivered.forEach { assertSame(controls, it.first) }
        assertSame(controls, cleanupReceiver)
        assertEquals(source.subscriptions, source.releases)
        controls.close()
    }

    @Test
    fun appliedPresentationWaitsForLatestAcknowledgementAndSurvivesClose() {
        val requests = mutableListOf<RuntimeUiControl>()
        val controller = RuntimeUiController(UiPresentation.Screen, apply = requests::add, close = {})
        controller.start()
        assertNull(controller.presentation)
        controller.applied(requests.last().sequence)
        assertEquals(UiPresentation.Screen, controller.presentation)
        controller.switch(UiPresentation.Hud)
        val obsolete = requests.last().sequence
        controller.switch(UiPresentation.Screen)
        assertEquals(3, requests.size)
        controller.applied(obsolete)
        assertEquals(UiSessionStatus.Switching(UiPresentation.Screen), controller.status)
        controller.applied(requests.last().sequence)
        controller.switch(UiPresentation.Hud)
        controller.applied(requests.last().sequence)
        controller.close()
        controller.applied(obsolete)
        assertEquals(UiPresentation.Hud, controller.presentation)
        assertEquals(UiSessionStatus.Closed(UiCloseReason.Closed), controller.status)
    }

    @Test
    fun requestsCoalesceAtEventBoundaryAndCloseWins() {
        val requests = mutableListOf<RuntimeUiControl>()
        var closed = 0
        val controller = RuntimeUiController(UiPresentation.Screen, apply = requests::add, close = { closed++ })
        controller.start()
        controller.applied(requests.last().sequence)
        controller.transaction {
            controller.switch(UiPresentation.Hud)
            controller.switch(UiPresentation.Hud)
            assertEquals(1, requests.size)
        }
        assertEquals(2, requests.size)
        controller.transaction {
            controller.switch(UiPresentation.Screen)
            controller.close()
            controller.close()
            assertEquals(0, closed)
        }
        assertEquals(2, requests.size)
        assertEquals(1, closed)
        assertEquals(UiOperationResult.Rejected(UiRejection.Closed), controller.switch(UiPresentation.Screen))
    }

    @Test
    fun rejectionKeepsAppliedStateAndFailureReleasesDriverOnce() {
        val requests = mutableListOf<RuntimeUiControl>()
        var closed = 0
        val failure = IllegalArgumentException("event")
        val cleanup = IllegalStateException("cleanup")
        val controller =
            RuntimeUiController(UiPresentation.Screen, apply = requests::add, close = {
                closed++
                throw cleanup
            })
        controller.start()
        controller.applied(requests.last().sequence)
        controller.switch(UiPresentation.Hud)
        controller.rejected(requests.last().sequence, UiRejection.Capacity)
        assertEquals(UiPresentation.Screen, controller.presentation)
        assertEquals(UiSessionStatus.Ready(UiRejection.Capacity), controller.status)
        val caught = assertThrows(IllegalArgumentException::class.java) { controller.transaction { throw failure } }
        assertSame(failure, caught)
        assertEquals(listOf(cleanup), caught.suppressed.toList())
        controller.close()
        assertEquals(1, closed)
    }

    @Test
    fun unpresentedEngineDefersEventCloseAndReleasesItsWholeOwner() {
        var receiver: UiSession? = null
        val host =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Spacer(
                        modifier =
                            Modifier.Empty.size(10, 10).onActivate {
                                receiver = this
                                close()
                                assertEquals(UiOperationResult.Rejected(UiRejection.Closed), switch(UiPresentation.Hud))
                            },
                    )
                }
            }
        host.attach()
        host.frame(Constraints.fixed(10, 10))
        host.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
        assertEquals(UiSessionStatus.Closed(UiCloseReason.Closed), checkNotNull(receiver).status)
        assertThrows(IllegalStateException::class.java) { host.frame(Constraints.fixed(10, 10)) }
        host.close()
        checkNotNull(receiver).close()
    }

    @Test
    fun reusedModifierUsesDeliveryOwnerAndCleanupCannotReopenSession() {
        val delivered = mutableListOf<UiSession>()
        val modifier =
            Modifier.Empty
                .size(10, 10)
                .onActivate {
                    delivered += this
                    switch(UiPresentation.Hud)
                    close()
                }.onFocusChanged {
                    if (status is UiSessionStatus.Closed) assertEquals(UiOperationResult.Rejected(UiRejection.Closed), switch(UiPresentation.Screen))
                }
        repeat(2) {
            val requests = mutableListOf<RuntimeUiControl>()
            var retained: UiTree? = null
            val controller = RuntimeUiController(UiPresentation.Screen, apply = requests::add, close = { retained?.close() })
            val tree = UiTree(controller)
            retained = tree
            tree.update(evaluateComponentTree { Spacer(modifier = modifier) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
            controller.start()
            controller.applied(requests.last().sequence)
            controller.transaction { tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)) }
            assertSame(controller, delivered.last())
            assertEquals(TreeState.Closed, tree.state)
            assertEquals(1, requests.size)
        }
        assertEquals(2, delivered.distinct().size)
    }
}
