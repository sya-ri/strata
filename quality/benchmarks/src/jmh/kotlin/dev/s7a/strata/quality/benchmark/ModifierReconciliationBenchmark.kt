package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Isolates retained reconciliation of alternating fresh compatible component declarations.
 * Frozen descriptions exclude declaration construction and presentation, while retaining full validation,
 * component callbacks, child reconciliation and lifecycle work inside each measured update.
 * Equivalent chains share their exact modifier descriptions, isolating bookkeeping from typed callbacks.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class ModifierReconciliationBenchmark {
    /**
     * Reconciles the next frozen tree on the JMH worker owning its retained nodes.
     */
    @Benchmark
    public fun update(state: Scene): UiTree = state.update()

    /**
     * Fixed entry and chain dimensions, plus representative changed-chain and structural controls.
     * Empty equivalent chains are already the same singleton and use the Same cases.
     */
    public enum class Workload(
        public val entryCount: Int,
        public val modifierCount: Int,
        public val mutation: Mutation = Mutation.Same,
    ) {
        Same1x0(1, 0),
        Same1x1(1, 1),
        Same1x8(1, 8),
        Same1x32(1, 32),
        Same1000x0(1_000, 0),
        Same1000x1(1_000, 1),
        Same1000x8(1_000, 8),
        Same1000x32(1_000, 32),
        Equivalent1x1(1, 1, Mutation.Equivalent),
        Equivalent1x8(1, 8, Mutation.Equivalent),
        Equivalent1x32(1, 32, Mutation.Equivalent),
        Equivalent1000x1(1_000, 1, Mutation.Equivalent),
        Equivalent1000x8(1_000, 8, Mutation.Equivalent),
        Equivalent1000x32(1_000, 32, Mutation.Equivalent),
        FreshDescriptions1000x8(1_000, 8, Mutation.FreshDescriptions),
        OneChanged1000x8(1_000, 8, Mutation.OneChanged),
        ModifierType1000x8(1_000, 8, Mutation.ModifierType),
        ComponentType1000x8(1_000, 8, Mutation.ComponentType),
        AddRemove1000x8(1_000, 8, Mutation.AddRemove),
        Reordered1000x8(1_000, 8, Mutation.Reordered),
        Keyed1000x8(1_000, 8, Mutation.Keyed),
    }

    /**
     * Typed fixture differences; all other description inputs remain identical.
     */
    public enum class Mutation {
        Same,
        Equivalent,
        FreshDescriptions,
        OneChanged,
        ModifierType,
        ComponentType,
        AddRemove,
        Reordered,
        Keyed,
    }

    /**
     * One owner-confined tree with two immutable declarations prepared before sampling.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Complete frozen workload injected by JMH before setup.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.Same1x0

        private val probe = Probe()
        private lateinit var tree: UiTree
        private lateinit var original: ComponentDescription
        private lateinit var candidate: ComponentDescription
        private var alternate = false

        /**
         * Freezes two fresh declaration trees and verifies callback/lifecycle work outside collection.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            val descriptions = List(workload.modifierCount) { index -> ModifierDescription(probe, index, if (index % 2 == 0) Kind.First else Kind.Second) }
            val originalChain = chain(descriptions)
            val candidateChain =
                when (workload.mutation) {
                    Mutation.Equivalent -> chain(descriptions)
                    Mutation.FreshDescriptions -> chain(descriptions.map { ModifierDescription(probe, it.value, it.kind) })
                    Mutation.OneChanged -> chain(descriptions.dropLast(1) + ModifierDescription(probe, workload.modifierCount, descriptions.last().kind))
                    Mutation.ModifierType -> chain(descriptions.dropLast(1) + ModifierDescription(probe, descriptions.last().value, Kind.First))
                    Mutation.AddRemove -> originalChain.then(ModifierDescription(probe, workload.modifierCount, Kind.First))
                    Mutation.Reordered -> chain(descriptions.reversed())
                    else -> originalChain
                }
            val children = List(workload.entryCount) { index -> ComponentDescription(probe, index, Kind.First, originalChain) }
            val nextKind = if (workload.mutation == Mutation.ComponentType) Kind.Second else Kind.First
            val nextChildren = List(workload.entryCount) { index -> ComponentDescription(probe, index, nextKind, candidateChain) }
            original = ComponentDescription(probe, -1, Kind.First, Modifier.Empty, children)
            candidate = ComponentDescription(probe, -1, Kind.First, Modifier.Empty, if (workload.mutation == Mutation.Keyed) nextChildren.reversed() else nextChildren)
            tree = UiTree()
            tree.update(original)
            verifyWork()
        }

        /**
         * Alternates distinct compatible descriptions, including required validation and child walks.
         */
        public fun update(): UiTree {
            alternate = alternate.not()
            tree.update(if (alternate) candidate else original)
            return tree
        }

        /**
         * Requires exact callback work and current-state bounds for every structural control.
         */
        public fun verifyWork() {
            repeat(10) {
                val componentValidations = probe.componentValidations
                val modifierValidations = probe.modifierValidations
                val componentUpdates = probe.componentUpdates
                val modifierUpdates = probe.modifierUpdates
                update()
                val modifiers = workload.modifierCount + if (alternate && workload.mutation == Mutation.AddRemove) 1 else 0
                check(probe.componentValidations - componentValidations == workload.entryCount + 1L)
                check(probe.modifierValidations - modifierValidations == workload.entryCount.toLong() * modifiers)
                check(probe.componentUpdates - componentUpdates == if (workload.mutation == Mutation.ComponentType) 1L else workload.entryCount + 1L)
                val expectedModifierUpdates =
                    when (workload.mutation) {
                        Mutation.FreshDescriptions -> workload.entryCount.toLong() * workload.modifierCount
                        Mutation.OneChanged -> workload.entryCount.toLong()
                        else -> 0L
                    }
                check(probe.modifierUpdates - modifierUpdates == expectedModifierUpdates)
                check(probe.liveComponents == workload.entryCount + 1)
                check(probe.liveModifiers == workload.entryCount * modifiers)
            }
        }

        /**
         * Closes the reachable tree and requires all current node ownership to be released.
         */
        @TearDown(Level.Trial)
        public fun close() {
            if (::tree.isInitialized) tree.close()
            check(probe.liveComponents == 0 && probe.liveModifiers == 0)
            check(probe.attaches == probe.detaches && probe.created == probe.disposed)
        }
    }

    /**
     * Untimed acceptance discovered by the generic compiled-fixture collector.
     */
    public companion object {
        private fun chain(descriptions: List<ModifierDescription>): Modifier = descriptions.fold(Modifier.Empty) { result, description -> result.then(description) }

        /**
         * Verifies the exact corpus and every workload's independent owner lifecycle before collection.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(ModifierReconciliationBenchmark::class.java), setOf("avgt")).size == Workload.entries.size)
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
     * Stable token choices used only to exercise positional replacement and ordering.
     */
    private enum class Kind {
        First,
        Second,
    }

    /**
     * Current-owner counters retain no node, callback or description history.
     */
    private class Probe {
        var componentValidations = 0L
        var modifierValidations = 0L
        var componentUpdates = 0L
        var modifierUpdates = 0L
        var liveComponents = 0
        var liveModifiers = 0
        var created = 0L
        var disposed = 0L
        var attaches = 0L
        var detaches = 0L
    }

    /**
     * Fresh compatible declaration keyed under its current parent.
     */
    private class ComponentDescription(
        val probe: Probe,
        id: Int,
        kind: Kind,
        modifier: Modifier,
        children: List<Element> = emptyList(),
    ) : Element(ElementIdentity.Keyed(ElementKey(id)), if (kind == Kind.First) FIRST else SECOND, children = children, modifier = modifier) {
        companion object {
            val FIRST = token()
            val SECOND = token()

            private fun token(): ElementType<ComponentDescription, ComponentNode> =
                ElementType(
                    elementClass = ComponentDescription::class,
                    nodeClass = ComponentNode::class,
                    validateLocal = { it.probe.componentValidations += 1 },
                    createNode = { ComponentNode(it.probe) },
                    updateNode = { _, current, _ ->
                        current.probe.componentUpdates += 1
                        DirtyMask.None
                    },
                )
        }
    }

    /**
     * Lifecycle-only component keeps reconciliation separate from rendering and semantics traversal.
     */
    private class ComponentNode(
        private val probe: Probe,
    ) : Node(),
        LifecycleNode {
        init {
            probe.created += 1
            probe.liveComponents += 1
        }

        override fun attach() {
            probe.attaches += 1
        }

        override fun detach() {
            probe.detaches += 1
        }

        override fun dispose() {
            probe.liveComponents -= 1
            probe.disposed += 1
        }
    }

    /**
     * Immutable active description carrying one typed token and one scalar update property.
     */
    private class ModifierDescription(
        val probe: Probe,
        val value: Int,
        val kind: Kind,
    ) : ModifierElement {
        override val type: ModifierNodeType<ModifierDescription, ActiveNode>
            get() = if (kind == Kind.First) FIRST else SECOND

        companion object {
            val FIRST = token()
            val SECOND = token()

            private fun token(): ModifierNodeType<ModifierDescription, ActiveNode> =
                ModifierNodeType(
                    elementClass = ModifierDescription::class,
                    nodeClass = ActiveNode::class,
                    validateLocal = { it.probe.modifierValidations += 1 },
                    createNode = { ActiveNode(it.probe) },
                    updateNode = { previous, current, _ ->
                        current.probe.modifierUpdates += 1
                        if (previous.value == current.value) DirtyMask.None else DirtyMask.of(DirtyPhase.Semantics)
                    },
                )
        }
    }

    /**
     * Fresh active node for exactly one modifier position and owner.
     */
    private class ActiveNode(
        private val probe: Probe,
    ) : ModifierNode(),
        LifecycleNode {
        init {
            probe.created += 1
            probe.liveModifiers += 1
        }

        override fun attach() {
            probe.attaches += 1
        }

        override fun detach() {
            probe.detaches += 1
        }

        override fun dispose() {
            probe.liveModifiers -= 1
            probe.disposed += 1
        }
    }
}
