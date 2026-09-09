package dev.s7a.strata

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.map
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises lazy mapping, ordinary revision delivery, and close racing an in-flight projection.
 */
internal class MappedStateSourceTest {
    @Test
    fun constructionIsLazyAndSubscriptionsPreserveNullableInitialAndEveryRevision() {
        var subscriptions = 0
        var releases = 0
        var transforms = 0
        val source =
            StateSource<Int?> { observer ->
                subscriptions += 1
                observer(StateSnapshot(StateRevision(1), 1))
                observer(StateSnapshot(StateRevision(2), 3))
                StateSubscription(StateSnapshot(StateRevision(0), null)) { releases += 1 }
            }
        val mapped =
            source
                .map {
                    transforms += 1
                    it?.rem(2)
                }.map { it?.toString() }
        assertEquals(0, subscriptions)
        assertEquals(0, transforms)
        val delivered = ArrayList<StateSnapshot<String?>>()
        mapped.subscribe(delivered::add).use { subscription ->
            assertEquals(StateSnapshot<String?>(StateRevision(0), null), subscription.initialSnapshot)
            assertEquals(listOf(StateSnapshot(StateRevision(1), "1"), StateSnapshot(StateRevision(2), "1")), delivered)
        }
        assertEquals(1, subscriptions)
        assertEquals(1, releases)
        assertEquals(3, transforms)
    }

    @Test
    fun initialTransformFailureClosesUpstreamAndPreservesCleanupFailure() {
        val failure = IllegalArgumentException("transform")
        val cleanup = IllegalStateException("close")
        val releases = AtomicInteger()
        val source =
            StateSource<Int> {
                StateSubscription(StateSnapshot(StateRevision(0), 0)) {
                    releases.incrementAndGet()
                    throw cleanup
                }
            }
        val mapped = source.map<Int, String> { throw failure }
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { mapped.subscribe { } })
        assertEquals(listOf(cleanup), failure.suppressed.toList())
        assertEquals(1, releases.get())
    }

    @Test
    fun closePreventsInFlightTransformFromStartingDelivery() {
        val transforming = CountDownLatch(1)
        val finishTransform = CountDownLatch(1)
        var callback: ((StateSnapshot<Int>) -> Unit)? = null
        val source =
            StateSource<Int> { observer ->
                callback = observer
                StateSubscription(StateSnapshot(StateRevision(0), 0)) { callback = null }
            }
        val mapped =
            source.map { value ->
                if (value == 1) {
                    transforming.countDown()
                    check(finishTransform.await(5, TimeUnit.SECONDS))
                }
                value.toString()
            }
        val calls = AtomicInteger()
        val subscription = mapped.subscribe { calls.incrementAndGet() }
        val deliver = checkNotNull(callback)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit { deliver(StateSnapshot(StateRevision(1), 1)) }
            assertTrue(transforming.await(5, TimeUnit.SECONDS))
            subscription.close()
            finishTransform.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertEquals(0, calls.get())
        } finally {
            finishTransform.countDown()
            subscription.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
