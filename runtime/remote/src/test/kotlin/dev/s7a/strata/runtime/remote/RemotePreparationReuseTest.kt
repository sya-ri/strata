package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.state.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Complete factory keys, phase/state parity and admission/release through real immutable trees and client sessions.
 */
internal class RemotePreparationReuseTest {
    @Test
    fun unchangedOneFewAndAllPropertiesReuseOnlyTheCurrentTrustedFactoryRecords() {
        for (count in listOf(100, 8192)) {
            for (changed in listOf(0, 1, 3, count)) {
                val probe = Probe()
                val firstTree = tree(count)
                val first = probe.registry.prepare(firstTree)
                val records = firstTree.nodes.values.mapIndexed { index, node -> if (index < changed) RemoteNode(node.declaration.copy(value = ProjectionValue.Integer(1)), node.modifiers, node.children) else node }
                val nextTree = RemoteTree(firstTree.root, records)
                val next = probe.registry.prepare(nextTree, previous = first)
                assertEquals(count + changed, probe.components)
                assertEquals(32, probe.modifiers)
                first.decoded.forEach { (identity, old) ->
                    if (identity <= changed.toLong()) assertNotSame(old, next.decoded.getValue(identity)) else assertSame(old, next.decoded.getValue(identity))
                    assertSame(old.modifiers, next.decoded.getValue(identity).modifiers)
                }
                assertEquals(count, next.decoded.size)
                assertEquals(firstTree, first.tree)
                assertEquals(firstTree.nodes.keys.toList(), next.tree.nodes.keys.toList())
            }
        }
    }

    @Test
    fun reorderedChangedAndRetiredModifiersPreserveIdentityAndOnlyCurrentOrderedCaptures() {
        val probe = Probe()
        val first = probe.registry.prepare(tree(100))
        val old = first.tree.nodes.getValue(1)
        val reordered = old.modifiers.reversed()
        val nextRoot = RemoteNode(old.declaration, reordered, old.children)
        val next = probe.registry.prepare(RemoteTree(1, listOf(nextRoot) + first.tree.nodes.values.drop(1)), previous = first)
        assertNotSame(first.decoded.getValue(1), next.decoded.getValue(1))
        assertEquals(32, probe.modifiers)
        first.decoded.getValue(1).modifiers.reversed().zip(next.decoded.getValue(1).modifiers).forEach { (before, after) -> assertSame(before, after) }
        val replacement = reordered.drop(1) + RemoteDeclaration(20000, MODIFIER, ProjectionValue.Integer(7))
        val finalRoot = RemoteNode(old.declaration, replacement, old.children)
        val final = probe.registry.prepare(RemoteTree(1, listOf(finalRoot) + first.tree.nodes.values.drop(1)), previous = next)
        assertEquals(33, probe.modifiers)
        assertNotSame(next.decoded.getValue(1).modifiers.first(), final.decoded.getValue(1).modifiers.last())
        assertEquals(reordered, next.tree.nodes.getValue(1).modifiers)
        assertEquals(32, final.decoded.getValue(1).modifiers.size)
        val created = mutableListOf<Long>()
        probe.created = created::add
        RemoteClientStates(RemoteLimits()).use { states ->
            final.prepare(states)
            final.build(RemoteClientActions { _, _, _ -> error("Unexpected action") }, states)
        }
        assertEquals(replacement.map { (it.value as ProjectionValue.Integer).value }, created)
    }

    @Test
    fun topologyOnlyChangesReuseFactoriesButReconstructionUsesTheNewValidatedChildren() {
        val probe = Probe()
        val first = probe.registry.prepare(tree(100))
        val records = first.tree.nodes.values.toList().dropLast(1).map { node -> if (node.declaration.identity == 1L) RemoteNode(node.declaration, node.modifiers, node.children.dropLast(1)) else node }
        val next = probe.registry.prepare(RemoteTree(1, records), previous = first)
        assertSame(first.decoded.getValue(1), next.decoded.getValue(1))
        assertEquals(99, next.decoded.size)
        assertTrue((100L in next.decoded).not())
        RemoteClientStates(RemoteLimits()).use { states ->
            next.prepare(states)
            val built = next.build(RemoteClientActions { _, _, _ -> error("Unexpected action") }, states)
            assertEquals(98, built.children.size)
        }
        assertEquals(99, first.tree.nodes.getValue(1).children.size)
    }

    @Test
    fun aDifferentFrozenRegistryCannotReuseAnotherRegistrysDecodedFactories() {
        val first = Probe()
        val previous = first.registry.prepare(tree(100))
        val other = Probe()
        val next = other.registry.prepare(previous.tree, previous = previous)
        assertEquals(100, other.components)
        assertEquals(32, other.modifiers)
        previous.decoded.forEach { (identity, node) -> assertNotSame(node, next.decoded.getValue(identity)) }
    }

    @Test
    fun customPreparationAlwaysRunsOwnersBeforeReferencesAndKeepsCurrentStateIdentity() {
        val probe = Probe(stateful = true)
        val first = probe.registry.prepare(tree(100))
        RemoteClientStates(RemoteLimits()).use { states ->
            first.prepare(states)
            val owned = states.get(2, probe.key)
            val changed = first.tree.nodes.values.map { node -> if (node.declaration.identity == 2L) RemoteNode(node.declaration.copy(value = ProjectionValue.Integer(9)), node.modifiers, node.children) else node }
            val next = probe.registry.prepare(RemoteTree(1, changed), previous = first)
            probe.phases.clear()
            next.prepare(states)
            assertSame(owned, states.get(2, probe.key))
            assertEquals(9L, owned.value)
            assertEquals(List(100) { RemotePreparationPhase.Owners } + List(32) { RemotePreparationPhase.References }, probe.phases)
            assertEquals(200, probe.preparations)
            val fewer = next.tree.nodes.values.toList().dropLast(1).map { node -> if (node.declaration.identity == 1L) RemoteNode(node.declaration, node.modifiers, node.children.dropLast(1)) else node }
            probe.registry.prepare(RemoteTree(1, fewer), previous = next).prepare(states)
            assertEquals(listOf(100L), probe.released)
        }
        assertEquals(100, probe.released.toSet().size)
        assertEquals(100, probe.released.size)
    }

    @Test
    fun reusedTreesStillConsumeEveryPhaseVisitAndRejectUnsupportedValuesBeforePreparation() {
        val probe = Probe(stateful = true)
        val first = probe.registry.prepare(tree(100))
        val exact = RemoteLimits(valueEntries = 132)
        val next = probe.registry.prepare(first.tree, exact, first)
        RemoteClientStates(exact).use { states ->
            val failure = assertThrows(RemoteProtocolException::class.java) { next.prepare(states, exact) }
            assertEquals(RemoteFailure.ResourceLimit, failure.reason)
            assertEquals(100, probe.preparations)
        }
        val before = probe.preparations
        val malformedRoot = RemoteNode(first.tree.nodes.getValue(1).declaration.copy(value = ProjectionValue.Integer(-1)), first.tree.nodes.getValue(1).modifiers, first.tree.nodes.getValue(1).children)
        assertThrows(IllegalArgumentException::class.java) { probe.registry.prepare(RemoteTree(1, listOf(malformedRoot) + first.tree.nodes.values.drop(1)), previous = first) }
        assertEquals(before, probe.preparations)
        val unsupported = RemoteNode(malformedRoot.declaration.copy(type = ProjectionType(ResourceId("test", "unsupported"))))
        assertThrows(IllegalArgumentException::class.java) { probe.registry.prepare(RemoteTree(1, listOf(unsupported)), previous = first) }
        assertEquals(before, probe.preparations)
        assertThrows(RemoteProtocolException::class.java) { probe.registry.prepare(first.tree, exact.copy(valueEntries = 131), first) }
    }

    @Test
    fun invalidPatchClosesTheRealSessionWithoutPublishingOrPreparingAPrefix() {
        val probe = Probe(stateful = true)
        val first = tree(100)
        val messages = mutableListOf<RemoteMessage>()
        val client = RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, first), probe.registry, send = messages::add)
        val invalid = RemotePatch(1, listOf(RemoteNode(first.nodes.getValue(1).declaration.copy(value = ProjectionValue.Integer(-1)), first.nodes.getValue(1).modifiers, first.nodes.getValue(1).children)), emptyList())
        val before = probe.preparations
        assertThrows(IllegalArgumentException::class.java) { client.receive(RemoteMessage.Update(1, 1, 2, invalid)) }
        assertEquals(before, probe.preparations)
        assertEquals(listOf(1L), messages.filterIsInstance<RemoteMessage.Applied>().map { it.revision })
        assertEquals(0, client.nodeCount)
        assertTrue(client.status is RemoteSessionStatus.Closed)
        val current = client.javaClass.getDeclaredField("current").apply { isAccessible = true }.get(client)
        assertNull((current as State<*>).value)
        client.close()
        assertEquals(100, probe.released.size)
        assertEquals(1, messages.filterIsInstance<RemoteMessage.Close>().size)
    }

    private fun tree(count: Int): RemoteTree {
        val modifiers = List(32) { index -> RemoteDeclaration(10000L + index, MODIFIER, ProjectionValue.Integer(index.toLong())) }
        val root = RemoteNode(RemoteDeclaration(1, COMPONENT, ProjectionValue.Integer(0)), modifiers, (2L..count.toLong()).toList())
        return RemoteTree(1, listOf(root) + (2L..count.toLong()).map { RemoteNode(RemoteDeclaration(it, COMPONENT, ProjectionValue.Integer(0))) })
    }

    /**
     * Detached decoded scalar with a stable client state token and no native or server owner reference.
     */
    private class Owned(var value: Long)

    /**
     * Trusted registry fixtures expose only detached counters and the current state's ordinary public token.
     */
    private class Probe(stateful: Boolean = false) {
        val registry = RemoteRegistry()
        val key = RemoteStateKey(Owned::class)
        var components = 0
        var modifiers = 0
        var preparations = 0
        val phases = mutableListOf<RemotePreparationPhase>()
        val released = mutableListOf<Long>()
        var created: (Long) -> Unit = {}

        init {
            val decode: (ProjectionValue) -> Long = { value ->
                val scalar = (value as ProjectionValue.Integer).value
                require(0 <= scalar)
                components++
                scalar
            }
            val create: (Long, RemoteElementContext) -> Element = { _, context -> evaluateComponentTree { Column(context.modifier, context.key) { context.children.forEach(::element) } } }
            if (stateful) {
                registry.element(COMPONENT, decode, { value, context ->
                    preparations++
                    phases.add(RemotePreparationPhase.Owners)
                    val identity = context.identity
                    context.states.prepare(identity, key, { Owned(value) }, { it.value = value }, { released.add(identity) })
                }, create = create)
                registry.statefulModifier(MODIFIER, { value -> modifiers++; (value as ProjectionValue.Integer).value }, { _, _ -> phases.add(RemotePreparationPhase.References) }) { value, _ -> created(value); Modifier.Empty.background(ArgbColor(value.toInt())) }
            } else {
                registry.element(COMPONENT, decode, create = create)
                registry.modifier(MODIFIER, { value -> modifiers++; (value as ProjectionValue.Integer).value }) { value, _ -> created(value); Modifier.Empty.background(ArgbColor(value.toInt())) }
            }
        }
    }

    private companion object {
        val COMPONENT = ProjectionType(ResourceId("test", "prepared_component"))
        val MODIFIER = ProjectionType(ResourceId("test", "prepared_modifier"))
    }
}
