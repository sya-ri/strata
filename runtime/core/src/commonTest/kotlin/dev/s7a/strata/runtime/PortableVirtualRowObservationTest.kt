@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Keeps independent observation, source cutoff, callback captures and terminal release when rows reuse declarations.
 */
internal class PortableVirtualRowObservationTest {
    @Test
    fun reusedRowsKeepTheirOwnStateDependenciesAndOneFrameSourceCutoff() {
        val source = Source()
        val local = mutableStateOf(0)
        val visible = mutableStateOf(true)
        val state = VirtualListState<Int>()
        val items = List(1_000) { Model(it) }
        val probe = TestProbe()
        var factories = 0
        var publishedDuringEvaluation = false
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Stack {
                        if (visible.value) {
                            VirtualList(items, { it.index }, state, IntSize(8, 20), rowHeight = 10) { item ->
                                factories += 1
                                Observe(source) { version ->
                                    val captured = local.value
                                    if (version == 1 && item.index == 100 && publishedDuringEvaluation.not()) {
                                        publishedDuringEvaluation = true
                                        source.publish(2)
                                    }
                                    element(probe.element(TestProbe.ProbeId("${item.index}:$version:$captured")))
                                }
                            }
                        }
                    }
                }
            }
        try {
            session.attach()
            val constraints = Constraints.fixed(8, 20)
            session.frame(constraints)
            state.scrollState.scrollTo(1_000.0)
            session.frame(constraints)
            val before = factories
            state.scrollState.scrollTo(1_010.0)
            val moved = session.frame(constraints)
            assertEquals(before + 1, factories)
            assertEquals((100 until 104).map { UiText.Literal("$it:0:0") }, moved.semantics.map { it.semantics.label })
            assertEquals(1, source.subscriptions)
            source.publish(1)
            val cutoff = session.frame(constraints)
            assertEquals((100 until 104).map { UiText.Literal("$it:1:0") }, cutoff.semantics.map { it.semantics.label })
            assertTrue(publishedDuringEvaluation)
            val next = session.frame(constraints)
            assertEquals((100 until 104).map { UiText.Literal("$it:2:0") }, next.semantics.map { it.semantics.label })
            local.value = 7
            val localUpdate = session.frame(constraints)
            assertEquals((100 until 104).map { UiText.Literal("$it:2:7") }, localUpdate.semantics.map { it.semantics.label })
            assertEquals(before + 1, factories)
            visible.value = false
            session.frame(constraints)
            assertEquals(1, source.releases)
        } finally {
            session.close()
        }
        assertEquals(source.subscriptions, source.releases)
        local.value = 8
    }

    @Test
    fun replacingRootCallbackCapturesRebuildsTheFullCurrentWindow() {
        val captured = mutableStateOf(0)
        val state = VirtualListState<Int>()
        val models = List(1_000) { Model(it) }
        val seen = ArrayList<Pair<Int, Int>>()
        val session =
            createRuntimeUiSession {
                val value = captured.value
                evaluateComponentTree {
                    VirtualList(models, { it.index }, state, IntSize(8, 20), rowHeight = 10) { item ->
                        seen.add(item.index to value)
                        Spacer()
                    }
                }
            }
        session.use {
            session.attach()
            val constraints = Constraints.fixed(8, 20)
            session.frame(constraints)
            state.scrollState.scrollTo(1_000.0)
            session.frame(constraints)
            seen.clear()
            captured.value = 1
            session.frame(constraints)
            assertEquals((99 until 103).map { it to 1 }, seen)
        }
    }

    @Test
    fun rowFailureAndDuplicateKeysReleaseSubscriptionsAndNavigationOwnership() {
        for (duplicate in listOf(false, true)) {
            val source = Source()
            val state = VirtualListState<Int>()
            val models = List(100) { Model(it) }
            var fail = false
            val failure = IllegalArgumentException("Row callback failed")
            val session =
                createRuntimeUiSession {
                    evaluateComponentTree {
                        VirtualList(
                            itemCount = models.size,
                            itemAt = models::get,
                            keyAt = { index -> if (fail && duplicate) 0 else index },
                            state = state,
                            viewportSize = IntSize(8, 20),
                            rowHeight = 10,
                        ) {
                            if (fail && duplicate.not()) throw failure
                            Observe(source) { Spacer() }
                        }
                    }
                }
            try {
                session.attach()
                val constraints = Constraints.fixed(8, 20)
                session.frame(constraints)
                fail = true
                state.refresh()
                val thrown = assertFailsWith<IllegalArgumentException> { session.frame(constraints) }
                if (duplicate.not()) assertSame(failure, thrown)
                assertEquals(source.subscriptions, source.releases)
                assertTrue(state.jumpToIndex(0))
            } finally {
                session.close()
            }
            assertEquals(source.subscriptions, source.releases)
        }
    }

    /**
     * Immutable row identity retained independently from observed presentation state.
     */
    private class Model(
        val index: Int,
    )

    /**
     * Caller-owned source whose revision delivery and exact subscription release are independently observable.
     */
    private class Source : StateSource<Int> {
        private var snapshot = StateSnapshot(StateRevision(0), 0)
        private var observer: ((StateSnapshot<Int>) -> Unit)? = null
        var subscriptions = 0
        var releases = 0

        override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
            check(this.observer == null)
            this.observer = observer
            subscriptions += 1
            return StateSubscription(snapshot) {
                releases += 1
                this.observer = null
            }
        }

        /**
         * Publishes a later external revision without running a session operation.
         */
        fun publish(value: Int) {
            snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
            observer?.invoke(snapshot)
        }
    }
}
