@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Runs logical ownership, callback lifetime, and terminal failure controls on JVM and JavaScript.
 */
internal class RootOverlayScopeLifetimeTest {
    @Test
    fun activeReadsAreExactAndCaughtLogicalOwnerFailuresPreserveOutput() {
        val owner = RuntimeExecutionOwner()
        val other = RuntimeExecutionOwner()
        owner.run {
            val probe = RootOverlayProbe()
            probe.rootOverlay = { scope ->
                repeat(3) {
                    assertEquals(IntRect(0, 0, 4, 4), scope.anchorBounds)
                    assertEquals(IntSize(4, 4), scope.size)
                }
                other.run {
                    val expected = assertFailsWith<IllegalStateException> { scope.size }
                    val actual = assertFailsWith<IllegalStateException> { scope.anchorBounds }
                    assertEquals(expected.message, actual.message)
                    assertEquals("This runtime object requires its owning execution context.", actual.message)
                }
                assertEquals(IntRect(0, 0, 4, 4), scope.anchorBounds)
                scope.fillRectangle(IntRect(1, 1, 3, 3), ArgbColor(-1))
            }
            val tree = laidOut(probe)
            try {
                val commands = tree.paint()
                assertEquals(
                    listOf(DrawCommand.PushClip(IntRect(0, 0, 4, 4)), DrawCommand.PopClip, DrawCommand.FillRectangle(IntRect(1, 1, 3, 3), ArgbColor(-1))),
                    commands,
                )
                assertEquals(TreeState.Active, tree.state)
                assertEquals(0, probe.detachCalls)
                assertEquals(0, probe.disposeCalls)
                assertEquals(commands, tree.paint())
                assertEquals(1, probe.rootOverlayCalls)
            } finally {
                tree.close()
            }
        }
    }

    @Test
    fun returnedScopeRejectsBeforeAnyAdditionalPaintingOrCleanup() {
        val probe = RootOverlayProbe()
        val tree = laidOut(probe)
        try {
            tree.paint()
            val scope = probe.scopes.single()
            repeat(3) {
                assertEquals("The callback scope is no longer active.", assertFailsWith<IllegalStateException> { scope.anchorBounds }.message)
                assertFailsWith<IllegalStateException> { scope.size }
                assertFailsWith<IllegalStateException> { scope.fillRectangle(IntRect(0, 0, 1, 1), ArgbColor(-1)) }
            }
            assertEquals(TreeState.Active, tree.state)
            assertEquals(1, probe.rootOverlayCalls)
            assertEquals(0, probe.detachCalls)
            assertEquals(0, probe.disposeCalls)
        } finally {
            tree.close()
        }
        assertFailsWith<IllegalStateException> { probe.scopes.single().anchorBounds }
        assertEquals(1, probe.detachCalls)
        assertEquals(1, probe.disposeCalls)
    }

    @Test
    fun expiredScopeChecksOwnerBeforeLifetimeEvenAfterTreeClose() {
        val owner = RuntimeExecutionOwner()
        val other = RuntimeExecutionOwner()
        owner.run {
            val probe = RootOverlayProbe()
            val tree = laidOut(probe)
            tree.paint()
            tree.close()
            val scope = probe.scopes.single()
            val lifetime = assertFailsWith<IllegalStateException> { scope.anchorBounds }
            assertEquals("The callback scope is no longer active.", lifetime.message)
            other.run {
                val failure = assertFailsWith<IllegalStateException> { scope.anchorBounds }
                assertEquals("This runtime object requires its owning execution context.", failure.message)
                assertEquals(assertFailsWith<IllegalStateException> { scope.size }.message, failure.message)
            }
        }
    }

    @Test
    fun throwingCallbackClosesScopeAndKeepsPrimaryAndOrderedCleanupFailures() {
        val primary = IllegalArgumentException("overlay callback")
        val detach = IllegalStateException("detach")
        val dispose = IllegalStateException("dispose")
        val probe = RootOverlayProbe()
        probe.detachFailure = detach
        probe.disposeFailure = dispose
        probe.rootOverlay = { scope ->
            scope.fillRectangle(IntRect(0, 0, 1, 1), ArgbColor(-1))
            throw primary
        }
        val tree = laidOut(probe)
        assertSame(primary, assertFailsWith<IllegalArgumentException> { tree.paint() })
        assertEquals(listOf(detach, dispose), primary.suppressedExceptions.toList())
        assertEquals(TreeState.Poisoned, tree.state)
        assertFailsWith<IllegalStateException> { probe.scopes.single().anchorBounds }
        tree.close()
        assertEquals(1, probe.detachCalls)
        assertEquals(1, probe.disposeCalls)
    }

    @Test
    fun escapingGetterFailureFailsSessionWithoutPublishingPartialCommands() {
        val owner = RuntimeExecutionOwner()
        val other = RuntimeExecutionOwner()
        for (cause in GuardFailureCause.entries) {
            owner.run {
                val probe = RootOverlayProbe()
                val detach = IllegalStateException("detach after guard failure")
                val dispose = IllegalStateException("dispose after guard failure")
                probe.detachFailure = detach
                probe.disposeFailure = dispose
                val session = createRuntimeUiSession { probe.element() }
                try {
                    session.attach()
                    val initial = session.frame(Constraints.fixed(4, 4))
                    val committed = initial.drawCommands.toList()
                    val expired = probe.scopes.single()
                    var guardFailure: IllegalStateException? = null
                    probe.rootOverlay = { scope ->
                        scope.fillRectangle(IntRect(0, 0, 4, 4), ArgbColor(-1))
                        guardFailure =
                            when (cause) {
                                GuardFailureCause.Owner -> other.run { assertFailsWith<IllegalStateException> { scope.anchorBounds } }
                                GuardFailureCause.Lifetime -> assertFailsWith<IllegalStateException> { expired.anchorBounds }
                            }
                        throw requireNotNull(guardFailure)
                    }
                    probe.invalidatePaint()
                    val thrown = assertFailsWith<IllegalStateException> { session.frame(Constraints.fixed(4, 4)) }
                    assertSame(guardFailure, thrown)
                    assertEquals(listOf(detach, dispose), thrown.suppressedExceptions.toList())
                    assertEquals(committed, initial.drawCommands)
                    probe.scopes.forEach { scope -> assertFailsWith<IllegalStateException> { scope.anchorBounds } }
                    assertFailsWith<IllegalStateException> { session.frame(Constraints.fixed(4, 4)) }
                } finally {
                    session.close()
                }
                assertEquals(1, probe.detachCalls)
                assertEquals(1, probe.disposeCalls)
            }
        }
    }

    @Test
    fun detachReattachReplacementAndCloseNeverReviveAnOldScope() {
        val first = RootOverlayProbe()
        val second = RootOverlayProbe()
        val content = mutableStateOf(first.element())
        val session = createRuntimeUiSession { content.value }
        try {
            session.attach()
            session.frame(Constraints.fixed(4, 4))
            val old = first.scopes.single()
            session.detach()
            assertFailsWith<IllegalStateException> { old.anchorBounds }
            session.attach()
            first.invalidatePaint()
            first.rootOverlay = { scope ->
                assertNotSame(old, scope)
                assertFailsWith<IllegalStateException> { old.anchorBounds }
                assertEquals(IntRect(0, 0, 4, 4), scope.anchorBounds)
            }
            session.frame(Constraints.fixed(4, 4))
            content.value = second.element()
            second.rootOverlay = { scope ->
                first.scopes.forEach { previous -> assertFailsWith<IllegalStateException> { previous.anchorBounds } }
                assertEquals(IntRect(0, 0, 4, 4), scope.anchorBounds)
            }
            session.frame(Constraints.fixed(4, 4))
            assertEquals(1, first.detachCalls)
            assertEquals(1, first.disposeCalls)
        } finally {
            session.close()
        }
        (first.scopes + second.scopes).forEach { scope -> assertFailsWith<IllegalStateException> { scope.anchorBounds } }
        assertEquals(1, second.detachCalls)
        assertEquals(1, second.disposeCalls)
    }

    /**
     * Prepares a public-node tree on the current execution owner without painting it.
     */
    private fun laidOut(probe: RootOverlayProbe): UiTree =
        UiTree().also { tree ->
            tree.update(probe.element())
            tree.measure(Constraints.fixed(4, 4))
            tree.layout()
        }

    /**
     * Independent guard boundaries whose escaped failures follow the same session failure contract.
     */
    private enum class GuardFailureCause {
        Owner,
        Lifetime,
    }
}
