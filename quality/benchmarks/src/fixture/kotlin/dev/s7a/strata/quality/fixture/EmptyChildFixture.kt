@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.fixture

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.quality.fixture.EmptyChildProbe.Role
import dev.s7a.strata.quality.fixture.EmptyChildWorkload.Operation
import dev.s7a.strata.quality.fixture.EmptyChildWorkload.Payload
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * One private invocation tree/session with identical declaration construction and collector settings on both archives.
 * Construct and settle outside sampling, call [execute] once inside sampling, then close outside sampling.
 */
public class EmptyChildFixture(
    /**
     * Fixed typed case; collector selection supplies the other matrix dimension.
     */
    public val workload: EmptyChildWorkload,
    monitoring: Boolean,
) : AutoCloseable {
    /**
     * Independent callback counter owner used in qualification and measured work.
     */
    public val probe: EmptyChildProbe = EmptyChildProbe()
    private val constraints = Constraints(maxWidth = 16_384, maxHeight = 4)
    private val source = Source(false)
    private val tree: UiTree?
    private val session: RuntimeUiSession?
    private val initial: Element

    /**
     * Retained diagnostics, absent for collector-disabled cases.
     */
    public val monitor: UiRenderMonitor?

    /**
     * Initial settled immutable frame for exact clean-frame identity assertions.
     */
    public val initialFrame: RuntimeUiFrame?

    init {
        initial = declaration(false)
        when (workload.operation) {
            Operation.DirectTreeUpdate, Operation.SameDescriptionTreeUpdate -> {
                tree = UiTree()
                session = null
                monitor = if (monitoring) tree.startRenderMonitoring(16_384) else null
                tree.update(initial)
                settle()
                initialFrame = null
            }

            Operation.ObservedRegionFrame, Operation.CleanSessionFrame -> {
                tree = null
                session =
                    createRuntimeUiSession {
                        evaluateComponentTree {
                            Observe(source) { changed -> element(declaration(changed)) }
                        }
                    }
                monitor = if (monitoring) session.startRenderMonitoring(16_384) else null
                session.attach()
                initialFrame = session.frame(constraints)
            }
        }
        probe.checkpoint()
        monitor?.checkpoint()
    }

    /**
     * Executes the entire declared public operation; fresh construction is included for real updates.
     */
    public fun execute(): Any =
        when (workload.operation) {
            Operation.DirectTreeUpdate -> {
                checkNotNull(tree).also { it.update(declaration(true)) }
            }

            Operation.SameDescriptionTreeUpdate -> {
                checkNotNull(tree).also { it.update(initial) }
            }

            Operation.ObservedRegionFrame -> {
                source.publish(true)
                checkNotNull(session).frame(constraints)
            }

            Operation.CleanSessionFrame -> {
                checkNotNull(session).frame(constraints)
            }
        }

    /**
     * Runs direct-tree presentation after qualification, outside the direct-update timing boundary.
     */
    public fun settle() {
        tree?.let {
            it.measure(constraints)
            it.layout()
            it.paint()
            it.semantics()
        }
    }

    /**
     * Returns immutable independent observations for matching baseline/candidate qualification.
     */
    public fun observations(): List<Int> = EmptyChildProbe.Stage.entries.map(probe::count)

    /**
     * Whether the current source retains its one allowed upstream observer.
     */
    public val subscribed: Boolean get() = source.subscribed

    /**
     * Releases the owner, source subscription and collector outside sampled work.
     */
    override fun close() {
        tree?.close()
        session?.close()
        monitor?.close()
        check(source.subscribed.not())
        check(probe.nodes.all { it.disposed })
    }

    private fun declaration(changed: Boolean): Element {
        val payload = if (changed && workload.payload == Payload.Changed) 1 else 0
        val targets =
            List(workload.count) { index ->
                val children =
                    when (workload.payload) {
                        Payload.Changed, Payload.FreshEqual -> emptyList()
                        Payload.EmptyToOne -> if (changed) listOf(probe.element(index + workload.count, 1, role = Role.Descendant)) else emptyList()
                        Payload.OneToEmpty -> if (changed) emptyList() else listOf(probe.element(index + workload.count, role = Role.Descendant))
                        Payload.NonemptyToNonempty -> listOf(probe.element(index + workload.count, if (changed) 1 else 0, role = Role.Descendant))
                    }
                probe.element(index, payload, children, probe.modifier(index))
            }
        return probe.element(-1, children = targets, role = Role.Surrounding)
    }

    /**
     * Single-owner publisher; snapshot construction is included in observed sampling.
     */
    private class Source(
        initial: Boolean,
    ) : StateSource<Boolean> {
        private var snapshot = StateSnapshot(StateRevision(0), initial)
        private var observer: ((StateSnapshot<Boolean>) -> Unit)? = null

        /**
         * Current subscription ownership, inspected outside sampling.
         */
        val subscribed: Boolean get() = observer != null

        override fun subscribe(observer: (StateSnapshot<Boolean>) -> Unit): StateSubscription<Boolean> {
            check(this.observer == null)
            this.observer = observer
            return StateSubscription(snapshot) { this.observer = null }
        }

        /**
         * Publishes a new revision synchronously; content still waits for the frame cutoff.
         */
        fun publish(value: Boolean) {
            snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
            observer?.invoke(snapshot)
        }
    }
}
