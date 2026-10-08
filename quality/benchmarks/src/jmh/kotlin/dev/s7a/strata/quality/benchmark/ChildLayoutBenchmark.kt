package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Measures parent measure/layout bookkeeping while retained leaf measurements stay cached.
 * Dense and sparse scenes use the same current child counts and no paint or semantics payload.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class ChildLayoutBenchmark {
    /**
     * Invalidates the parent geometry and returns one completed retained frame.
     */
    @Benchmark
    public fun geometry(state: Scene): RuntimeUiFrame = state.changedFrame()

    /**
     * Fixed dense and sparse child membership in the separate geometry corpus.
     */
    public enum class Workload(
        public val childCount: Int,
        public val sparse: Boolean,
    ) {
        /**
         * One participating direct child.
         */
        Dense1(1, false),

        /**
         * A representative fully participating row collection.
         */
        Dense128(128, false),

        /**
         * A large fully participating layout with indices beyond the boxed integer cache.
         */
        Dense4096(4_096, false),

        /**
         * Only the first and last children participate in a large retained collection.
         */
        Sparse4096(4_096, true),
    }

    /**
     * One primed owner-thread session per JMH worker; setup and teardown remain outside timings.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Fixed direct-child participation injected by JMH before setup.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.Dense1

        private lateinit var session: RuntimeUiSession
        private lateinit var root: ParentNode
        private val constraints = Constraints.fixed(4_096, 4)

        /**
         * Creates and primes one static current child tree.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            root = ParentNode(workload)
            val element = ParentElement(root, List(workload.childCount) { LeafElement() })
            session = createRuntimeUiSession { element }
            session.attach()
            session.frame(constraints)
            verifyWork()
        }

        /**
         * Invalidates only the parent; each previously measured child reuses its measured result.
         */
        public fun changedFrame(): RuntimeUiFrame {
            root.invalidateGeometry()
            return session.frame(constraints)
        }

        /**
         * Verifies exact callback work and clean frame identity outside collection.
         */
        public fun verifyWork() {
            val expected = if (workload.sparse) 2 else workload.childCount
            check(root.measuredChildren == expected && root.placedChildren == expected)
            repeat(10) {
                root.measuredChildren = 0
                root.placedChildren = 0
                val frame = changedFrame()
                check(root.measuredChildren == expected && root.placedChildren == expected)
                check(frame.drawCommands.isEmpty() && frame.semantics.isEmpty())
                check(session.frame(constraints) === frame)
            }
        }

        /**
         * Releases the current child tree on the owning JMH worker.
         */
        @TearDown(Level.Trial)
        public fun close() {
            session.close()
        }
    }

    /**
     * Runs deterministic current-tree work checks for every compiled geometry fixture.
     */
    public companion object {
        /**
         * Requires exact participation and clean-frame reuse for every scene outside timed invocations.
         */
        public fun verifyWork() {
            for (workload in Workload.entries) {
                val scene = Scene()
                scene.workload = workload
                try {
                    scene.setUp()
                } finally {
                    scene.close()
                }
            }
        }
    }

    /**
     * Parent callback fixture selecting all children or the two distant endpoints.
     */
    private class ParentNode(
        private val workload: Workload,
    ) : Node(),
        MeasureNode,
        LayoutNode {
        var measuredChildren = 0
        var placedChildren = 0
        private val childConstraints = Constraints.fixed(1, 1)

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            if (workload.sparse) {
                scope.measureChild(0, childConstraints)
                scope.measureChild(scope.childCount - 1, childConstraints)
                measuredChildren += 2
            } else {
                for (index in 0 until scope.childCount) {
                    scope.measureChild(index, childConstraints)
                    measuredChildren += 1
                }
            }
            return constraints.constrain(IntSize(4_096, 4))
        }

        override fun layout(scope: LayoutScope) {
            if (workload.sparse) {
                scope.placeChild(0, IntOffset.Zero)
                scope.placeChild(scope.childCount - 1, IntOffset(scope.childCount - 1, 0))
                placedChildren += 2
            } else {
                for (index in 0 until scope.childCount) {
                    scope.placeChild(index, IntOffset(index, 0))
                    placedChildren += 1
                }
            }
        }

        /**
         * Requests a new parent pass without invalidating cached leaf measurement.
         */
        fun invalidateGeometry() {
            invalidate(DirtyMask.of(DirtyPhase.Measure))
        }
    }

    /**
     * Static parent declaration owning one fresh fixture node.
     */
    private class ParentElement(
        val node: ParentNode,
        children: List<Element>,
    ) : Element(ElementIdentity.Positional, TYPE, children = children) {
        /**
         * Stable parent token for the fixed geometry fixture.
         */
        companion object {
            val TYPE: ElementType<ParentElement, ParentNode> =
                ElementType(
                    elementClass = ParentElement::class,
                    nodeClass = ParentNode::class,
                    validateLocal = { _ -> },
                    createNode = { it.node },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Cached leaf measurement with no other presentation capability.
     */
    private class LeafNode :
        Node(),
        MeasureNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(1, 1))
    }

    /**
     * Static leaf declaration whose node belongs to exactly one retained child.
     */
    private class LeafElement : Element(ElementIdentity.Positional, TYPE) {
        /**
         * Stable leaf token for the fixed geometry fixture.
         */
        companion object {
            val TYPE: ElementType<LeafElement, LeafNode> =
                ElementType(
                    elementClass = LeafElement::class,
                    nodeClass = LeafNode::class,
                    validateLocal = { _ -> },
                    createNode = { LeafNode() },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
