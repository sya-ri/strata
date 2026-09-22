@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies declaration-only commits share state and lifecycle while leaving presentation phases untouched.
 */
internal class RuntimeDeclarationTest {
    @Test
    fun projectionDoesNotRunPresentationPhases() {
        val probe = TestProbe()
        createRuntimeUiSession { probe.root(emptyList()) }.use { session ->
            session.attach()
            val identity = session.projectDeclarations { it.identity }
            assertEquals(identity, session.projectDeclarations { it.identity })
            assertEquals(0, probe.measureCalls)
            assertEquals(0, probe.layoutCalls)
            assertEquals(0, probe.paintCalls)
            assertEquals(0, probe.semanticsCalls)
        }
        assertEquals(1, probe.events.count { it is TestProbe.Event.Dispose })
    }

    @Test
    fun actionStateChangesReconcileAtTheNextDeclarationCutoff() {
        val visible = mutableStateOf(true)
        val key = ElementKey("child")
        createRuntimeUiSession {
            evaluateComponentTree { Column { if (visible.value) Spacer(key = key) } }
        }.use { session ->
            session.attach()
            val first = session.projectDeclarations { it }
            session.dispatchAction { visible.value = false }
            val empty = session.projectDeclarations { it }
            assertEquals(first.identity, empty.identity)
            assertEquals(0, empty.children.size)
            session.dispatchAction { visible.value = true }
            val replacement = session.projectDeclarations { it }
            assertNotEquals(first.children.single().identity, replacement.children.single().identity)
        }
    }

    @Test
    fun projectionFailureKeepsPrimaryFailureAndCleansOwnershipOnce() {
        val probe = TestProbe()
        val failure = IllegalArgumentException("projection failed")
        val session = createRuntimeUiSession { probe.root(emptyList()) }
        session.attach()
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { session.projectDeclarations<Unit> { throw failure } })
        session.close()
        session.close()
        assertEquals(1, probe.events.count { it is TestProbe.Event.Dispose })
    }

    @Test
    fun projectionRejectsStateMutationAndActionReentrancy() {
        val state = mutableStateOf(0)
        createRuntimeUiSession { evaluateComponentTree { Spacer(modifier = Modifier.Empty.size(state.value + 1, 1)) } }.use { session ->
            session.attach()
            assertThrows(IllegalStateException::class.java) { session.projectDeclarations { state.value = 1 } }
        }
        createRuntimeUiSession { evaluateComponentTree { Spacer() } }.use { session ->
            session.attach()
            assertThrows(IllegalStateException::class.java) { session.dispatchAction { session.dispatchAction { } } }
        }
    }
}
