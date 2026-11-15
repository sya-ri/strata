@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.DragScrollOptions
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that Web primary-pointer scrolling uses the same configured recognition and inertia as core.
 */
internal class WebDragScrollOptionsTest {
    @Test
    fun primaryPointerPoliciesControlImmediateActivationDraggingAndReleasedMotion() {
        val policies = listOf(DragScrollOptions(enabled = false), DragScrollOptions(inertiaEnabled = false), DragScrollOptions.Default)
        for (options in policies) {
            val scroll = ScrollState(options)
            var clicks = 0
            val runtime = WebComponentRuntime()
            val session =
                createRuntimeUiSession {
                    ComponentRuntimeBridge.evaluate(runtime) {
                        ScrollArea(scroll, Modifier.size(100, 60)) {
                            Column {
                                Button("Row", width = 80, modifier = Modifier.onActivate { clicks++ })
                                Spacer(Modifier.size(80, 180))
                            }
                        }
                    }
                }
            try {
                session.attach()
                val constraints = Constraints.fixed(100, 60)
                session.frame(constraints, FrameTime(0))
                session.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
                assertEquals(if (options.enabled) 0 else 1, clicks)
                session.frame(constraints, FrameTime(16_000_000))
                session.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
                session.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
                val released = scroll.metrics.offset
                assertEquals(if (options.enabled) 20.0 else 0.0, released)
                session.frame(constraints, FrameTime(80_000_000))
                if (options.enabled && options.inertiaEnabled) {
                    assertTrue(released < scroll.metrics.offset)
                } else {
                    assertEquals(released, scroll.metrics.offset)
                }
            } finally {
                session.close()
            }
        }
    }
}
