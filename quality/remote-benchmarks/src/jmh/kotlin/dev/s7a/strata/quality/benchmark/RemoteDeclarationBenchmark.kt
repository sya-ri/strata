@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.node.DeclarationProjectionNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSessionStatus
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete retained server cutoffs through APIs present on both archived runtimes.
 * Preparation, fixed metadata comparison and opaque projection remain inside each declared operation.
 * This fixture neither calls the new fixed-value bridge nor reads a candidate-only revision property.
 */
public open class RemoteDeclarationBenchmark {
    /**
     * Executes one declared server operation, consuming its actual completed node count.
     */
    @Benchmark
    public fun process(scene: Scene): Int = scene.perform()

    /**
     * Aggregate current node budgets, including real Observe and Column declarations where used.
     */
    public enum class Size(public val nodes: Int) {
        Small100(100),
        Limit8192(8192),
    }

    /**
     * Fixed reuse, unchanged/changed opaque behavior and independently owned lifecycle controls.
     */
    public enum class Workload {
        FixedIdle,
        FixedUpdate,
        OpaqueIdle,
        DynamicLists,
        SharedIdle,
        SharedUpdate,
        EndpointChurn,
        EditableValues,
        ControlsAck,
        PreparingIdle,
        PreparingChange,
        Lifecycle,
    }

    /**
     * Current owners and two fixed input variants on one JMH worker, with no outgoing-message history.
     * Shared cases keep four or sixteen owners within the declared aggregate node and HUD budgets.
     */
    @State(Scope.Thread)
    public open class Scene(private val reconstructionMillis: Long = 1000) : AutoCloseable {
        /**
         * Maximum aggregate current nodes, including dynamic-list alternating admission.
         */
        @JvmField
        @Param
        public var size: Size = Size.Small100

        /**
         * Exact operation and owned-input conditions.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.FixedIdle

        private var source = RemoteStateSource(false)
        private var selected = false
        private var peers = emptyList<Peer>()
        private var retired = emptyList<Peer>()
        private var verifying = false
        private var preparations = 0L
        private var acceptedActions = 0L
        private val sessionCount: Int get() = if (workload == Workload.SharedIdle || workload == Workload.SharedUpdate) if (size == Size.Small100) 4 else 16 else 1
        private val perOwnerNodes: Int get() = size.nodes / sessionCount
        private val limits: RemoteLimits get() = RemoteLimits(reconstructionMillis = reconstructionMillis)

        /**
         * Creates initial admitted owners outside steady-state operation timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            source = RemoteStateSource(false)
            selected = false
            preparations = 0
            acceptedActions = 0
            check(size.nodes <= limits.treeNodes && sessionCount <= limits.hudSessions)
            if (workload != Workload.Lifecycle) peers = acquire()
        }

        /**
         * Executes one cutoff; lifecycle includes attach/update/close, and control/edit work includes its real acknowledgement.
         */
        public fun perform(): Int {
            if (workload == Workload.Lifecycle) {
                val current = acquire()
                return try {
                    selected = selected.not()
                    source.publish(selected)
                    current.forEach { it.server.tick() }
                    if (verifying) current.forEach(Peer::verify)
                    current.sumOf { it.server.nodeCount }
                } finally {
                    current.forEach(Peer::close)
                    retired = current
                }
            }
            when (workload) {
                Workload.FixedUpdate, Workload.DynamicLists, Workload.SharedUpdate -> {
                    selected = selected.not()
                    source.publish(selected)
                }
                Workload.EndpointChurn, Workload.EditableValues, Workload.ControlsAck, Workload.PreparingChange -> selected = selected.not()
                else -> Unit
            }
            peers.forEach { peer ->
                if (workload == Workload.EditableValues) peer.edit(if (selected) "first" else "second")
                if (workload == Workload.ControlsAck) peer.switch(if (selected) UiPresentation.Hud else UiPresentation.Screen)
                peer.server.tick()
            }
            return peers.sumOf { it.server.nodeCount }
        }

        /**
         * Complete operation/admission denominators used by both independent CPU sides.
         */
        public fun controls(): Map<String, Long> = mapOf(
            "maximum_nodes" to size.nodes.toLong(), "sessions" to sessionCount.toLong(), "nodes_per_owner" to perOwnerNodes.toLong(),
            "ticks_per_operation" to if (workload == Workload.Lifecycle || workload == Workload.EditableValues) 2L else sessionCount.toLong(),
            "message_bytes" to limits.messageBytes.toLong(), "pending_bytes" to limits.pendingBytes.toLong(),
            "reconstruction_millis" to reconstructionMillis, "source_subscriptions_at_setup" to source.subscriptions.toLong(),
            "text_limit_utf16_units" to 64L,
        )

        /**
         * Checks both input variants, actual wire/revision/control/edit work and terminal ownership outside every sample interval.
         */
        public fun verifyWork() {
            verifying = true
            peers.forEach(Peer::beginVerification)
            val before = peers.map { it.updates }
            val prepared = preparations
            repeat(2) {
                val nodes = perform()
                check(nodes == if (workload == Workload.DynamicLists && selected) size.nodes - 1 else size.nodes)
                peers.forEach(Peer::verify)
            }
            when (workload) {
                Workload.FixedUpdate, Workload.DynamicLists, Workload.SharedUpdate, Workload.EndpointChurn, Workload.EditableValues, Workload.PreparingChange -> peers.forEachIndexed { index, peer -> check(peer.updates == before[index] + 2) }
                Workload.Lifecycle -> check(retired.all { it.server.status is RemoteSessionStatus.Closed && it.server.nodeCount == 0 })
                else -> peers.forEachIndexed { index, peer -> check(peer.updates == before[index]) }
            }
            if (workload == Workload.PreparingIdle || workload == Workload.PreparingChange) check(preparations == prepared + 2)
            if (workload == Workload.EndpointChurn) peers.forEach(Peer::verifyObsoleteAction)
            val current = peers
            close()
            check(source.subscriptions == 0)
            check((current + retired).all { it.closes == 1 && it.server.nodeCount == 0 && it.server.status is RemoteSessionStatus.Closed })
        }

        /**
         * Clears fixture-owned current messages and closes owners while closed handles can remain reachable for admission checks.
         */
        @TearDown(Level.Trial)
        override fun close() {
            val current = peers
            peers = emptyList()
            current.forEach(Peer::close)
        }

        private fun acquire(): List<Peer> {
            val acquired = mutableListOf<Peer>()
            return runCatching {
                repeat(sessionCount) { index ->
                    val peer = Peer(index + 1L)
                    acquired.add(peer)
                    peer.start()
                }
                acquired.toList()
            }.getOrElse { failure ->
                acquired.asReversed().forEach { runCatching(it::close).exceptionOrNull()?.let { cleanup -> if (cleanup !== failure) failure.addSuppressed(cleanup) } }
                throw failure
            }
        }

        private fun describe(marker: Element?): Element = evaluateComponentTree {
            when (workload) {
                Workload.FixedUpdate, Workload.DynamicLists, Workload.SharedIdle, Workload.SharedUpdate, Workload.Lifecycle -> Observe(source) { value ->
                    Column {
                        val removed = if (workload == Workload.DynamicLists && value) 1 else 0
                        repeat(perOwnerNodes - 2 - removed) { Spacer(Modifier.Empty.background(ArgbColor(if (value) -16776961 else -65536))) }
                    }
                }
                else -> Column {
                    if (marker != null) element(marker)
                    val leaves = perOwnerNodes - 1 - (if (marker == null) 0 else 1)
                    repeat(leaves) { Spacer() }
                }
            }
        }

        /**
         * One server and its current detached declaration/control/ack output, with authoritative edit text outside the runtime cache.
         */
        private inner class Peer(private val identity: Long) : AutoCloseable {
            private var text = "initial"
            private var inputSequence = 0L
            private var editIdentity = 0L
            private var generation = 0L
            private var endpoint = 0L
            private var initialEndpoint = 0L
            private var declaration: RemoteMessage? = null
            private var control: RuntimeUiControl? = null
            private var acknowledgement: RemoteMessage.Acknowledgement? = null
            private var revision = 0L
            private var validationTree: RemoteTree? = null
            private var validationRevision = 0L
            private var validationChildren = emptyList<Long>()
            var updates = 0L
                private set
            var closes = 0
                private set
            private val binding = if (workload == Workload.EditableValues) ProjectionBinding(EDIT, this, { text }, { ProjectionValue.Text(it) }, { value ->
                val decoded = (value as ProjectionValue.Text).value
                require(decoded.length <= 64)
                decoded
            }, { value -> text = value }) else null
            private val marker = when (workload) {
                Workload.OpaqueIdle, Workload.EndpointChurn, Workload.EditableValues -> OpaqueElement(DeclarationProjection(OPAQUE, Unit) { _, scope ->
                    when (workload) {
                        Workload.EndpointChurn -> ProjectionValue.Integer(scope.action(ProjectionAction(EVENT, { Unit }) { acceptedActions++ }, ProjectionValue.Integer(if (selected) 1L else 0L)))
                        Workload.EditableValues -> scope.binding(requireNotNull(binding))
                        else -> ProjectionValue.Integer(0)
                    }
                })
                Workload.PreparingIdle, Workload.PreparingChange -> PreparingElement { preparations++; if (selected) 1 else 0 }
                else -> null
            }
            val server = RemoteServerSession(identity, ProjectionValue.Absent, RemoteRegistry().also(RemoteBuiltins::register).types + setOf(OPAQUE, RESOURCE, EVENT, EDIT), limits, send = ::receive) { describe(marker) }

            fun start() {
                server.tick()
                val initial = requireNotNull(declaration) as RemoteMessage.Snapshot
                control = requireNotNull(initial.control)
                server.receive(RemoteMessage.ControlApplied(identity, requireNotNull(control).sequence))
                if (workload == Workload.EndpointChurn || workload == Workload.EditableValues) {
                    val value = initial.tree.nodes.values.single { it.declaration.type == OPAQUE }.declaration.value
                    if (workload == Workload.EndpointChurn) initialEndpoint = (value as ProjectionValue.Integer).value else readBinding(value)
                }
                if (verifying) beginVerification()
            }

            fun beginVerification() {
                val initial = requireNotNull(declaration) as RemoteMessage.Snapshot
                check(initial.tree.nodes.size == perOwnerNodes)
                val codec = RemoteMessageCodec(limits)
                check(codec.decode(codec.encode(initial)) == initial)
                validationTree = initial.tree
                validationRevision = initial.revision
                verifyTree(initial.tree)
            }

            fun edit(value: String) {
                inputSequence++
                server.receive(RemoteMessage.Action(identity, inputSequence, endpoint, EDIT, ProjectionValue.Sequence(listOf(ProjectionValue.Integer(editIdentity), ProjectionValue.Integer(generation), ProjectionValue.Text(value)))))
            }

            fun switch(presentation: UiPresentation) {
                server.uiSession.switch(presentation)
                server.receive(RemoteMessage.ControlApplied(identity, requireNotNull(control).sequence))
            }

            fun verify() {
                val current = requireNotNull(declaration)
                val codec = RemoteMessageCodec(limits)
                check(codec.decode(codec.encode(current)) == current)
                val expectedNodes = perOwnerNodes - (if (workload == Workload.DynamicLists && selected) 1 else 0)
                check(server.nodeCount == expectedNodes && server.status == RemoteSessionStatus.Open)
                if (current is RemoteMessage.Update) {
                    check(current.revision == revision && current.baseRevision + 1 == current.revision)
                    check(current.patch.changed.map { it.declaration.identity }.toSet().size == current.patch.changed.size)
                    if (workload == Workload.EditableValues) {
                        val fields = ProjectionFields(current.patch.changed.single { it.declaration.type == OPAQUE }.declaration.value)
                        check(fields.long() == editIdentity && fields.long() == generation && fields.long() == inputSequence)
                        check(fields.text() == text && fields.long() == endpoint)
                        fields.finish()
                        check(acknowledgement == RemoteMessage.Acknowledgement(identity, inputSequence, current.revision))
                    }
                }
                val previous = requireNotNull(validationTree)
                val tree = if (current is RemoteMessage.Update && validationRevision != current.revision) {
                    check(current.baseRevision == validationRevision)
                    validationRevision = current.revision
                    current.patch.apply(previous, limits)
                } else previous
                validationTree = tree
                verifyTree(tree)
                if (workload == Workload.ControlsAck) {
                    check(server.uiSession.presentation == if (selected) UiPresentation.Hud else UiPresentation.Screen)
                    check(server.uiSession.status == UiSessionStatus.Ready())
                }
            }

            fun verifyObsoleteAction() {
                server.receive(RemoteMessage.Action(identity, 1, initialEndpoint, EVENT, ProjectionValue.Absent))
                server.tick()
                check(acceptedActions == 0L)
                check(acknowledgement == RemoteMessage.Acknowledgement(identity, 1, revision))
            }

            override fun close() {
                server.close()
                declaration = null
                control = null
                acknowledgement = null
                validationTree = null
                validationChildren = emptyList()
            }

            private fun verifyTree(tree: RemoteTree) {
                val dynamic = workload == Workload.FixedUpdate || workload == Workload.DynamicLists || workload == Workload.SharedIdle || workload == Workload.SharedUpdate || workload == Workload.Lifecycle
                val root = tree.nodes.getValue(tree.root)
                val column = if (dynamic) {
                    check(root.declaration.type == BuiltinProjection.Observe.type && root.modifiers.isEmpty() && root.children.size == 1)
                    ProjectionFields(root.declaration.value).finish()
                    tree.nodes.getValue(root.children.single())
                } else root
                check(column.declaration.type == BuiltinProjection.Column.type && column.modifiers.isEmpty())
                val fields = ProjectionFields(column.declaration.value)
                check(fields.int() == 0 && fields.int() == Arrangement.Start.ordinal && fields.int() == HorizontalAlignment.Start.ordinal)
                fields.finish()
                val prefix = minOf(validationChildren.size, column.children.size)
                check(validationChildren.take(prefix) == column.children.take(prefix))
                validationChildren = column.children
                val leaves = column.children.map(tree.nodes::getValue)
                check(leaves.size + (if (dynamic) 2 else 1) == tree.nodes.size)
                leaves.forEachIndexed { index, node ->
                    check(node.children.isEmpty())
                    if (marker != null && index == 0) {
                        check(node.modifiers.isEmpty())
                        when (workload) {
                            Workload.OpaqueIdle -> check(node.declaration.type == OPAQUE && node.declaration.value == ProjectionValue.Integer(0))
                            Workload.EndpointChurn -> check(node.declaration.type == OPAQUE && 0 < (node.declaration.value as ProjectionValue.Integer).value)
                            Workload.EditableValues -> {
                                check(node.declaration.type == OPAQUE)
                                val edit = ProjectionFields(node.declaration.value)
                                check(edit.long() == editIdentity && edit.long() == generation && edit.long() == inputSequence)
                                check(edit.text() == text && edit.long() == endpoint)
                                edit.finish()
                            }
                            Workload.PreparingIdle, Workload.PreparingChange -> check(node.declaration.type == RESOURCE && node.declaration.value == ProjectionValue.Integer(if (selected) 1L else 0L))
                            else -> error("Unexpected marker case")
                        }
                    } else {
                        check(node.declaration.type == BuiltinProjection.Spacer.type)
                        ProjectionFields(node.declaration.value).finish()
                        if (dynamic) {
                            val background = node.modifiers.single()
                            check(background.type == BuiltinProjection.Background.type)
                            val color = ProjectionFields(background.value)
                            check(color.int() == if (selected) -16776961 else -65536)
                            color.finish()
                        } else check(node.modifiers.isEmpty())
                    }
                }
            }

            private fun readBinding(value: ProjectionValue) {
                val fields = ProjectionFields(value)
                editIdentity = fields.long()
                generation = fields.long()
                check(fields.long() == 0L && fields.text() == text)
                endpoint = fields.long()
                fields.finish()
            }

            private fun receive(message: RemoteMessage) {
                when (message) {
                    is RemoteMessage.Snapshot -> { declaration = message; revision = message.revision }
                    is RemoteMessage.Update -> { declaration = message; revision = message.revision; updates++ }
                    is RemoteMessage.Control -> control = message.state
                    is RemoteMessage.Acknowledgement -> acknowledgement = message
                    is RemoteMessage.Close -> closes++
                    else -> Unit
                }
            }
        }
    }

    /**
     * Consumer-defined opaque declaration with no local presentation phases in this fixture boundary.
     */
    private class OpaqueElement(override val projection: DeclarationProjection<*>) : Element(ElementIdentity.Positional, TYPE) {
        private class OpaqueNode : Node()
        private companion object {
            val TYPE = ElementType(OpaqueElement::class, OpaqueNode::class, { _ -> }, { _ -> OpaqueNode() }, { _, _, _ -> DirtyMask.None })
        }
    }

    /**
     * Pure derived declaration supplier; its retained capability must prepare on every real cutoff.
     */
    private class PreparingElement(val metadata: () -> Int) : Element(ElementIdentity.Positional, TYPE) {
        private class PreparingNode(private val metadata: () -> Int) : Node(), DeclarationProjectionNode {
            private var current = 0
            override val declarationProjection = DeclarationProjection(RESOURCE, Unit) { _, _ -> ProjectionValue.Integer(current.toLong()) }
            override fun prepareDeclaration() { current = metadata() }
        }
        private companion object {
            val TYPE = ElementType(PreparingElement::class, PreparingNode::class, { _ -> }, { PreparingNode(it.metadata) }, { _, _, _ -> DirtyMask.None })
        }
    }

    /**
     * Complete generated case discovery and public-API archive-independent admission.
     */
    public companion object {
        private val OPAQUE = ProjectionType(ResourceId("benchmark", "opaque_declaration"))
        private val RESOURCE = ProjectionType(ResourceId("benchmark", "prepared_declaration"))
        private val EVENT = ProjectionType(ResourceId("benchmark", "declaration_action"))
        private val EDIT = ProjectionType(ResourceId("benchmark", "declaration_edit"))

        /**
         * Requires all 24 actual compiled combinations before timing or selected-site observation.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(RemoteDeclarationBenchmark::class.java), setOf("avgt")).size == 24)
            for (size in Size.entries) {
                for (workload in Workload.entries) {
                    Scene().use { scene ->
                        scene.size = size
                        scene.workload = workload
                        scene.setup()
                        scene.verifyWork()
                    }
                }
            }
        }
    }
}
