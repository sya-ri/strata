package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Exercises observation through the retained session, including nested reconciliation and input-independent frames.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObserveRegionTest {
    @Test
    fun sharedSourceAcrossRepeatedArgumentsAndNestedRegionsUsesOneSnapshotAndOneSubscription() {
        val source = ObserveTestSource(1)
        val seen = ArrayList<String>()
        session {
            Observe(source, source) { left, right ->
                seen.add("parent:$left:$right")
                Observe(source) { child ->
                    seen.add("child:$left:$child")
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            assertEquals(listOf("parent:1:1", "child:1:1"), seen)
            assertEquals(1, source.subscriptions)
            source.publish(2)
            source.publish(3)
            session.frame(Constraints())
            session.frame(Constraints())
            assertEquals(listOf("parent:1:1", "child:1:1", "parent:3:3", "child:3:3"), seen)
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun bothSourcesChangeBeforeOneFrameAndChildUsesParentsFinalCapturedValue() {
        val parent = ObserveTestSource("old")
        val child = ObserveTestSource(1)
        val seen = ArrayList<String>()
        session {
            Observe(parent) { prefix ->
                Observe(child) { number ->
                    seen.add("$prefix:$number")
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            parent.publish("new")
            child.publish(2)
            session.frame(Constraints())
            parent.publish("captured")
            session.frame(Constraints())
            assertEquals(listOf("old:1", "new:2", "captured:2"), seen)
            assertEquals(1, child.subscriptions)
        }
    }

    @Test
    fun deletingADirtyChildCancelsItsEvaluationAndReleasesItsSource() {
        val visible = ObserveTestSource(true)
        val child = ObserveTestSource(0)
        var calls = 0
        session {
            Observe(visible) { show ->
                if (show) {
                    Observe(child) {
                        calls += 1
                        Spacer()
                    }
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            visible.publish(false)
            child.publish(1)
            assertEquals(IntSize.Zero, session.frame(Constraints()).size)
            assertEquals(1, calls)
            assertEquals(1, child.releases)
        }
    }

    @Test
    fun sourceReplacementKeepsTheRegionAndClosesOnlyTheOldSource() {
        val select = ObserveTestSource(true)
        val first = ObserveTestSource("first")
        val second = ObserveTestSource("second")
        val seen = ArrayList<String>()
        session {
            Observe(select) { useFirst ->
                Observe(if (useFirst) first else second, key = ElementKey(Unit)) { value ->
                    seen.add(value)
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            select.publish(false)
            session.frame(Constraints())
            assertEquals(listOf("first", "second"), seen)
            assertEquals(1, first.releases)
            assertEquals(0, second.releases)
        }
        assertEquals(1, second.releases)
    }

    @Test
    fun compatibleChildrenRetainIdentityWhenTheirParentRegionIsReevaluated() {
        val source = ObserveTestSource(0)
        val probe = TestProbe()
        val key = TestProbe.ProbeId("editor")
        session {
            Observe(source) { value -> element(probe.element(TestProbe.ProbeId("value:$value"), key)) }
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(2, 1))
            val retained = probe.created.single()
            source.publish(1)
            session.frame(Constraints.fixed(2, 1))
            assertEquals(1, probe.created.size)
            assertSame(retained, probe.created.single())
            assertEquals(TestProbe.ProbeId("value:1"), retained.tag)
        }
    }

    @Test
    fun backgroundBurstCoalescesAndEqualValuesReuseTheCommittedFrame() {
        val source = ObserveTestSource(0)
        var calls = 0
        session {
            Observe(source) {
                calls += 1
                Spacer()
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            val task = FutureTask { (1..100).forEach(source::publish) }
            Thread(task).start()
            task.get(5, TimeUnit.SECONDS)
            session.frame(Constraints())
            val stable = session.frame(Constraints())
            source.publish(100)
            assertSame(stable, session.frame(Constraints()))
            assertEquals(2, calls)
        }
    }

    @Test
    fun emptyContentRespectsParentConstraintsAndMultipleRootsFailWithCleanup() {
        val source = ObserveTestSource(0)
        session { Observe(source) {} }.use { session ->
            session.attach()
            assertEquals(IntSize(5, 7), session.frame(Constraints.fixed(5, 7)).size)
        }
        session {
            Observe(source) {
                Spacer()
                Spacer()
            }
        }.use { session ->
            session.attach()
            assertThrows(IllegalArgumentException::class.java) { session.frame(Constraints()) }
        }
        assertEquals(source.subscriptions, source.releases)
    }

    @Test
    fun regionOwnsParentDataAndReevaluationCreatesFreshInnerLayoutScopes() {
        val source = ObserveTestSource(1)
        session {
            Row {
                Observe(source, modifier = Modifier.Empty.weight(1f)) {
                    Column {
                        Spacer(modifier = Modifier.Empty.weight(1f))
                    }
                }
            }
        }.use { session ->
            session.attach()
            assertEquals(IntSize(10, 20), session.frame(Constraints.fixed(10, 20)).size)
            source.publish(2)
            assertEquals(IntSize(10, 20), session.frame(Constraints.fixed(10, 20)).size)
        }
    }

    @Test
    fun failureAndSeparateSessionsReleaseExactlyTheirOwnSharedSubscriptions() {
        val source = ObserveTestSource(0)
        val first = session { Observe(source) { Spacer() } }
        val second =
            session {
                Observe(source) { value ->
                    check(value == 0) { "failed content" }
                    Spacer()
                }
            }
        first.attach()
        second.attach()
        first.frame(Constraints())
        second.frame(Constraints())
        assertEquals(2, source.subscriptions)
        source.publish(1)
        assertThrows(IllegalStateException::class.java) { second.frame(Constraints()) }
        assertEquals(1, source.releases)
        first.frame(Constraints())
        first.close()
        second.close()
        assertEquals(2, source.releases)
    }

    @Test
    fun sourcePublishedDuringLayoutIsDeferredUntilTheNextFrame() {
        val source = ObserveTestSource(0)
        val probe = TestProbe()
        val seen = ArrayList<Int>()
        session {
            Observe(source) { value ->
                seen.add(value)
                element(probe.element(TestProbe.ProbeId("value:$value"), onMeasure = { source.publish(1) }))
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(2, 1))
            assertEquals(listOf(0), seen)
            session.frame(Constraints.fixed(2, 1))
            assertEquals(listOf(0, 1), seen)
            assertTrue(source.subscriptions == 1)
        }
    }

    private fun session(content: UiScope.() -> Unit): UiSession = UiSession(TestOwnerDispatcher()) { evaluateComponentTree(content) }
}
