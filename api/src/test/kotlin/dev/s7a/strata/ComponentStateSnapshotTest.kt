@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.ComponentStateSubscription
import dev.s7a.strata.component.ObservableComponentState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Checks current snapshot identity, deterministic release and owner failure without requiring garbage collection.
 */
internal class ComponentStateSnapshotTest {
    @Test
    fun warmWritesReuseOnlyCurrentMembershipAndLastCloseDropsCallbacksImmediately() {
        val state = ObservableComponentState(0) { _ -> }
        val callback: (Int) -> Unit = { _ -> }
        val first = state.observe(callback)
        assertNull(snapshot(state))
        state.set(1)
        val original = snapshot(state)
        assertEquals(listOf(callback), original)
        state.set(2)
        assertSame(original, snapshot(state))
        assertFailsWith<IllegalStateException> { state.observe(callback) }
        assertSame(original, snapshot(state))
        val second = state.observe { _ -> }
        assertNull(snapshot(state))
        state.set(3)
        assertNotSame(original, snapshot(state))
        assertEquals(2, checkNotNull(snapshot(state)).size)
        second.close()
        assertNull(snapshot(state))
        state.set(4)
        assertEquals(listOf(callback), snapshot(state))
        first.close()
        assertNull(snapshot(state))
        assertTrue(observers(state).isEmpty())
        val releaseField = ComponentStateSubscription::class.java.getDeclaredField("release")
        releaseField.isAccessible = true
        for (handle in listOf(first, second)) {
            assertNull(releaseField.get(handle))
        }
        state.set(5)
        assertTrue(checkNotNull(snapshot(state)).isEmpty())
        val third = state.observe(callback)
        assertNull(snapshot(state))
        third.close()
        assertNull(snapshot(state))
        assertTrue(observers(state).isEmpty())
    }

    @Test
    fun wrongOwnerCannotReadWriteRegisterOrReleaseTheCurrentPlan() {
        val state = ObservableComponentState(0) { _ -> }
        val handle = state.observe { _ -> }
        state.set(1)
        val original = snapshot(state)
        val failure = AtomicReference<Throwable?>()
        Thread {
            failure.set(
                runCatching {
                    assertFailsWith<IllegalStateException> { state.get() }
                    assertFailsWith<IllegalStateException> { state.set(2) }
                    assertFailsWith<IllegalStateException> { state.observe { _ -> } }
                    assertFailsWith<IllegalStateException> { handle.close() }
                }.exceptionOrNull(),
            )
        }.apply {
            start()
            join()
        }
        assertNull(failure.get())
        assertSame(original, snapshot(state))
        assertEquals(1, state.get())
        handle.close()
        assertNull(snapshot(state))
        assertTrue(observers(state).isEmpty())
    }

    private fun snapshot(state: ObservableComponentState<*>): List<*>? {
        val field = ObservableComponentState::class.java.getDeclaredField("notificationSnapshot")
        field.isAccessible = true
        return field.get(state) as List<*>?
    }

    private fun observers(state: ObservableComponentState<*>): Set<*> {
        val field = ObservableComponentState::class.java.getDeclaredField("observers")
        field.isAccessible = true
        return field.get(state) as Set<*>
    }
}
