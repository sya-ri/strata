@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteClientSession
import dev.s7a.strata.runtime.remote.RemoteDeclaration
import dev.s7a.strata.runtime.remote.RemoteElementContext
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemoteNode
import dev.s7a.strata.runtime.remote.RemotePatch
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.runtime.remote.RemoteStateKey
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete public client updates on fixed input pairs, including full topology/registry/phase/state admission and Applied.
 * All 54 cases use APIs present on both runtime archives; no prepared-tree access or candidate hook is required.
 */
public open class RemotePreparationBenchmark {
    /**
     * Consumes the actual committed revision, or the required terminal invalid-update outcome.
     */
    @Benchmark
    public fun process(scene: Scene): Long = scene.perform()

    /**
     * Maximum current decoded component counts, including one root container.
     */
    public enum class Size(public val nodes: Int) { Small100(100), Limit8192(8192) }

    /**
     * Ordered modifiers on the root; leaf records remain modifier-free under the same aggregate bound.
     */
    public enum class Chain(public val entries: Int) { Empty(0), Short2(2), Long32(32) }

    /**
     * Valid-property/topology changes, explicit callback/state controls, and complete malformed update rejection.
     */
    public enum class Workload { Stable, Single, Few, All, Reorder, AddRemove, Stateful, CallerNoop, Invalid }

    /**
     * One current client and frozen forward/backward patches, retaining only current revision/counters and state handles.
     * Invalid includes initial client preparation, invalid update and terminal release on every operation.
     */
    @State(Scope.Thread)
    public open class Scene(private val reconstructionMillis: Long = 1000) : AutoCloseable {
        /**
         * Current maximum component count.
         */
        @JvmField
        @Param
        public var size: Size = Size.Small100

        /**
         * Complete current root modifier chain.
         */
        @JvmField
        @Param
        public var chain: Chain = Chain.Empty

        /**
         * Exact declared mutation and callback conditions.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.Stable

        private lateinit var limits: RemoteLimits
        private lateinit var before: RemoteTree
        private lateinit var after: RemoteTree
        private lateinit var forward: RemotePatch
        private lateinit var backward: RemotePatch
        private lateinit var invalid: RemotePatch
        private lateinit var initial: RemoteMessage.Snapshot
        private var client: RemoteClientSession? = null
        private var revision = 1L
        private var applied = 0L
        private var selected = false
        private var components = 0L
        private var modifiers = 0L
        private var preparations = 0L
        private var references = 0L
        private var closes = 0L
        private var released = 0L
        private val owned = linkedMapOf<Long, Owned>()
        private val key = RemoteStateKey(Owned::class)

        /**
         * Builds immutable input variants and initial decoded ownership outside steady operation timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            limits = RemoteLimits(treeNodes = size.nodes, collectionEntries = size.nodes + chain.entries, valueEntries = (size.nodes + chain.entries) * 8, reconstructionMillis = reconstructionMillis)
            before = tree(false)
            after = tree(true)
            forward = RemotePatch.between(before, after)
            backward = RemotePatch.between(after, before)
            initial = RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, before)
            invalid = RemotePatch(1, listOf(RemoteNode(before.nodes.getValue(1).declaration.copy(value = ProjectionValue.Integer(-1)), before.nodes.getValue(1).modifiers, before.nodes.getValue(1).children)), emptyList())
            if (workload != Workload.Invalid) client = acquire()
        }

        /**
         * Runs one complete next patch; invalid work includes its independent client lifecycle explicitly.
         */
        public fun perform(): Long {
            if (workload == Workload.Invalid) {
                val current = acquire()
                try {
                    val failure = runCatching { current.receive(RemoteMessage.Update(1, 1, 2, invalid)) }.exceptionOrNull()
                    check(failure is IllegalArgumentException && current.status is RemoteSessionStatus.Closed && current.nodeCount == 0)
                    return current.nodeCount.toLong()
                } finally { current.close() }
            }
            val previous = revision
            revision++
            checkNotNull(client).receive(RemoteMessage.Update(1, previous, revision, if (selected) backward else forward))
            selected = selected.not()
            return applied
        }

        /**
         * Stable controls for paired CPU receipts; malformed work's initial preparation is part of its operation.
         */
        public fun controls(): Map<String, Long> = mapOf(
            "maximum_nodes" to size.nodes.toLong(), "root_modifiers" to chain.entries.toLong(), "sessions" to 1L,
            "message_bytes" to limits.messageBytes.toLong(), "pending_bytes" to limits.pendingBytes.toLong(),
            "value_entries" to limits.valueEntries.toLong(), "collection_entries" to limits.collectionEntries.toLong(),
            "reconstruction_millis" to reconstructionMillis,
            "initial_client_per_operation" to if (workload == Workload.Invalid) 1L else 0L,
        )

        /**
         * Independent wire/patch/property/topology, actual decoder/callback/state and terminal oracles outside intervals.
         */
        public fun verifyWork() {
            val codec = RemoteMessageCodec(limits)
            check(codec.decode(codec.encode(initial)) == initial)
            check(forward.apply(before, limits) == after && backward.apply(after, limits) == before)
            check(before.nodes.size == size.nodes && after.nodes.size == size.nodes)
            val decoded = components
            val modifierCount = modifiers
            val prepared = preparations
            val referenced = references
            val identities = owned.toMap()
            repeat(2) {
                val result = perform()
                if (workload != Workload.Invalid) {
                    check(result == revision && checkNotNull(client).nodeCount == size.nodes)
                    verifyBuilt()
                }
            }
            if (workload == Workload.Invalid) {
                check(applied == 1L && closes == 2L && owned.isEmpty())
            } else {
                val changed = when (workload) { Workload.Stable, Workload.Reorder -> 0; Workload.Few -> 3; Workload.All -> size.nodes; Workload.AddRemove -> 1; else -> 1 }
                check(components == decoded + 2L * changed && modifiers == modifierCount)
                if (workload == Workload.Stateful || workload == Workload.CallerNoop) check(preparations == prepared + 2L * size.nodes)
                if (workload == Workload.Stateful) {
                    check(references == referenced + 2L * chain.entries)
                    check(owned.keys == before.nodes.keys)
                    identities.forEach { (identity, value) -> check(owned[identity] === value && value.value == 0L) }
                }
            }
            close()
            check(owned.isEmpty() && client == null)
            check(closes == if (workload == Workload.Invalid) 2L else 1L)
            if (workload == Workload.Stateful) check(released == size.nodes.toLong())
        }

        /**
         * Releases the current owner and all fixture-held state handles without collecting an operation history.
         */
        @TearDown(Level.Trial)
        override fun close() {
            val current = client
            client = null
            current?.close()
        }

        private fun verifyBuilt() {
            val content = checkNotNull(client).definition(UiText.Literal("prepared")).transfer().content
            val built = evaluateComponentTree(content)
            check(built.children.size == size.nodes - 1)
            (listOf(built) + built.children).forEachIndexed { index, element ->
                val position = index + 1
                val identity = if (selected && workload == Workload.AddRemove && position == size.nodes) size.nodes + 1L else position.toLong()
                check(element.identity == ElementIdentity.Keyed(ElementKey(identity)))
                val count = when (workload) { Workload.Few -> 3; Workload.All -> size.nodes; else -> 1 }
                val changed = selected && workload != Workload.Stable && workload != Workload.Reorder && workload != Workload.AddRemove && position <= count
                val properties = element.modifier.elements().map { modifier ->
                    val projection = checkNotNull(modifier.projection)
                    check(projection.type == BuiltinProjection.Background.type)
                    (projection.encode(UnusedScope) as ProjectionValue.Sequence).values.single() as ProjectionValue.Integer
                }.map { it.value }
                val ordered = if (index == 0) (0 until chain.entries).map(Int::toLong).let { if (selected && workload == Workload.Reorder) it.reversed() else it } else emptyList()
                check(properties == ordered + if (changed) 1L else 0L)
            }
        }

        private fun acquire(): RemoteClientSession {
            val registry = RemoteRegistry()
            val decode: (ProjectionValue) -> Long = { value ->
                val scalar = (value as ProjectionValue.Integer).value
                require(0 <= scalar)
                components++
                scalar
            }
            val create: (Long, RemoteElementContext) -> Element = { value, context -> evaluateComponentTree { Column(context.modifier.background(ArgbColor(value.toInt())), context.key) { context.children.forEach(::element) } } }
            when (workload) {
                Workload.Stateful -> registry.element(COMPONENT, decode, { value, context ->
                    preparations++
                    val identity = context.identity
                    context.states.prepare(identity, key, { Owned(value).also { owned[identity] = it } }, { it.value = value }, { owned.remove(identity); released++ })
                }, create = create)
                Workload.CallerNoop -> registry.element(COMPONENT, decode, { _, _ -> preparations++ }, create = create)
                else -> registry.element(COMPONENT, decode, create = create)
            }
            val modifierDecode: (ProjectionValue) -> Long = { value -> modifiers++; (value as ProjectionValue.Integer).value }
            if (workload == Workload.Stateful) registry.statefulModifier(MODIFIER, modifierDecode, { _, _ -> references++ }) { value, _ -> Modifier.Empty.background(ArgbColor(value.toInt())) }
            else registry.modifier(MODIFIER, modifierDecode) { value, _ -> Modifier.Empty.background(ArgbColor(value.toInt())) }
            return RemoteClientSession(initial, registry, limits, send = { message -> when (message) { is RemoteMessage.Applied -> applied = message.revision; is RemoteMessage.Close -> closes++; else -> Unit } })
        }

        private fun tree(alternate: Boolean): RemoteTree {
            val changing = alternate && workload != Workload.Stable && workload != Workload.Reorder && workload != Workload.Invalid
            val count = when (workload) { Workload.Few -> 3; Workload.All -> size.nodes; else -> 1 }
            val records = (1..size.nodes).map { identity ->
                val replacement = if (alternate && workload == Workload.AddRemove && identity == size.nodes) size.nodes + 1L else identity.toLong()
                val value = if (changing && workload != Workload.AddRemove && identity <= count) 1L else 0L
                val children = if (identity == 1) (2L..size.nodes.toLong()).map { if (alternate && workload == Workload.AddRemove && it == size.nodes.toLong()) size.nodes + 1L else it } else emptyList()
                val ordered = if (identity == 1) List(chain.entries) { index -> RemoteDeclaration(10000L + index, MODIFIER, ProjectionValue.Integer(index.toLong())) }.let { if (alternate && workload == Workload.Reorder) it.reversed() else it } else emptyList()
                RemoteNode(RemoteDeclaration(replacement, COMPONENT, ProjectionValue.Integer(value)), ordered, children)
            }
            return RemoteTree(1, records, limits)
        }
    }

    /**
     * Fixture-owned derived presentation scalar; no authoritative server or native state is cached.
     */
    private class Owned(var value: Long)

    private object UnusedScope : ProjectionScope {
        override fun action(action: ProjectionAction<*>, key: ProjectionValue): Long = error("Unexpected action")
        override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue = error("Unexpected binding")
        override fun image(image: DrawImage): ProjectionValue = error("Unexpected image")
        override fun text(text: UiText): ProjectionValue = error("Unexpected text")
        override fun requireType(type: ProjectionType): Unit = error("Unexpected capability")
    }

    /**
     * Same common fixture admission used by shared JMH selection, supplemental CPU and untimed observation.
     */
    public companion object {
        private val COMPONENT = ProjectionType(ResourceId("benchmark", "prepared_client_component"))
        private val MODIFIER = ProjectionType(ResourceId("benchmark", "prepared_client_modifier"))

        /**
         * Requires all 54 actual generated combinations and archive-independent public behavior before collection.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(RemotePreparationBenchmark::class.java), setOf("avgt")).size == 54)
            for (size in Size.entries) for (chain in Chain.entries) for (workload in Workload.entries) Scene().use { scene ->
                scene.size = size
                scene.chain = chain
                scene.workload = workload
                scene.setup()
                scene.verifyWork()
            }
        }
    }
}
