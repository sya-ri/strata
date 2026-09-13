@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Executes the same reactive tree, input, key ownership, and branch cleanup on JVM and JavaScript.
 */
internal class PortableSessionStateTest {
    @Test
    fun inputReevaluatesIfAndWhenAndDisposesRemovedBranches() {
        val shown = mutableStateOf(false)
        val count = mutableStateOf(0)
        val probe = TestProbe()
        val branch = TestProbe.ProbeId("branch")
        var disposed = 0
        var evaluations = 0
        var label = ""
        val session =
            createRuntimeUiSession {
                evaluations += 1
                label =
                    when (count.value) {
                        0 -> "Initial"
                        else -> "Changed"
                    }
                probe.root(
                    if (shown.value) listOf(probe.element(branch, key = branch, onDispose = { disposed += 1 })) else emptyList(),
                )
            }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals("Initial", label)
        probe.nodeForTag(TestProbe.ProbeId("root")).onInput = {
            shown.value = true
            count.value += 1
        }
        session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        assertEquals(1, evaluations)
        session.frame(Constraints.fixed(2, 1))
        assertEquals("Changed", label)
        assertEquals(2, evaluations)
        val original = probe.nodeForTag(branch)
        count.value += 1
        session.frame(Constraints.fixed(2, 1))
        assertSame(original, probe.nodeForTag(branch))
        shown.value = false
        session.frame(Constraints.fixed(2, 1))
        assertEquals(1, disposed)
        shown.value = true
        session.frame(Constraints.fixed(2, 1))
        assertNotSame(original, probe.nodeForTag(branch))
        session.close()
        assertEquals(2, disposed)
        shown.value = false
    }
}
