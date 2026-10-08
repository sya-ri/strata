@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.mutableStateOf
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Actual private routing storage, immediate stale release, closed-owner collection and serial-owner isolation.
 */
internal class MutableStatePlanRetentionTest {
    @Test
    fun zeroAndSingletonPlansHaveExactBoundsAndDuplicateChangesPreserveTheAdmittedIdentity() {
        val state = mutableStateOf(0)
        repeat(64) { state.value = 0 }
        assertNull(plan(state))
        var begins = 0
        var ends = 0
        val observer = StateObservation({ begins += 1 }, { ends += 1 }, {}, {})
        val unrelated = StateObservation({}, {}, {}, {})
        try {
            observer.evaluate { repeat(4) { state.value } }
            assertNull(plan(state))
            state.value = 0
            val admitted = checkNotNull(plan(state))
            assertEquals(listOf(observer), listField(admitted, "observations"))
            assertEquals(listOf(observer), listField(admitted, "owners"))
            repeat(64) { state.value = 0 }
            assertSame(admitted, plan(state))
            state.observe(observer)
            state.forget(unrelated)
            assertSame(admitted, plan(state))
            assertEquals(65, begins)
            assertEquals(65, ends)
        } finally {
            unrelated.close()
            observer.close()
        }
        assertNull(plan(state))
        assertEquals(emptySet(), field(state, "observations"))
        state.value = 1
        assertEquals(1, state.value)
        assertNull(plan(state))
    }

    @Test
    fun wideSharedOwnerPlansReuseExactCurrentListsAndSparseRemovalRebuildsOnlyAfterAnAssignment() {
        for (count in listOf(128, 4_096)) {
            val state = mutableStateOf(0)
            var invalidations = 0
            val root = StateObservation({}, {}, {}, {})
            val observations = List(count) { root.fork { invalidations += 1 }.also { it.evaluate { state.value } } }
            try {
                assertNull(plan(state))
                state.value = 0
                val admitted = checkNotNull(plan(state))
                assertEquals(observations, listField(admitted, "observations"))
                assertEquals(listOf(root), listField(admitted, "owners"))
                repeat(64) {
                    state.value = 0
                    assertSame(admitted, plan(state))
                }
                assertEquals(0, invalidations)
                state.value = 1
                assertEquals(count, invalidations)
                val removed = observations.filterIndexed { index, _ -> index % 17 == 0 }
                removed.forEach {
                    it.close()
                    assertNull(plan(state))
                }
                val remaining = observations.filter { (it in removed).not() }
                state.value = 1
                val rebuilt = checkNotNull(plan(state))
                assertNotSame(admitted, rebuilt)
                assertEquals(remaining, listField(rebuilt, "observations"))
                assertEquals(listOf(root), listField(rebuilt, "owners"))
                assertEquals(observations, listField(admitted, "observations"))
            } finally {
                root.close()
            }
            assertNull(plan(state))
            assertEquals(emptySet(), field(state, "observations"))
        }
    }

    @Test
    fun firstObserverRemovalImmediatelyDropsThePlanAndReversesTheNextOwnerProjection() {
        val state = mutableStateOf(0)
        val firstRoot = StateObservation({}, {}, {}, {})
        val secondRoot = StateObservation({}, {}, {}, {})
        val first = firstRoot.fork {}
        val middle = secondRoot.fork {}
        val last = firstRoot.fork {}
        try {
            listOf(first, middle, last).forEach { it.evaluate { state.value } }
            state.value = 0
            assertEquals(listOf(firstRoot, secondRoot), listField(checkNotNull(plan(state)), "owners"))
            first.close()
            assertNull(plan(state))
            state.value = 0
            val admitted = checkNotNull(plan(state))
            assertEquals(listOf(middle, last), listField(admitted, "observations"))
            assertEquals(listOf(secondRoot, firstRoot), listField(admitted, "owners"))
            val replacement = firstRoot.fork {}
            replacement.evaluate { state.value }
            assertNull(plan(state))
            state.value = 0
            assertEquals(listOf(middle, last, replacement), listField(checkNotNull(plan(state)), "observations"))
        } finally {
            secondRoot.close()
            firstRoot.close()
        }
        assertNull(plan(state))
    }

    @Test
    fun disjointOwnerHistoriesLeaveNoRetiredPlanOrContainerAndDoNotRequireAnotherWrite() {
        val state = mutableStateOf(0)
        repeat(256) {
            val root = StateObservation({}, {}, {}, {})
            repeat(8) { root.fork {}.evaluate { state.value } }
            state.value = 0
            val admitted = checkNotNull(plan(state))
            assertEquals(8, listField(admitted, "observations").size)
            assertEquals(1, listField(admitted, "owners").size)
            root.close()
            assertNull(plan(state))
            assertEquals(emptySet(), field(state, "observations"))
        }
        assertNull(plan(state))
    }

    @Test
    fun anOngoingAttemptAloneKeepsItsSnapshotAfterGuardDrivenRemoval() {
        val state = mutableStateOf(0)
        var remove = false
        lateinit var first: StateObservation
        val root =
            StateObservation({
                if (remove) {
                    remove = false
                    first.close()
                    assertNull(plan(state))
                }
            }, {}, {}, {})
        first = root.fork {}
        val second = root.fork {}
        try {
            first.evaluate { state.value }
            second.evaluate { state.value }
            state.value = 0
            val captured = checkNotNull(plan(state))
            remove = true
            state.value = 1
            assertNull(plan(state))
            assertEquals(listOf(first, second), listField(captured, "observations"))
            state.value = 1
            assertEquals(listOf(second), listField(checkNotNull(plan(state)), "observations"))
        } finally {
            root.close()
        }
        assertNull(plan(state))
    }

    @Test
    fun closedObservationRootAndCallbackAreCollectedWhileTheStateRemainsAliveWithoutFurtherAssignment() {
        val state = mutableStateOf(0)
        val retired = retireObservedOwner(state)
        assertNull(plan(state))
        assertEquals(emptySet(), field(state, "observations"))
        repeat(12) {
            System.gc()
            if (retired.all { it.get() == null }) {
                assertEquals(0, state.value)
                return
            }
            Thread.sleep(10)
        }
        assertTrue(retired.all { it.get() == null }, "Closed routing must not retain its root, observation or callback payload")
        assertEquals(0, state.value)
    }

    @Test
    fun serialOwnersMigrateTheirOwnPlanAndRejectForeignOwnersWithoutSharingRouting() {
        val owner = RuntimeExecutionOwner()
        val otherOwner = RuntimeExecutionOwner()
        val executor = Executors.newFixedThreadPool(2)
        var constructionThread: Thread? = null
        val first =
            executor.submit<Pair<MutableState<Int>, StateObservation>> {
                owner.run {
                    constructionThread = Thread.currentThread()
                    val state = mutableStateOf(0)
                    val observation = StateObservation({}, {}, {}, {})
                    observation.evaluate { state.value }
                    state.value = 0
                    state to observation
                }
            }.get(5, TimeUnit.SECONDS)
        val admitted = checkNotNull(plan(first.first))
        try {
            executor.submit {
                owner.run {
                    assertNotSame(constructionThread, Thread.currentThread())
                    repeat(64) { first.first.value = 0 }
                    assertSame(admitted, plan(first.first))
                    otherOwner.run { assertFailsWith<IllegalStateException> { first.first.value = 1 } }
                    assertSame(admitted, plan(first.first))
                    first.first.value = 1
                    assertEquals(1, first.first.value)
                }
            }.get(5, TimeUnit.SECONDS)
            assertFailsWith<IllegalStateException> { first.first.value }
            assertFailsWith<IllegalStateException> { first.first.value = 2 }
            owner.run {
                val independent = mutableStateOf(0)
                val observation = StateObservation({}, {}, {}, {})
                try {
                    observation.evaluate { independent.value }
                    independent.value = 0
                    assertNotSame(admitted, plan(independent))
                } finally {
                    observation.close()
                }
            }
        } finally {
            owner.run { first.second.close() }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertNull(plan(first.first))
    }

    private fun retireObservedOwner(state: MutableState<Int>): List<WeakReference<*>> {
        val payload = Payload()
        val root = StateObservation({ payload.calls += 1 }, {}, {}, {})
        val child = root.fork { payload.calls += 1 }
        child.evaluate { state.value }
        state.value = 0
        val references = listOf(WeakReference(root), WeakReference(child), WeakReference(payload))
        root.close()
        return references
    }

    private fun plan(state: MutableState<*>): Any? = field(state, "observationPlan")

    private fun listField(
        instance: Any,
        name: String,
    ): List<*> = field(instance, name) as List<*>

    private fun field(
        instance: Any,
        name: String,
    ): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }

    /**
     * Callback-only payload absent from the authoritative primitive state value.
     */
    private class Payload {
        var calls = 0
    }
}
