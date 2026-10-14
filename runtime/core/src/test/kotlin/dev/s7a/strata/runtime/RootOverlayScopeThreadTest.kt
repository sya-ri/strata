@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Physical-thread controls are JVM-only; all worker operations finish within their owning test callback.
 */
internal class RootOverlayScopeThreadTest {
    @Test
    fun activeForeignThreadReadsAreCaughtWithoutPoisoningEitherOwnerMode() {
        val owner = RuntimeExecutionOwner()
        for (operation in listOf<(() -> Unit) -> Unit>({ it() }, { owner.run(it) })) {
            operation {
                val probe = RootOverlayProbe()
                val executor = Executors.newSingleThreadExecutor()
                probe.rootOverlay = { scope ->
                    val expectedOwner = RuntimeExecutionOwner.current()
                    executor
                        .submit {
                            assertNotEquals(expectedOwner, RuntimeExecutionOwner.current())
                            val failure = assertFailsWith<IllegalStateException> { scope.anchorBounds }
                            assertEquals("This runtime object requires its owning execution context.", failure.message)
                            assertEquals(assertFailsWith<IllegalStateException> { scope.size }.message, failure.message)
                        }.get(5, TimeUnit.SECONDS)
                    assertEquals(IntRect(0, 0, 4, 4), scope.anchorBounds)
                    scope.fillRectangle(scope.anchorBounds, ArgbColor(-1))
                }
                val tree = UiTree()
                try {
                    tree.update(probe.element())
                    tree.measure(Constraints.fixed(4, 4))
                    tree.layout()
                    assertTrue(tree.paint().isNotEmpty())
                    assertEquals(TreeState.Active, tree.state)
                    assertEquals(0, probe.detachCalls)
                    assertEquals(1, probe.rootOverlayCalls)
                } finally {
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                    tree.close()
                }
            }
        }
    }

    @Test
    fun serialOwnerAndSharedScopeGuardMigrateWhileConcurrentEntryIsRejected() {
        val owner = RuntimeExecutionOwner()
        val constructionThread = Thread.currentThread()
        val guard = owner.run { ScopeGuard(OwnerGuard()) }
        val tree = owner.run { UiTree() }
        val probe = RootOverlayProbe()
        val executor = Executors.newSingleThreadExecutor()
        val contender = Executors.newSingleThreadExecutor()
        probe.rootOverlay = { scope ->
            assertNotEquals(constructionThread, Thread.currentThread())
            assertEquals(IntRect(0, 0, 4, 4), scope.anchorBounds)
            contender
                .submit {
                    var invoked = false
                    val failure =
                        assertFailsWith<IllegalStateException> {
                            owner.run {
                                invoked = true
                                scope.anchorBounds
                            }
                        }
                    assertEquals("The runtime owner is already executing.", failure.message)
                    assertEquals(false, invoked)
                }.get(5, TimeUnit.SECONDS)
            scope.fillRectangle(scope.anchorBounds, ArgbColor(-1))
        }
        owner.run {
            tree.update(probe.element())
            tree.measure(Constraints.fixed(4, 4))
            tree.layout()
        }
        try {
            executor
                .submit {
                    owner.run {
                        guard.check()
                        assertTrue(tree.paint().isNotEmpty())
                    }
                }.get(5, TimeUnit.SECONDS)
            owner.run {
                guard.check()
                assertFailsWith<IllegalStateException> { probe.scopes.single().anchorBounds }
                probe.invalidatePaint()
            }
            executor.submit { owner.run { tree.paint() } }.get(5, TimeUnit.SECONDS)
            assertEquals(2, probe.rootOverlayCalls)
        } finally {
            executor.shutdownNow()
            contender.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(contender.awaitTermination(5, TimeUnit.SECONDS))
            owner.run {
                guard.close()
                tree.close()
            }
        }
    }

    @Test
    fun copiedRectangleRemainsThreadSafeAfterCloseButSavedScopeDoesNot() {
        val owner = RuntimeExecutionOwner()
        val probe = RootOverlayProbe()
        var copied: IntRect? = null
        probe.rootOverlay = { copied = it.anchorBounds }
        owner.run {
            val tree = UiTree()
            tree.update(probe.element())
            tree.measure(Constraints.fixed(4, 4))
            tree.layout()
            tree.paint()
            tree.close()
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    val rectangle = requireNotNull(copied)
                    assertEquals(IntRect(0, 0, 4, 4), rectangle)
                    assertEquals(IntSize(4, 4), rectangle.size)
                    assertEquals(4, rectangle.right)
                    assertEquals("This runtime object requires its owning execution context.", assertFailsWith<IllegalStateException> { probe.scopes.single().anchorBounds }.message)
                }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
