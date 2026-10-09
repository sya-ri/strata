@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteClientSession
import dev.s7a.strata.runtime.remote.RemoteDeclaration
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteNode
import dev.s7a.strata.runtime.remote.RemotePatch
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Actual immutable patch application and complete client update/preparation controls, without native rendering.
 * All 72 generated cases preserve full validation and immutable prior trees on both loaded runtime archives.
 */
public open class RemotePatchIndexBenchmark {
    /**
     * Applies a frozen patch to a frozen previous tree, including complete untrusted validation.
     */
    @Benchmark
    public fun apply(scene: Scene): RemoteTree = scene.applyPatch()

    /**
     * Alternates actual atomic client updates, including registry/state preparation and revision acknowledgement.
     */
    @Benchmark
    public fun clientUpdate(scene: Scene): Long = scene.updateClient()

    /**
     * Wide trees and a backbone reaching the negotiated depth limit, with remaining records rooted alongside it.
     */
    public enum class Shape {
        Wide,
        Deep,
    }

    /**
     * Stable, property, ordering and identity/topology changes share the same current node and modifier counts.
     */
    public enum class Change {
        Stable,
        Single,
        All,
        Reorder,
        AddRemove,
        RootReplacement,
    }

    /**
     * Private immutable input pairs and one current real client; only cumulative counters survive each operation.
     * Custom limits tightly admit the configured node/declaration/depth corpus rather than assuming defaults.
     */
    @State(Scope.Thread)
    public open class Scene(
        private val reconstructionMillis: Long = 1000,
    ) : AutoCloseable {
        /**
         * Minimal, medium, and default maximum node counts.
         */
        @JvmField
        @Param("1", "128", "8192")
        public var nodes: Int = 1

        /**
         * Admitted topology, independent of the selected change.
         */
        @JvmField
        @Param
        public var shape: Shape = Shape.Wide

        /**
         * Each mutation's actual immutable patch is prepared before timing.
         */
        @JvmField
        @Param
        public var change: Change = Change.Stable

        private lateinit var limits: RemoteLimits
        private lateinit var before: RemoteTree
        private lateinit var after: RemoteTree
        private lateinit var forward: RemotePatch
        private lateinit var backward: RemotePatch
        private lateinit var client: RemoteClientSession
        private var revision = 1L
        private var candidate = false
        private var applied = 0L
        private var prepared = 0L
        private var closed = 0
        private val type = ProjectionType(ResourceId("benchmark", "patch_node"))
        private val modifierType = ProjectionType(ResourceId("benchmark", "patch_modifier"))

        /**
         * Constructs immutable topology, both patches, and a real prepared client outside measured operations.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val depth = if (shape == Shape.Deep) minOf(nodes, 64) else minOf(nodes, 2)
            limits = RemoteLimits(treeNodes = nodes, collectionEntries = nodes * 2, valueDepth = depth, reconstructionMillis = reconstructionMillis)
            val records = (1..nodes).map { identity -> node(identity.toLong(), children(identity), 0) }
            before = RemoteTree(1, records, limits)
            val expected = changedRecords(records)
            after = RemoteTree(expected.first, expected.second, limits)
            forward = RemotePatch.between(before, after)
            backward = RemotePatch.between(after, before)
            val registry = RemoteRegistry()
            registry.element(type, { requireNotNull(it as? ProjectionValue.Integer).value }, { _, _ -> prepared++ }) { _, context ->
                evaluateComponentTree { Spacer(context.modifier, context.key) }
            }
            registry.modifier(modifierType, { it }) { _, _ -> Modifier.Empty }
            client = RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, before), registry, limits, send = { message ->
                when (message) {
                    is RemoteMessage.Applied -> applied = message.revision
                    is RemoteMessage.Close -> closed++
                    else -> Unit
                }
            })
            check(prepared == nodes.toLong() && applied == 1L)
        }

        /**
         * Returns only one new validated tree; no measured-result history is retained.
         */
        public fun applyPatch(): RemoteTree = forward.apply(before, limits)

        /**
         * Applies the next prebuilt patch through the actual client's existing revision and preparation boundaries.
         */
        public fun updateClient(): Long {
            val patch = if (candidate) backward else forward
            val previous = revision
            revision++
            client.receive(RemoteMessage.Update(1, previous, revision, patch))
            candidate = candidate.not()
            check(applied == revision)
            return applied
        }

        /**
         * Validates results against an independent list-index/BFS oracle and checks every old snapshot outside timing.
         * Malformed and newly tightened stable inputs are frozen deterministic rejection controls, not timed thresholds.
         */
        public fun verifyWork() {
            val original = before.nodes.toMap()
            val expected = reference(before, forward, limits)
            val actual = applyPatch()
            check(actual.root == expected.first && actual.nodes == expected.second)
            check(actual.nodes.keys.toList() == expected.second.keys.toList())
            check(actual == after && actual.hashCode() == after.hashCode())
            updateClient()
            updateClient()
            check(prepared == 3L * nodes && applied == 3L && client.status == RemoteSessionStatus.Open)
            val invalid = listOf(RemotePatch(Long.MAX_VALUE, emptyList(), emptyList()), RemotePatch(before.root, emptyList(), listOf(Long.MAX_VALUE)), RemotePatch(before.root, listOf(node(before.root, listOf(before.root), 0)), emptyList()))
            invalid.forEach { patch ->
                check(runCatching { reference(before, patch, limits) }.exceptionOrNull() is IllegalArgumentException)
                check(runCatching { patch.apply(before, limits) }.exceptionOrNull() is IllegalArgumentException)
            }
            val stable = RemotePatch(before.root, emptyList(), emptyList())
            val declarations = limits.copy(collectionEntries = nodes * 2 - 1)
            check(runCatching { stable.apply(before, declarations) }.exceptionOrNull() is IllegalArgumentException)
            if (1 < nodes) check(runCatching { stable.apply(before, limits.copy(treeNodes = nodes - 1)) }.exceptionOrNull() is IllegalArgumentException)
            if (1 < limits.valueDepth) check(runCatching { stable.apply(before, limits.copy(valueDepth = limits.valueDepth - 1)) }.exceptionOrNull() is IllegalArgumentException)
            check(before.nodes == original && before.nodes.keys.toList() == original.keys.toList())
            check(backward.apply(actual, limits) == before)
            println("Patch index $nodes $shape $change: ${actual.nodes.size} current nodes, ${forward.changed.size} changed, ${forward.removed.size} removed; complete client preparation $prepared records, revision $applied")
            close()
            check(closed == 1 && client.status is RemoteSessionStatus.Closed)
        }

        /**
         * Supplies explicit component identities to the untimed observer, including both frozen input variants.
         * A role is determined by this typed membership rather than a numeric identity range.
         */
        public fun componentIdentities(): LongArray = (before.nodes.keys + after.nodes.keys).toLongArray()

        /**
         * Releases the current client without retaining a revision or outgoing-message history.
         */
        @TearDown(Level.Trial)
        override fun close() {
            if (::client.isInitialized) client.close()
        }

        private fun children(identity: Int): List<Long> {
            if (shape == Shape.Wide) return if (identity == 1) (2..nodes).map(Int::toLong) else emptyList()
            val backbone = minOf(nodes, 64)
            return when {
                identity == 1 -> (if (1 < backbone) listOf(2L) else emptyList()) + ((backbone + 1)..nodes).map(Int::toLong)
                identity < backbone -> listOf(identity + 1L)
                else -> emptyList()
            }
        }

        private fun node(
            identity: Long,
            children: List<Long>,
            value: Long,
        ): RemoteNode = RemoteNode(RemoteDeclaration(identity, type, ProjectionValue.Integer(value)), listOf(RemoteDeclaration(100000 + identity, modifierType, ProjectionValue.Absent)), children)

        private fun changedRecords(records: List<RemoteNode>): Pair<Long, List<RemoteNode>> =
            when (change) {
                Change.Stable -> 1L to records
                Change.Single -> 1L to records.map { if (it.declaration.identity == nodes.toLong()) node(it.declaration.identity, it.children, 1) else it }
                Change.All -> 1L to records.map { node(it.declaration.identity, it.children, 1) }
                Change.Reorder -> 1L to records.map { if (it.declaration.identity == 1L) node(1, it.children.reversed(), 0) else it }
                Change.AddRemove -> {
                    val removed = nodes.toLong()
                    val added = removed + 1
                    val current = records.filter { it.declaration.identity != removed }.map { node(it.declaration.identity, it.children.map { child -> if (child == removed) added else child }, 0) } + node(added, emptyList(), 0)
                    (if (nodes == 1) added else 1L) to current
                }
                Change.RootReplacement -> {
                    val root = nodes + 1L
                    root to (records.drop(1) + node(root, records.first().children, 0))
                }
            }

        private fun reference(
            previous: RemoteTree,
            patch: RemotePatch,
            limits: RemoteLimits,
        ): Pair<Long, Map<Long, RemoteNode>> {
            require(patch.changed.size <= limits.treeNodes && patch.removed.size <= limits.treeNodes)
            require(patch.removed.all { it in previous.nodes })
            val records = previous.nodes.values.filter { (it.declaration.identity in patch.removed).not() }.toMutableList()
            val positions = records.mapIndexed { index, record -> record.declaration.identity to index }.toMap().toMutableMap()
            for (change in patch.changed) {
                val position = positions[change.declaration.identity]
                if (position == null) {
                    positions[change.declaration.identity] = records.size
                    records.add(change)
                } else {
                    records[position] = change
                }
            }
            require(records.size <= limits.treeNodes)
            val index = linkedMapOf<Long, RemoteNode>()
            for (record in records) require(index.put(record.declaration.identity, record) == null)
            val identities = mutableSetOf<Long>()
            val pending = ArrayDeque<Pair<Long, Int>>()
            pending.addLast(patch.root to 0)
            var components = 0
            while (pending.isNotEmpty()) {
                val (identity, depth) = pending.removeFirst()
                require(depth < limits.valueDepth && identities.add(identity))
                val record = requireNotNull(index[identity])
                require(record.declaration.identity == identity)
                require(record.children.size <= limits.treeNodes && record.modifiers.size <= limits.collectionEntries)
                for (modifier in record.modifiers) require(identities.add(modifier.identity))
                require(identities.size <= limits.collectionEntries)
                components++
                record.children.forEach { pending.addLast(it to depth + 1) }
                require(pending.size <= records.size)
            }
            require(components == records.size)
            return patch.root to index
        }
    }

    /**
     * Generated method/parameter discovery and independent checks use the generic shared remote launcher.
     */
    public companion object {
        /**
         * Requires all 72 compiled rows and deterministic graph, ordering, tightened-limit and client controls.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(RemotePatchIndexBenchmark::class.java), setOf("avgt")).size == 72)
            for (nodes in listOf(1, 128, 8192)) {
                for (shape in Shape.entries) {
                    for (change in Change.entries) {
                        Scene().also { scene ->
                            scene.nodes = nodes
                            scene.shape = shape
                            scene.change = change
                            try {
                                scene.setup()
                                scene.verifyWork()
                            } finally {
                                scene.close()
                            }
                        }
                    }
                }
            }
        }
    }
}
