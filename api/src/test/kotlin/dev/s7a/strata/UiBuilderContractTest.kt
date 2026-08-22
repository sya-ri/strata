package dev.s7a.strata

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.buildComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies the bounded declarative builder contract.
 */
internal class UiBuilderContractTest {
    @Test
    fun oneRootReturnsTheExactEmittedInstance() {
        val root = BuilderElement()

        val result = buildComponentTree { element(root) }

        assertSame(root, result)
    }

    @Test
    fun zeroRootsFailWithStableCardinalityMessage() {
        val failure = assertThrows(IllegalArgumentException::class.java) { buildComponentTree { } }

        assertEquals(
            "A component callback requires exactly one root element, but no elements were emitted.",
            failure.message,
        )
    }

    @Test
    fun multipleRootsFailWithoutDiscardingEmissionCardinality() {
        val first = BuilderElement()
        val second = BuilderElement()

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                buildComponentTree {
                    element(first)
                    element(second)
                }
            }

        assertEquals(
            "A component callback requires exactly one root element, but multiple elements were emitted.",
            failure.message,
        )
    }

    @Test
    fun callbackFailureIsPropagatedByIdentityAndClosesCapturedScope() {
        val callbackFailure = IllegalStateException("callback failure")
        var capturedScope: UiScope? = null

        val propagated =
            assertThrows(IllegalStateException::class.java) {
                buildComponentTree {
                    capturedScope = this
                    element(BuilderElement())
                    element(BuilderElement())
                    throw callbackFailure
                }
            }

        assertSame(callbackFailure, propagated)
        val scope = capturedScope ?: throw AssertionError("The callback did not capture its scope.")
        assertThrows(IllegalStateException::class.java) { scope.element(BuilderElement()) }
    }

    @Test
    fun capturedScopeRejectsSameThreadUseAfterSuccessfulReturn() {
        val root = BuilderElement()
        var capturedScope: UiScope? = null

        val result =
            buildComponentTree {
                capturedScope = this
                element(root)
            }

        assertSame(root, result)
        val scope = capturedScope ?: throw AssertionError("The callback did not capture its scope.")
        val failure = assertThrows(IllegalStateException::class.java) { scope.element(BuilderElement()) }
        assertEquals("UiScope cannot be used after its callback has completed.", failure.message)
    }

    @Test
    fun capturedScopeRejectsSameThreadUseAfterCardinalityFailure() {
        var capturedScope: UiScope? = null

        assertThrows(IllegalArgumentException::class.java) {
            buildComponentTree {
                capturedScope = this
                element(BuilderElement())
                element(BuilderElement())
            }
        }

        val scope = capturedScope ?: throw AssertionError("The callback did not capture its scope.")
        assertThrows(IllegalStateException::class.java) { scope.element(BuilderElement()) }
    }

    @Test
    fun activeScopeRejectsWrongThreadBeforeItCanEmit() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        var capturedScope: UiScope? = null
        val root = BuilderElement()
        val worker =
            Thread {
                try {
                    entered.await()
                    val scope = capturedScope ?: throw AssertionError("The callback did not capture its scope.")
                    scope.element(BuilderElement())
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finished.countDown()
                }
            }

        val result =
            buildComponentTree {
                capturedScope = this
                worker.start()
                entered.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                element(root)
            }
        worker.join(5_000)

        val wrongThreadFailure = failure.get() ?: throw AssertionError("The worker did not report a failure.")
        assertTrue(wrongThreadFailure is IllegalStateException)
        assertEquals(
            "UiScope can only be used from its constructing thread.",
            wrongThreadFailure.message,
        )
        assertSame(root, result)
    }

    private class BuilderNode : Node()

    private class BuilderElement : Element(ElementIdentity.Positional, TYPE) {
        companion object {
            val TYPE: ElementType<BuilderElement, BuilderNode> =
                ElementType(
                    elementClass = BuilderElement::class,
                    nodeClass = BuilderNode::class,
                    validateLocal = { },
                    createNode = { BuilderNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
