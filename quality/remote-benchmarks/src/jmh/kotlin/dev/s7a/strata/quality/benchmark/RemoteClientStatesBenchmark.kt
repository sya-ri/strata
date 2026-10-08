@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteBindingSnapshot
import dev.s7a.strata.runtime.remote.RemoteClientActions
import dev.s7a.strata.runtime.remote.RemoteClientSession
import dev.s7a.strata.runtime.remote.RemoteClientStates
import dev.s7a.strata.runtime.remote.RemoteDeclaration
import dev.s7a.strata.runtime.remote.RemoteEditingState
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteNode
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteStateKey
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
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
 * Measures current editable polling through a real registered client session without Minecraft or a network.
 * Admission and host attachment occur outside timing; update and churn methods include their actual preparation work.
 * All 45 compiled cases retain the same current state count, editing reads and outgoing operations on both runtimes.
 */
public open class RemoteClientStatesBenchmark {
    /**
     * Polls every current editor while none has a local edit.
     */
    @Benchmark
    public fun idleFlush(scene: Scene): Long = scene.idle()

    /**
     * Changes each editable native value and sends its ordered optimistic edit.
     */
    @Benchmark
    public fun localEdits(scene: Scene): Long = scene.edit()

    /**
     * Flushes changed native values before sending a business action on the same sequence stream.
     */
    @Benchmark
    public fun preActionFlush(scene: Scene): Long = scene.action()

    /**
     * Applies a newer authoritative replacement to every retained state entry.
     */
    @Benchmark
    public fun incomingUpdates(scene: Scene): Long = scene.update(false)

    /**
     * Replaces every state address, including passive entries, while retaining the fixed current count.
     */
    @Benchmark
    public fun churn(scene: Scene): Long = scene.update(true)

    /**
     * Current editable membership; every size includes passive and fully editable controls.
     */
    public enum class Membership {
        /**
         * No editable entries.
         */
        None,

        /**
         * Ten editable entries among the fixed current entries.
         */
        Few,

        /**
         * Every current entry is editable.
         */
        All,
    }

    /**
     * One owner-confined registered extension and client session per JMH worker.
     */
    @State(Scope.Thread)
    public open class Scene : AutoCloseable {
        /**
         * Fixed retained state cardinality, independent of tree depth or viewport.
         */
        @JvmField
        @Param("100", "1000", "8192")
        public var entries: Int = 100

        /**
         * Fixed editable fraction injected from the compiled enum.
         */
        @JvmField
        @Param
        public var membership: Membership = Membership.None

        private lateinit var client: RemoteClientSession
        private lateinit var host: RuntimeUiSession
        private lateinit var states: RemoteClientStates
        private lateinit var actions: RemoteClientActions
        private lateinit var cells: Array<Cell>
        private var revision = 1L
        private var generation = 1L
        private var shifted = false
        private var polls = 0L
        private var writes = 0L
        private var created = 0L
        private var released = 0L
        private var lastSequence = 0L
        private var tracing = false
        private val trace = mutableListOf<RemoteMessage.Action>()

        private val editableCount: Int
            get() = when (membership) {
                Membership.None -> 0
                Membership.Few -> 10
                Membership.All -> entries
            }

        /**
         * Primes the actual state store using the registered preparation path before collection.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            cells = Array(entries) { Cell(0) }
            val registry = RemoteRegistry()
            registry.element(TYPE, ::decode, { properties, context ->
                states = context.states
                val offset = if (properties.shifted) entries else 0
                for (index in 0 until entries) {
                    val identity = (offset + index + 1).toLong()
                    if (index < editableCount) {
                        val snapshot = RemoteBindingSnapshot(identity, properties.generation, lastSequence, ProjectionValue.Integer(properties.generation - 1), index.toLong() + 1)
                        context.states.prepare(identity, EDITABLE, {
                            val cell = Cell(properties.generation - 1).also { cells[index] = it }
                            created++
                            RemoteEditingState(cell, EVENT, snapshot, ::integer, ProjectionValue::Integer, {
                                polls++
                                it.value
                            }, { state, value ->
                                writes++
                                state.value = value
                            })
                        }, { it.reconcile(snapshot) }, { released++ })
                    } else {
                        context.states.prepare(identity, PASSIVE, {
                            created++
                            Cell(properties.generation - 1).also { cells[index] = it }
                        }, { it.value = properties.generation - 1 }, { released++ })
                    }
                }
            }) { _, context ->
                actions = context.actions
                evaluateComponentTree { Spacer(context.modifier, context.key) }
            }
            client = RemoteClientSession(snapshot(), registry, send = { message ->
                if (message is RemoteMessage.Action) {
                    lastSequence = message.sequence
                    if (tracing) trace.add(message)
                }
            })
            val content = client.definition(UiText.Literal("State polling")).transfer().content
            host = createRuntimeUiSession { evaluateComponentTree(content) }
            host.attach()
            check(created == entries.toLong())
        }

        /**
         * Polls unchanged values and returns the cumulative callback count without retaining frame history.
         */
        public fun idle(): Long {
            client.flushEdits()
            return polls
        }

        /**
         * Mutates native cells without a notification, then polls and sends every changed editor.
         */
        public fun edit(): Long {
            changeLocal()
            client.flushEdits()
            return lastSequence
        }

        /**
         * Uses the real session action allocator, including its protected nested flush calls.
         */
        public fun action(): Long {
            changeLocal()
            return actions.send(BUSINESS, EVENT, ProjectionValue.Absent)
        }

        /**
         * Advances authoritative generations or switches all addresses before the next atomic preparation.
         */
        public fun update(churn: Boolean): Long {
            generation++
            revision++
            if (churn) shifted = shifted.not()
            client.receive(snapshot())
            return released + writes + polls
        }

        /**
         * Releases the host and current extension state on the constructing worker.
         */
        @TearDown(Level.Trial)
        override fun close() {
            if (::host.isInitialized) host.close()
            if (::client.isInitialized) client.close()
            trace.clear()
        }

        private fun changeLocal() {
            for (index in 0 until editableCount) cells[index].value++
        }

        private fun snapshot(): RemoteMessage.Snapshot {
            val properties = ProjectionValue.Sequence(listOf(ProjectionValue.Flag(shifted), ProjectionValue.Integer(generation)))
            val node = RemoteNode(RemoteDeclaration(1, TYPE, properties), emptyList(), emptyList())
            return RemoteMessage.Snapshot(1, revision, ProjectionValue.Absent, RemoteTree(1, listOf(node)))
        }

        // Wrapping maps is confined to this untimed oracle; timed scenes retain the ordinary production maps.
        @Suppress("UNCHECKED_CAST")
        private fun countTraversal(): List<VisitMap<Any?, Any?>> =
            RemoteClientStates::class.java.declaredFields.mapNotNull { field ->
                if (Map::class.java.isAssignableFrom(field.type).not()) return@mapNotNull null
                field.isAccessible = true
                val counted = VisitMap(field.get(states) as MutableMap<Any?, Any?>)
                field.set(states, counted)
                counted
            }

        /**
         * Checks actual ordered edits, polling visits, replacement and terminal retention outside timing.
         */
        public fun verifyWork() {
            val maps = countTraversal()
            val indexed = maps.size == 2
            val expectedVisits = if (indexed) editableCount else entries
            tracing = true
            fun operation(expectedActions: Int, run: () -> Unit) {
                maps.forEach { it.visits = 0 }
                val beforePolls = polls
                val beforeSequence = lastSequence
                trace.clear()
                run()
                check(polls - beforePolls == editableCount.toLong())
                check(maps.sumOf { it.visits } == expectedVisits.toLong())
                check(trace.size == expectedActions)
                check(trace.map { it.sequence } == (1..expectedActions).map { beforeSequence + it })
            }
            operation(0) { idle() }
            operation(editableCount) { edit() }
            check(trace.map { it.endpoint } == (1..editableCount).map(Int::toLong))
            check(trace.map { it.value } == (1..editableCount).map { identity ->
                ProjectionValue.Sequence(listOf(ProjectionValue.Integer(identity.toLong()), ProjectionValue.Integer(1), ProjectionValue.Integer(1)))
            })
            check(trace.all { it.session == 1L && it.type == EVENT })
            operation(editableCount + 1) { action() }
            check(trace.dropLast(1).map { it.endpoint } == (1..editableCount).map(Int::toLong))
            check(trace.dropLast(1).map { it.value } == (1..editableCount).map { identity ->
                ProjectionValue.Sequence(listOf(ProjectionValue.Integer(identity.toLong()), ProjectionValue.Integer(1), ProjectionValue.Integer(2)))
            })
            check(trace.last().endpoint == BUSINESS && trace.last().value == ProjectionValue.Absent)
            check(trace.all { it.session == 1L && it.type == EVENT })
            val beforeCreated = created
            operation(0) { update(false) }
            check(created == beforeCreated && released == 0L)
            check(cells.take(editableCount).all { it.value == 1L })
            operation(0) { update(true) }
            check(created == beforeCreated + entries && released == entries.toLong())
            check(maps.any { it.size == entries } && maps.all { it.size <= entries })
            println("Client state $entries $membership: $expectedVisits entry visits, $editableCount editable reads per flush; $editableCount ordered edits, ${editableCount + 1} pre-action sends; $entries churn releases")
            close()
            check(maps.all { it.isEmpty() })
            check(released == 2L * entries)
        }

        /**
         * Mutable native editor value; edits deliberately have no dirty notification.
         */
        private class Cell(var value: Long)

        /**
         * Decoded immutable preparation inputs; scalar generations are data rather than capability discriminators.
         */
        private data class Properties(val shifted: Boolean, val generation: Long)

        /**
         * Counts real value traversal in an independent, untimed session without modifying production source.
         */
        private class VisitMap<K, V>(private val backing: MutableMap<K, V>) : MutableMap<K, V> by backing {
            var visits = 0L
            override val values: MutableCollection<V> = object : AbstractMutableCollection<V>() {
                override val size: Int get() = backing.size

                override fun add(element: V): Boolean = error("Map values cannot be added")

                override fun iterator(): MutableIterator<V> {
                    val iterator = backing.values.iterator()
                    return object : MutableIterator<V> {
                        override fun hasNext(): Boolean = iterator.hasNext()

                        override fun next(): V {
                            visits++
                            return iterator.next()
                        }

                        override fun remove() = iterator.remove()
                    }
                }
            }
        }

        private companion object {
            val TYPE = ProjectionType(ResourceId("benchmark", "client_states"))
            val EVENT = ProjectionType(ResourceId("benchmark", "edit"))
            val EDITABLE = RemoteStateKey(RemoteEditingState::class)
            val PASSIVE = RemoteStateKey(Cell::class)
            val BUSINESS = 100_000L

            fun integer(value: ProjectionValue): Long = requireNotNull(value as? ProjectionValue.Integer).value

            fun decode(value: ProjectionValue): Properties {
                val fields = ProjectionFields(value)
                val properties = Properties(fields.flag(), fields.long())
                fields.finish()
                return properties
            }
        }
    }

    /**
     * Compiled discovery owns the complete case matrix and independent work checks, with no launcher registry.
     */
    public companion object {
        /**
         * Verifies all sizes, membership controls and operations without collecting time or allocating history.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(RemoteClientStatesBenchmark::class.java), setOf("avgt")).size == 45)
            for (entries in listOf(100, 1_000, 8_192)) {
                for (membership in Membership.entries) {
                    val scene = Scene()
                    scene.entries = entries
                    scene.membership = membership
                    try {
                        scene.setUp()
                        scene.verifyWork()
                    } finally {
                        scene.close()
                    }
                }
            }
        }
    }
}
