@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.VirtualListElement
import dev.s7a.strata.node.ContentWork
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Proves declaration identity, complete input invalidation and current-window ownership on JVM and JavaScript.
 */
internal class VirtualListDeclarationReuseTest {
    @Test
    fun oneRowShiftReusesThirtyNineOfFortyDeclarationsAndRereadsCurrentInputs() {
        Fixture().use { fixture ->
            val original = fixture.window(1_000.0)
            fixture.resetWork()
            val shifted = fixture.window(1_010.0)
            assertEquals(listOf(139), fixture.constructed)
            assertEquals(40, fixture.itemReads)
            assertEquals(1, fixture.rowWork)
            for (index in 0 until 39) assertSame(original[index + 1], shifted[index])
            assertNotSame(original.last(), shifted.last())
            assertEquals(40, original.size)
            assertSame(shifted, fixture.window(1_010.0))
            assertEquals(listOf(139), fixture.constructed)
        }
    }

    @Test
    fun fractionalMovementAndUnrelatedHistoryKeepOnlyTheCurrentWindow() {
        Fixture().use { fixture ->
            val first = fixture.window(1_000.0)
            fixture.resetWork()
            val fractional = fixture.window(1_001.25)
            assertEquals(41, fractional.size)
            assertEquals(listOf(139), fixture.constructed)
            for (index in first.indices) assertSame(first[index], fractional[index])
            assertSame(fractional, fixture.window(1_001.5))
            repeat(100) { step -> assertEquals(40, fixture.window((200 + step * 5) * 10.0).size) }
            fixture.resetWork()
            val returned = fixture.window(1_000.0)
            assertEquals((99 until 139).toList(), fixture.constructed)
            for (index in first.indices) assertNotSame(first[index], returned[index])
        }
    }

    @Test
    fun changedItemIdentityAndStableKeyRebuildOnlyTheirOverlappingRows() {
        Fixture().use { fixture ->
            val original = fixture.window(1_000.0)
            fixture.items[110] = fixture.items[110].copy()
            fixture.keys[120] = 10_120
            fixture.resetWork()
            val shifted = fixture.window(1_010.0)
            assertEquals(listOf(110, 120, 139), fixture.constructed)
            assertNotSame(original[11], shifted[10])
            assertNotSame(original[21], shifted[20])
            assertSame(original[12], shifted[11])
        }
    }

    @Test
    fun directStateReadsKeepTheOrdinaryFullWindowCallbackBehavior() {
        val value = mutableStateOf(0)
        val seen = ArrayList<Int>()
        Fixture {
            seen.add(value.value)
            Spacer()
        }.use { fixture ->
            fixture.window(1_000.0)
            fixture.resetWork()
            seen.clear()
            value.value = 1
            fixture.window(1_010.0)
            assertEquals((100 until 140).toList(), fixture.constructed)
            assertEquals(List(40) { 1 }, seen)
            assertEquals(40, fixture.rowWork)
        }
    }

    @Test
    fun refreshAndDefinitionReplacementNeverReuseCapturedCallbacks() {
        var captured = 0
        val seen = ArrayList<Int>()
        Fixture {
            seen.add(captured)
            Spacer()
        }.use { fixture ->
            fixture.window(1_000.0)
            captured = 1
            fixture.state.refresh()
            fixture.resetWork()
            seen.clear()
            fixture.window(1_000.0)
            assertEquals(40, fixture.constructed.size)
            assertEquals(List(40) { 1 }, seen)
            fixture.node.update(
                fixture.declaration {
                    seen.add(2)
                    Spacer()
                },
            )
            fixture.resetWork()
            seen.clear()
            fixture.window(1_000.0)
            assertEquals(List(40) { 2 }, seen)
        }
    }

    @Test
    fun failureAndOwnerRemovalReleaseTheReuseWindow() {
        var failing = false
        Fixture {
            if (failing) error("Row construction failed")
            Spacer()
        }.use { fixture ->
            val original = fixture.window(1_000.0)
            failing = true
            assertFailsWith<IllegalStateException> { fixture.window(1_010.0) }
            failing = false
            fixture.resetWork()
            val recovered = fixture.window(1_000.0)
            assertEquals(40, fixture.constructed.size)
            for (index in original.indices) assertNotSame(original[index], recovered[index])
            fixture.node.detach()
            fixture.node.attach()
            fixture.resetWork()
            val reattached = fixture.window(1_000.0)
            assertEquals(40, fixture.constructed.size)
            for (index in recovered.indices) assertNotSame(recovered[index], reattached[index])
        }
    }

    @Test
    fun independentOwnersNeverShareCachedDeclarations() {
        Fixture().use { first ->
            Fixture().use { second ->
                val left = first.window(1_000.0)
                val right = second.window(1_000.0)
                for (index in left.indices) assertNotSame(left[index], right[index])
                first.window(1_010.0)
                assertSame(right, second.window(1_000.0))
            }
        }
    }

    /**
     * Immutable input identity independent from its value equality.
     */
    private data class Row(
        val index: Int,
    )

    /**
     * Bound API owner with stable source identities and a replaceable declaration callback.
     */
    private class Fixture(
        private val content: UiScope.() -> Unit = { Spacer() },
    ) : AutoCloseable {
        val state = VirtualListState<Int>()
        val items = MutableList(1_000) { Row(it) }
        val keys = MutableList(1_000) { it }
        val constructed = ArrayList<Int>()
        var itemReads = 0
        var rowWork = 0
        val node = VirtualListElement.Node(declaration(content))
        private val release = node.bindRuntime {}

        init {
            node.contentWorkObserver = { work -> if (work == ContentWork.RowEvaluation) rowWork += 1 }
            node.attach()
            node.prepareDeclaration()
        }

        /**
         * Builds a replacement definition against the same current source.
         */
        fun declaration(callback: UiScope.() -> Unit): VirtualListElement =
            evaluateComponentTree {
                VirtualList(
                    itemCount = { items.size },
                    itemAt = { index ->
                        itemReads += 1
                        items[index]
                    },
                    keyAt = keys::get,
                    state = state,
                    viewportSize = IntSize(8, 380),
                    rowHeight = 10,
                ) { item ->
                    constructed.add(item.index)
                    callback()
                }
            } as VirtualListElement

        /**
         * Materializes the requested current window through the retained owner.
         */
        fun window(offset: Double): List<Element> {
            state.scrollState.scrollTo(offset)
            return node.dynamicChildren()
        }

        /**
         * Clears callback observations without changing owner state.
         */
        fun resetWork() {
            constructed.clear()
            itemReads = 0
            rowWork = 0
        }

        override fun close() {
            node.detach()
            node.dispose()
            release()
        }
    }
}
