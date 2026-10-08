@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.properties.ReadOnlyProperty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Actual session declaration order and the complete cutoff across declared and tree-owned source queues.
 */
internal class DeclaredPendingSourceTest {
    @Test
    fun idleAndEqualDeclarationsReuseFramesAndReversePublicationsCommitInDeclarationOrder() {
        for (count in listOf(1, 128, 1_024)) {
            val compared = ArrayList<Int>()
            val sources = List(count) { index -> CutoffTestSource(Value(0) { compared.add(index) }) }
            val holders = List(count) { Holder<Value>() }
            var evaluations = 0
            var committed = emptyList<Int>()
            val session =
                UiSession(TestOwnerDispatcher()) {
                    evaluations += 1
                    committed = holders.map { it.value.number }
                    evaluateComponentTree { Spacer() }
                }
            holders.forEachIndexed { index, holder -> holder.delegate = session.bind(sources[index]) }
            try {
                session.attach()
                val constraints = Constraints.fixed(4, 4)
                val initial = session.frame(constraints)
                repeat(10) { assertSame(initial, session.frame(constraints)) }
                sources.forEachIndexed { index, source -> source.publish(Value(0) { compared.add(index) }) }
                assertSame(initial, session.frame(constraints))
                assertEquals(sources.indices.toList(), compared)
                compared.clear()
                sources.asReversed().forEach { it.publish(Value(1)) }
                session.frame(constraints)
                assertEquals(sources.indices.toList(), compared)
                assertEquals(List(count) { 1 }, committed)
                assertEquals(2, evaluations)
            } finally {
                session.close()
            }
            sources.forEach { assertEquals(1, it.releases) }
        }
    }

    @Test
    fun declaredComparisonPublishesOnlyAfterEveryObservedRootHasBeenCaptured() {
        val observed = CutoffTestSource(Value(0))
        var publishDuringComparison = false
        val declared = CutoffTestSource(Value(0) {
            if (publishDuringComparison) {
                publishDuringComparison = false
                observed.publish(Value(2))
            }
        })
        val holder = Holder<Value>()
        val seen = ArrayList<List<Int>>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                val declaration = holder.value.number
                evaluateComponentTree {
                    Observe(observed) {
                        seen.add(listOf(declaration, it.number))
                        Spacer()
                    }
                }
            }
        holder.delegate = session.bind(declared)
        try {
            session.attach()
            val constraints = Constraints.fixed(4, 4)
            session.frame(constraints)
            declared.publish(Value(1))
            observed.publish(Value(1))
            publishDuringComparison = true
            session.frame(constraints)
            assertEquals(listOf(1, 1), seen.last())
            session.frame(constraints)
            assertEquals(listOf(listOf(0, 0), listOf(1, 1), listOf(1, 2)), seen)
        } finally {
            session.close()
        }
        assertEquals(1, declared.releases)
        assertEquals(1, observed.releases)
    }

    /**
     * Delegate carrier populated before the first session evaluation.
     */
    private class Holder<T> {
        lateinit var delegate: ReadOnlyProperty<Any?, T>
        val value: T
            get() = delegate.getValue(this, ::value)
    }

    /**
     * Immutable source value with an optional application comparison hook.
     */
    private class Value(
        val number: Int,
        private val compared: () -> Unit = {},
    ) {
        override fun equals(other: Any?): Boolean {
            compared()
            return other is Value && number == other.number
        }

        override fun hashCode(): Int = number
    }
}
