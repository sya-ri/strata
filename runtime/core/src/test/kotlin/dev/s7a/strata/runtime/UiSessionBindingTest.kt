package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty

/**
 * Verifies revisioned binding cutoffs, coalescing, retention, and subscription cleanup.
 */
internal class UiSessionBindingTest {
    @Test
    fun queuedSourceUpdatesRemainInvisibleDuringInputGeometrySynchronization() {
        val probe = TestProbe()
        val source = TestSource(StateSnapshot(StateRevision(0), "old"), null)
        val holder = BindingHolder<String>()
        val contentValues = ArrayList<String>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentValues.add(holder.value)
                probe.element(TestProbe.ProbeId(holder.value))
            }
        holder.delegate = session.bind(source)
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        source.publish(StateSnapshot(StateRevision(1), "new"))
        probe.nodeForTag(TestProbe.ProbeId("old")).invalidateForTest(DirtyMask.of(DirtyPhase.Measure))
        session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        assertEquals("old", holder.value)
        assertEquals(listOf("old"), contentValues)
        assertEquals(listOf(TestProbe.ProbeId("old"), TestProbe.ProbeId("old")), probe.inputEvents)
        assertEquals(1, probe.paintCalls)
        val next = session.frame(Constraints.fixed(4, 2))
        assertEquals(
            UiText.Literal("new"),
            next.semantics
                .single()
                .semantics.label,
        )
        assertEquals(listOf("old", "new"), contentValues)
        session.close()
        assertEquals(1, source.closeCount)
    }

    @Test
    fun callbackBeforeReturnIsUsedByTheFirstBuild() {
        val probe = TestProbe()
        val source = TestSource(StateSnapshot(StateRevision(1), "initial"), StateSnapshot(StateRevision(2), "latest"))
        val holder = BindingHolder<String>()
        var contentCalls = 0
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentCalls += 1
                probe.root(listOf(probe.element(TestProbe.ProbeId(holder.value))))
            }
        holder.delegate = session.bind(source)

        session.attach()
        assertEquals(1, contentCalls)
        val frame = session.frame(Constraints.fixed(2, 1))
        val label =
            frame
                .semantics
                .last()
                .semantics
                .label
        assertEquals(UiText.Literal("latest"), label)
        assertEquals(1, contentCalls)
        session.close()
    }

    @Test
    fun arbitraryThreadBurstPublishesOnlyTheNewestValueAndOneRebuild() {
        val probe = TestProbe()
        val source = TestSource(StateSnapshot(StateRevision(0), "zero"), null)
        val holder = BindingHolder<String>()
        var contentCalls = 0
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentCalls += 1
                probe.root(listOf(probe.element(TestProbe.ProbeId(holder.value))))
            }
        holder.delegate = session.bind(source)
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        val task =
            FutureTask<Unit> {
                (1..500).forEach { revision ->
                    source.publish(StateSnapshot(StateRevision(revision.toLong()), revision.toString()))
                }
            }
        val thread = Thread(task)
        thread.start()
        task.get(5, TimeUnit.SECONDS)

        val frame = session.frame(Constraints.fixed(2, 1))
        val label =
            frame
                .semantics
                .last()
                .semantics
                .label
        assertEquals(UiText.Literal("500"), label)
        assertEquals(2, contentCalls)
        session.close()
    }

    @Test
    fun staleAndEqualRevisionsAreIgnored() {
        val probe = TestProbe()
        val source = TestSource(StateSnapshot(StateRevision(1), "one"), null)
        val holder = BindingHolder<String>()
        var contentCalls = 0
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentCalls += 1
                probe.root(listOf(probe.element(TestProbe.ProbeId(holder.value))))
            }
        holder.delegate = session.bind(source)
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        source.publish(StateSnapshot(StateRevision(2), "two"))
        session.frame(Constraints.fixed(2, 1))
        source.publish(StateSnapshot(StateRevision(1), "stale"))
        source.publish(StateSnapshot(StateRevision(2), "equal"))
        session.frame(Constraints.fixed(2, 1))
        assertEquals(2, contentCalls)
        session.close()
    }

    @Test
    fun atomicBindingCutoffKeepsLaterCallbackForTheNextFrame() {
        val binding = UiSessionBinding<Int>({}, {}, {})
        binding.commitInitial(StateSnapshot(StateRevision(1), 1))
        binding.enqueue(StateSnapshot(StateRevision(2), 2))
        binding.capturePending()
        assertTrue(binding.applyPending())
        binding.enqueue(StateSnapshot(StateRevision(3), 3))
        assertEquals(2, bindingValue(binding))
        binding.capturePending()
        assertTrue(binding.applyPending())
        assertEquals(3, bindingValue(binding))
    }

    @Test
    fun aBindingRejectsReadsBeforeItsInitialSnapshot() {
        val binding = UiSessionBinding<Int>({}, {}, {})

        assertThrows(IllegalStateException::class.java) { bindingValue(binding) }
    }

    @Test
    fun installationAfterCleanupClosesTheTransferredHandleImmediately() {
        val disabledBinding = UiSessionBinding<Int>({}, {}, {})
        var disabledCloseCount = 0
        disabledBinding.disable()
        disabledBinding.install(
            StateSubscription(StateSnapshot(StateRevision(1), 1)) { disabledCloseCount += 1 },
        )

        val closeRequestedBinding = UiSessionBinding<Int>({}, {}, {})
        var requestedCloseCount = 0
        assertEquals(null, closeRequestedBinding.closeSubscription())
        closeRequestedBinding.install(
            StateSubscription(StateSnapshot(StateRevision(1), 1)) { requestedCloseCount += 1 },
        )

        assertEquals(1, disabledCloseCount)
        assertEquals(1, requestedCloseCount)
        assertEquals(null, disabledBinding.closeSubscription())
        assertEquals(null, closeRequestedBinding.closeSubscription())
    }

    @Test
    fun detachedPendingValueIsAppliedBeforeOneReattachBuild() {
        val probe = TestProbe()
        val source = TestSource(StateSnapshot(StateRevision(1), "one"), null)
        val holder = BindingHolder<String>()
        var contentCalls = 0
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentCalls += 1
                probe.root(listOf(probe.element(TestProbe.ProbeId(holder.value))))
            }
        holder.delegate = session.bind(source)
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        session.detach()
        source.publish(StateSnapshot(StateRevision(2), "two"))
        session.attach()
        assertEquals(2, contentCalls)
        val frame = session.frame(Constraints.fixed(2, 1))
        val label =
            frame
                .semantics
                .last()
                .semantics
                .label
        assertEquals(UiText.Literal("two"), label)
        assertEquals(2, contentCalls)
        session.close()
    }

    @Test
    fun callbacksAfterCloseAreIgnoredAndSubscriptionsCloseOnce() {
        val probe = TestProbe()
        val source = TestSource(StateSnapshot(StateRevision(1), "one"), null)
        val session = UiSession(TestOwnerDispatcher()) { probe.root(emptyList()) }
        session.bind(source)
        session.close()
        source.publish(StateSnapshot(StateRevision(2), "late"))
        session.close()
        assertEquals(1, source.closeCount)
    }

    @Test
    fun closeIsAnIdempotentNoOpWhenASubscriptionReentersCleanup() {
        var closeCount = 0
        lateinit var session: UiSession
        val source =
            StateSource<Int> { _ ->
                StateSubscription(StateSnapshot(StateRevision(1), 1)) {
                    closeCount += 1
                    session.close()
                }
            }
        session = UiSession(TestOwnerDispatcher()) { TestProbe().root(emptyList()) }
        session.bind(source)

        session.close()
        session.close()

        assertEquals(UiSessionState.Closed, session.lifecycleState)
        assertEquals(1, closeCount)
    }

    @Test
    fun bindingCloseFailuresContinueInDeclarationOrder() {
        val firstFailure = IllegalStateException("first close")
        val secondFailure = IllegalStateException("second close")
        val first = TestSource(StateSnapshot(StateRevision(1), 1), null, firstFailure)
        val second = TestSource(StateSnapshot(StateRevision(1), 2), null, secondFailure)
        val session = UiSession(TestOwnerDispatcher()) { TestProbe().root(emptyList()) }
        session.bind(first)
        session.bind(second)
        val thrown = assertThrows(IllegalStateException::class.java) { session.close() }
        assertSame(firstFailure, thrown)
        assertEquals(listOf(secondFailure), thrown.suppressed.toList())
        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun boundEqualityPublishesAfterCutoffAndRejectsLocalStateReentry() {
        val dispatcher = TestOwnerDispatcher()
        val probe = TestProbe()
        val local = LocalHolder<Int>()
        val bound = BindingHolder<BoundValue>()
        var readRejected = false
        var writeRejected = false
        var published = false
        lateinit var source: TestSource<BoundValue>
        val initial =
            BoundValue("initial") {
                readRejected = runCatching { local.value }.isFailure
                writeRejected = runCatching { local.value = 1 }.isFailure
                if (published.not()) {
                    published = true
                    source.publish(StateSnapshot(StateRevision(3), BoundValue("latest")))
                }
            }
        source = TestSource(StateSnapshot(StateRevision(1), initial), null)
        var contentCalls = 0
        val session =
            UiSession(dispatcher) {
                contentCalls += 1
                probe.root(listOf(probe.element(TestProbe.ProbeId(bound.value.label))))
            }
        local.delegate = session.state(0)
        bound.delegate = session.bind(source)
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        source.publish(StateSnapshot(StateRevision(2), BoundValue("cutoff")))

        val cutoffFrame = session.frame(Constraints.fixed(2, 1))
        val cutoffLabel =
            cutoffFrame
                .semantics
                .last()
                .semantics
                .label
        assertEquals(UiText.Literal("cutoff"), cutoffLabel)
        assertEquals(2, contentCalls)
        assertTrue(readRejected)
        assertTrue(writeRejected)

        val latestFrame = session.frame(Constraints.fixed(2, 1))
        val latestLabel =
            latestFrame
                .semantics
                .last()
                .semantics
                .label
        assertEquals(UiText.Literal("latest"), latestLabel)
        assertEquals(3, contentCalls)
        session.close()
    }

    @Test
    fun caughtBindEstablishmentReentryLeavesTheSessionCreated() {
        val dispatcher = TestOwnerDispatcher()
        lateinit var session: UiSession
        var closeRejected = false
        var bindRejected = false
        var stateRejected = false
        val source =
            object : StateSource<Int> {
                override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
                    closeRejected = runCatching { session.close() }.isFailure
                    bindRejected = runCatching { session.bind(this) }.isFailure
                    stateRejected = runCatching { session.state(1) }.isFailure
                    observer(StateSnapshot(StateRevision(1), 1))
                    return StateSubscription(StateSnapshot(StateRevision(1), 1)) {}
                }
            }
        session = UiSession(dispatcher) { TestProbe().root(emptyList()) }

        session.bind(source)

        assertTrue(closeRejected)
        assertTrue(bindRejected)
        assertTrue(stateRejected)
        assertEquals(UiSessionState.Created, session.lifecycleState)
        session.attach()
        session.close()
    }

    @Test
    fun propagatedBindEstablishmentFailureReleasesGuardsForRetry() {
        val dispatcher = TestOwnerDispatcher()
        lateinit var session: UiSession
        var unavailableHandleCreated = false
        val reentrantSource =
            object : StateSource<Int> {
                override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
                    session.state(1)
                    unavailableHandleCreated = true
                    return StateSubscription(StateSnapshot(StateRevision(1), 1)) {}
                }
            }
        session = UiSession(dispatcher) { TestProbe().root(emptyList()) }

        assertThrows(IllegalStateException::class.java) { session.bind(reentrantSource) }

        assertTrue(unavailableHandleCreated.not())
        assertEquals(UiSessionState.Created, session.lifecycleState)
        var closeCount = 0
        val validSource =
            StateSource<Int> { _ ->
                StateSubscription(StateSnapshot(StateRevision(1), 1)) { closeCount += 1 }
            }
        session.bind(validSource)
        session.attach()
        session.close()
        assertEquals(1, closeCount)
    }

    private fun bindingValue(binding: UiSessionBinding<Int>): Int {
        val holder = BindingHolder<Int>()
        holder.delegate = binding
        return holder.value
    }

    private class BindingHolder<T> {
        lateinit var delegate: ReadOnlyProperty<Any?, T>

        val value: T
            get() = delegate.getValue(this, BindingHolder<T>::value)
    }

    private class LocalHolder<T> {
        lateinit var delegate: ReadWriteProperty<Any?, T>

        var value: T
            get() = delegate.getValue(this, LocalHolder<T>::value)
            set(next) = delegate.setValue(this, LocalHolder<T>::value, next)
    }

    private class BoundValue(
        val label: String,
        private val action: () -> Unit = {},
    ) {
        override fun equals(other: Any?): Boolean {
            action()
            return other is BoundValue && label == other.label
        }

        override fun hashCode(): Int = label.hashCode()
    }

    private class TestSource<T>(
        private val initial: StateSnapshot<T>,
        private val callbackBeforeReturn: StateSnapshot<T>?,
        private val closeFailure: Throwable? = null,
    ) : StateSource<T> {
        private var observer: ((StateSnapshot<T>) -> Unit)? = null
        var closeCount: Int = 0

        override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
            this.observer = observer
            callbackBeforeReturn?.let(observer)
            return StateSubscription(initial) {
                closeCount += 1
                closeFailure?.let { failure -> throw failure }
            }
        }

        fun publish(snapshot: StateSnapshot<T>) {
            observer?.invoke(snapshot)
        }
    }
}
