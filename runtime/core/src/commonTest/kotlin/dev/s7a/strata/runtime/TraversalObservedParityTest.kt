package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.TraversalTestFixture.Event
import dev.s7a.strata.runtime.TraversalTestFixture.Kind
import dev.s7a.strata.runtime.TraversalTestFixture.Phase
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Preserves authoritative source cutoffs and localized/full-update behavior through the shared runtime bridge.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class TraversalObservedParityTest {
    @Test
    fun localizedSourcesAndLeafGeometryPreserveBroadDeepAndFullUpdateControls() {
        for (depth in listOf(0, 32)) {
            for (participants in listOf(0, 8, 256)) {
                val fixture = TraversalTestFixture()
                val sources = List(8) { Source(0) }
                val calls = IntArray(sources.size)
                val generation = mutableStateOf(0)
                val session = createRuntimeUiSession { description(fixture, sources, calls, participants, depth, generation.value) }
                try {
                    session.attach()
                    val initial = session.frame(Constraints.fixed(20, 20))
                    assertEquals(List(8) { 1 }, calls.toList())
                    assertEquals(participants, initial.semantics.size)
                    assertSame(initial, session.frame(Constraints.fixed(20, 20)))
                    sources[3].publish(1)
                    val updated = session.frame(Constraints.fixed(20, 20))
                    assertEquals(List(8) { if (it == 3) 2 else 1 }, calls.toList())
                    assertEquals(participants, updated.semantics.size)
                    fixture.nodes.getValue(256).dirty()
                    session.frame(Constraints.fixed(20, 20))
                    assertEquals(List(8) { if (it == 3) 2 else 1 }, calls.toList())
                    generation.value = 1
                    val full = session.frame(Constraints.fixed(20, 20))
                    assertEquals(List(8) { if (it == 3) 3 else 2 }, calls.toList())
                    assertEquals(participants, full.semantics.size)
                    assertTrue(sources.all { it.active == 1 })
                } finally {
                    session.close()
                }
                assertTrue(sources.all { it.active == 0 && it.releases == 1 })
            }
        }
    }

    @Test
    fun publicationInsideLayoutRemainsQueuedForTheNextAuthoritativeCutoff() {
        val fixture = TraversalTestFixture()
        val source = Source(0)
        val seen = ArrayList<Int>()
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Observe(source) { value ->
                        seen.add(value)
                        element(fixture.element(1, Kind.Participant))
                    }
                }
            }
        val event = Event(Phase.Layout, 1)
        fixture.callbacks[event] = {
            source.publish(1)
            fixture.callbacks.remove(event)
        }
        try {
            session.attach()
            session.frame(Constraints.fixed(20, 20))
            assertEquals(listOf(0), seen)
            session.frame(Constraints.fixed(20, 20))
            assertEquals(listOf(0, 1), seen)
            assertEquals(1, source.active)
        } finally {
            session.close()
        }
        assertEquals(0, source.active)
    }

    private fun description(
        fixture: TraversalTestFixture,
        sources: List<Source>,
        calls: IntArray,
        participants: Int,
        depth: Int,
        generation: Int,
    ): Element {
        val children =
            List(256) { ordinal ->
                val id = ordinal + 1
                val kind = if (ordinal < participants) Kind.Participant else Kind.Plain
                if (ordinal < sources.size) {
                    evaluateComponentTree {
                        Observe(sources[ordinal]) { value ->
                            calls[ordinal] += 1
                            element(fixture.element(id, kind, width = 4 + (value + generation) % 2))
                        }
                    }
                } else {
                    fixture.element(id, kind, width = 4 + generation % 2)
                }
            }
        var root: Element = fixture.element(0, children = children)
        repeat(depth) { root = fixture.element(1000 + it, children = listOf(root, fixture.element(2000 + it))) }
        return root
    }

    /**
     * Owner-thread source; callbacks only enqueue immutable revisions into the runtime.
     */
    private class Source(
        initial: Int,
    ) : StateSource<Int> {
        private var snapshot = StateSnapshot(StateRevision(0), initial)
        private var observer: ((StateSnapshot<Int>) -> Unit)? = null

        /**
         * Current external subscription count.
         */
        val active: Int get() = if (observer == null) 0 else 1

        /**
         * Completed independent release count.
         */
        var releases: Int = 0
            private set

        override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
            check(this.observer == null)
            this.observer = observer
            return StateSubscription(snapshot) {
                this.observer = null
                releases += 1
            }
        }

        /**
         * Publishes one newer revision under the source's test execution owner.
         */
        fun publish(value: Int) {
            snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
            observer?.invoke(snapshot)
        }
    }
}
