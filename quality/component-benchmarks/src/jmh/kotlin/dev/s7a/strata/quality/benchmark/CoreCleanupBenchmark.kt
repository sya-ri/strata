package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.TreeState
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

/**
 * Measures complete retained-tree close and reconciliation removal through actual lifecycle cleanup owners.
 * Declaration, node, binding and tree allocations remain timed identically on both runtime revisions.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class CoreCleanupBenchmark {
    /**
     * Attaches one independent tree and completes its terminal close, including failure aggregation.
     */
    @Benchmark
    public fun closeTree(state: Cleanup): Throwable? = state.execute(remove = false)

    /**
     * Removes the current siblings by reconciliation and then closes the remaining or poisoned tree.
     */
    @Benchmark
    public fun reconcileRemoval(state: Cleanup): Throwable? = state.execute(remove = true)

    /**
     * Complete fixed success and failure workloads for the retained owner boundary.
     *
     * @property owners exact lifecycle participants, excluding the capability-free container.
     */
    public enum class Workload(
        public val owners: Int,
    ) {
        /**
         * An empty tree with no claimed node.
         */
        CleanEmpty(0),

        /**
         * One attached lifecycle owner.
         */
        CleanOne(1),

        /**
         * A successful fan-out of independent lifecycle owners.
         */
        CleanMany(64),

        /**
         * One failing owner followed by independent successful cleanup.
         */
        Single(64),

        /**
         * Three different failures in reverse sibling order.
         */
        Distinct(64),

        /**
         * Three owners and both cleanup callbacks throw the same failure instance.
         */
        Identical(64),

        /**
         * Two identity-distinct exceptions compare equal.
         */
        EqualDistinct(64),

        /**
         * A later failure carries a nested failure also thrown by another owner.
         */
        Nested(64),
    }

    /**
     * Holds only the most recent closed tree, retired nodes and exception graph for untimed verification.
     */
    @State(Scope.Thread)
    public open class Cleanup {
        /**
         * Fixed lifecycle-owner count and exception graph selected by JMH.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.CleanEmpty
        private var nodes = emptyList<CleanupNode>()
        private var failures = emptyList<Throwable>()
        private var tree: UiTree? = null
        private var result: Throwable? = null
        private var attaches = 0
        private var detaches = 0
        private var disposes = 0
        private var previousOrdinal = Int.MAX_VALUE
        private var reverseOrder = true

        /**
         * Completes either owner boundary, preserving the first cleanup failure and all later callbacks.
         */
        public fun execute(remove: Boolean): Throwable? {
            attaches = 0
            detaches = 0
            disposes = 0
            previousOrdinal = Int.MAX_VALUE
            reverseOrder = true
            failures = errors()
            nodes = List(workload.owners) { ordinal -> CleanupNode(this, ordinal, failures.getOrNull(workload.owners - 1 - ordinal)) }
            val current = UiTree()
            tree = current
            if (nodes.isNotEmpty()) current.update(RootElement(nodes.map(::LeafElement)))
            result =
                runCatching {
                    if (remove && nodes.isNotEmpty()) current.update(RootElement(emptyList())) else current.close()
                }.exceptionOrNull()
            current.close()
            return result
        }

        /**
         * Requires complete callback work, reverse cleanup order, exact throwable graph and permanently retired nodes.
         */
        public fun verify() {
            check(attaches == workload.owners && detaches == workload.owners && disposes == workload.owners)
            check(reverseOrder && tree?.state === TreeState.Closed)
            nodes.forEach { node ->
                check(runCatching(node::invalidateRetired).exceptionOrNull() is IllegalStateException)
                check(runCatching { node.bindRuntime(callback = { }) }.exceptionOrNull() is IllegalStateException)
            }
            if (failures.isEmpty()) {
                check(result == null)
                return
            }
            check(result === failures.first())
            val expected =
                when (workload) {
                    Workload.Distinct, Workload.Nested -> listOf(failures[1], failures[2])
                    Workload.EqualDistinct -> listOf(failures[1])
                    else -> emptyList()
                }
            val suppressed = checkNotNull(result).suppressed
            check(suppressed.size == expected.size)
            expected.forEachIndexed { index, failure -> check(suppressed[index] === failure) }
            if (workload == Workload.Nested) check(failures[1].suppressed.single() === failures[2])
        }

        private fun errors(): List<Throwable> =
            when (workload) {
                Workload.CleanEmpty, Workload.CleanOne, Workload.CleanMany -> emptyList()
                Workload.Single -> listOf(Failure())
                Workload.Distinct -> List(3) { Failure() }
                Workload.Identical -> Failure().let { listOf(it, it, it) }
                Workload.EqualDistinct -> listOf(EqualFailure(), EqualFailure())
                Workload.Nested -> List(3) { Failure() }.also { it[1].addSuppressed(it[2]) }
            }

        private class CleanupNode(
            private val owner: Cleanup,
            private val ordinal: Int,
            private val failure: Throwable?,
        ) : Node(),
            LifecycleNode {
            override fun attach() {
                owner.attaches += 1
            }

            override fun detach() {
                owner.detaches += 1
                if ((ordinal < owner.previousOrdinal).not()) owner.reverseOrder = false
                owner.previousOrdinal = ordinal
                failure?.let { throw it }
            }

            override fun dispose() {
                owner.disposes += 1
                failure?.let { throw it }
            }

            /**
             * Probes that terminal cleanup dropped this node's active invalidation binding.
             */
            fun invalidateRetired() {
                invalidate(DirtyMask.None)
            }
        }

        private class LeafElement(
            val node: CleanupNode,
        ) : Element(ElementIdentity.Positional, TYPE) {
            companion object {
                val TYPE: ElementType<LeafElement, CleanupNode> =
                    ElementType(
                        elementClass = LeafElement::class,
                        nodeClass = CleanupNode::class,
                        validateLocal = { _ -> },
                        createNode = { it.node },
                        updateNode = { _, _, _ -> DirtyMask.None },
                    )
            }
        }
    }

    /**
     * Verifies the entire lifecycle matrix through the generic automatic work hook.
     */
    public companion object {
        /**
         * Checks both operation boundaries and repeated terminal close for each compiled control.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(CoreCleanupBenchmark::class.java), setOf("avgt")).size == 16)
            for (workload in Workload.entries) {
                for (remove in listOf(false, true)) {
                    val state = Cleanup().apply { this.workload = workload }
                    repeat(128) {
                        state.execute(remove)
                        state.verify()
                    }
                    println("core-cleanup,workload=$workload,remove=$remove,owners=${workload.owners},detach=${workload.owners},dispose=${workload.owners}")
                }
            }
        }
    }

    private class RootNode : Node()

    private class RootElement(
        children: List<Element>,
    ) : Element(ElementIdentity.Positional, TYPE, children = children) {
        companion object {
            val TYPE: ElementType<RootElement, RootNode> =
                ElementType(
                    elementClass = RootElement::class,
                    nodeClass = RootNode::class,
                    validateLocal = { _ -> },
                    createNode = { RootNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class Failure : RuntimeException("core cleanup fixture", null, true, false)

    private class EqualFailure : RuntimeException("equal core cleanup fixture", null, true, false) {
        override fun equals(other: Any?): Boolean = other is EqualFailure

        override fun hashCode(): Int = 0
    }
}
