@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM execution-owner rejection and collection of retired keys/descriptions while terminal owners remain reachable.
 */
internal class DescriptionValidationOwnershipTest {
    @Test
    fun c41WrongJvmThread() {
        val probe = DescriptionValidationProbe()
        UiTree().use { tree ->
            tree.update(probe.element(0))
            val history = probe.trace.toList()
            val failures = ArrayList<Throwable?>()
            val thread = Thread {
                failures.add(runCatching { tree.update(probe.element(1, key = probe.key(1))) }.exceptionOrNull())
                failures.add(runCatching { tree.close() }.exceptionOrNull())
            }
            thread.start()
            thread.join()
            assertTrue(failures.all { it is IllegalStateException })
            assertEquals(history, probe.trace)
            assertEquals(TreeState.Active, tree.state)
        }
    }

    @Test
    fun terminalAndRemovedDescriptionsDoNotRetainKeyHistory() {
        val released = removedKey()
        val terminal = closedObservedSession()
        repeat(50) {
            if (released.key.get() != null || terminal.key.get() != null || terminal.source.get() != null) {
                System.gc()
                Thread.sleep(10)
            }
        }
        assertNull(released.key.get())
        assertNull(terminal.key.get())
        assertNull(terminal.source.get())
        // Both owner objects stay strongly reachable across the collection assertions.
        assertEquals(TreeState.Active, released.tree.state)
        released.tree.close()
        terminal.session.close()
        assertFailsWith<IllegalStateException> { terminal.session.frame(Constraints.fixed(2, 2)) }
    }

    private fun removedKey(): Removed {
        val probe = DescriptionValidationProbe()
        val key = probe.key(1)
        val tree = UiTree()
        tree.update(probe.element(0, listOf(probe.element(1, key = key))))
        repeat(16) { tree.update(probe.element(0, listOf(probe.element(it + 2, key = probe.key(it + 2))))) }
        tree.update(probe.element(0))
        return Removed(tree, WeakReference(key))
    }

    private fun closedObservedSession(): Terminal {
        val probe = DescriptionValidationProbe()
        val key = probe.key(1)
        val source = DescriptionValidationSource(key)
        val session =
            createRuntimeUiSession {
                evaluateComponentTree { Observe(source) { element(probe.element(1, key = it)) } }
            }
        session.attach()
        session.frame(Constraints.fixed(2, 2))
        session.close()
        assertEquals(1, source.releases)
        return Terminal(session, WeakReference(key), WeakReference(source))
    }

    /**
     * Live tree with its retired key represented only by a weak reference.
     */
    private data class Removed(val tree: UiTree, val key: WeakReference<DescriptionValidationProbe.Key>)

    /**
     * Closed owner plus weak caller objects; no test closure captures either caller object.
     */
    private data class Terminal(
        val session: RuntimeUiSession,
        val key: WeakReference<DescriptionValidationProbe.Key>,
        val source: WeakReference<DescriptionValidationSource<DescriptionValidationProbe.Key>>,
    )
}
