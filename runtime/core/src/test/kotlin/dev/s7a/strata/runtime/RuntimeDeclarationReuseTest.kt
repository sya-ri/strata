@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DeclarationProjectionNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.SessionAttachmentNode
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.spi.RuntimeDeclaration
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Fixed-value completeness, ordered replacement and release without relying on phase dirtiness or GC.
 */
internal class RuntimeDeclarationReuseTest {
    @Test
    fun completeFixedTreesShareOnlyTheirCurrentOrderedSnapshotsAtBothAcceptanceSizes() {
        for (count in listOf(100, 8192)) {
            UiTree().use { tree ->
                val leaves = List(count - 1) { index -> element(index, modifier = Modifier.Empty.background(ArgbColor(index)).size(1, 1)) }
                tree.update(element(0, children = leaves))
                val first = tree.projectDeclarations { it }
                repeat(3) { assertSame(first, tree.projectDeclarations { it }) }
                assertEquals(count - 1, first.children.size)
                assertTrue(0 < first.revision)
                assertEquals(count, entries(root(tree)).count { it.declarationSnapshot != null })
                entries(root(tree)).forEach { retained -> assertSame(retained.element, requireNotNull(retained.declarationSnapshot).element) }
            }
        }
    }

    @Test
    fun dirtyNoneDescriptionAndOrderedModifierChildChangesReplaceTheCompleteRevision() {
        UiTree().use { tree ->
            val a = element(1, identity = ElementIdentity.Keyed(ElementKey(1)))
            val b = element(2, identity = ElementIdentity.Keyed(ElementKey(2)))
            tree.update(element(0, listOf(a, b), Modifier.Empty.background(ArgbColor(1)).size(2, 3)))
            val first = tree.projectDeclarations { it }
            tree.update(element(0, listOf(b, a), Modifier.Empty.size(2, 3).background(ArgbColor(2))))
            val reordered = tree.projectDeclarations { it }
            assertTrue(first.revision < reordered.revision)
            assertEquals(first.children.map { it.identity }.reversed(), reordered.children.map { it.identity })
            assertEquals(first.modifiers.map { it.projection?.type }.reversed(), reordered.modifiers.map { it.projection?.type })
            assertSame(reordered, tree.projectDeclarations { it })
            tree.update(element(3, listOf(b, a)))
            val changed = tree.projectDeclarations { it }
            assertTrue(reordered.revision < changed.revision)
            assertEquals(first.identity, changed.identity)
            assertEquals(2, first.children.size)
            assertEquals(2, first.modifiers.size)
        }
    }

    @Test
    fun arbitraryEqualEncoderNeverReusesItsOwnOrAnAncestorsRevision() {
        val opaque = ProbeElement(DeclarationProjection(PROJECTION, ProjectionValue.Integer(1)) { value, _ -> value })
        UiTree().use { tree ->
            tree.update(element(0, listOf(opaque)))
            val first = tree.projectDeclarations { it }
            val second = tree.projectDeclarations { it }
            assertNotSame(first, second)
            assertNotSame(first.children.single(), second.children.single())
            assertTrue(first.revision < second.revision)
            assertTrue(first.children.single().revision < second.children.single().revision)
            assertTrue(entries(root(tree)).all { it.declarationSnapshot == null })
        }
    }

    @Test
    fun fixedRetainedCapabilitiesStillPrepareAndReplaceAfterDerivedMetadataChanges() {
        val probe = PreparedProbe()
        UiTree().use { tree ->
            tree.update(PreparingElement(probe))
            val first = tree.projectDeclarations { it }
            assertSame(first, tree.projectDeclarations { it })
            assertEquals(2, probe.preparations)
            probe.value = 1
            val changed = tree.projectDeclarations { it }
            assertTrue(first.revision < changed.revision)
            assertEquals(first.identity, changed.identity)
            assertNotSame(first.projection, changed.projection)
            assertSame(changed, tree.projectDeclarations { it })
            assertEquals(4, probe.preparations)
            probe.enabled = false
            val disabled = tree.projectDeclarations { it }
            assertTrue(changed.revision < disabled.revision)
            assertEquals(false, requireNotNull(disabled.projection).inputEnabled)
            probe.type = ProjectionType(ResourceId("test", "replacement_declaration"))
            val retyped = tree.projectDeclarations { it }
            assertTrue(disabled.revision < retyped.revision)
            assertEquals(probe.type, requireNotNull(retyped.projection).type)
            assertSame(retyped, tree.projectDeclarations { it })
        }
    }

    @Test
    fun detachmentRemovalAndPreparationFailureReleaseKeysBeforeUserCallbacks() {
        val probe = PreparedProbe()
        val tree = UiTree()
        tree.update(element(0, listOf(PreparingElement(probe))))
        tree.projectDeclarations { it }
        val captured = entries(root(tree))
        probe.detached = { assertTrue(captured.all { it.declarationSnapshot == null }) }
        tree.sessionDetached()
        assertTrue(captured.all { it.declarationSnapshot == null })
        tree.sessionAttached()
        val reattached = tree.projectDeclarations { it }
        assertSame(reattached, tree.projectDeclarations { it })
        probe.disposed = { assertTrue(captured.all { it.declarationSnapshot == null }) }
        tree.update(element(0))
        assertNull(captured.last().declarationSnapshot)
        assertEquals(1, probe.disposals)
        tree.projectDeclarations { it }
        tree.close()
        val failureProbe = PreparedProbe()
        val failed = UiTree()
        failed.update(PreparingElement(failureProbe))
        failed.projectDeclarations { it }
        val failedEntries = entries(root(failed))
        failureProbe.disposed = { assertTrue(failedEntries.all { it.declarationSnapshot == null }) }
        val primary = IllegalArgumentException("prepared metadata failed")
        failureProbe.failure = primary
        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { failed.projectDeclarations { Unit } })
        failed.close()
        failed.close()
        assertEquals(1, failureProbe.disposals)
    }

    @Test
    fun repeatedCurrentTreeReplacementKeepsNoRemovedSnapshotKeys() {
        UiTree().use { tree ->
            var previousChild: RetainedNode? = null
            val removed = mutableListOf<RetainedNode>()
            repeat(64) { value ->
                val children = if (value % 2 == 0) listOf(element(value)) else emptyList()
                tree.update(element(value, children))
                if (children.isEmpty()) previousChild?.let(removed::add)
                val snapshot = tree.projectDeclarations { it }
                val current = entries(root(tree))
                assertEquals(current.size, current.count { it.declarationSnapshot != null })
                assertEquals(children.size, snapshot.children.size)
                current.forEach { assertSame(it.element, requireNotNull(it.declarationSnapshot).element) }
                removed.forEach { assertNull(it.declarationSnapshot) }
                previousChild = root(tree).children.singleOrNull()
            }
        }
    }

    @Test
    fun wrongOwnerCannotReadOrAlterTheCurrentProjectionKey() {
        UiTree().use { tree ->
            tree.update(element(1))
            val first = tree.projectDeclarations { it }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<RuntimeDeclaration> { tree.projectDeclarations { it } }
                val failure = assertThrows(ExecutionException::class.java, future::get)
                assertTrue(failure.cause is IllegalStateException)
            } finally {
                executor.shutdownNow()
            }
            assertSame(first, tree.projectDeclarations { it })
        }
    }

    private fun root(tree: UiTree): RetainedNode = tree.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(tree) as RetainedNode

    private fun entries(root: RetainedNode): List<RetainedNode> = listOf(root) + root.children.flatMap(::entries)

    private fun element(value: Int, children: List<Element> = emptyList(), modifier: Modifier = Modifier.Empty, identity: ElementIdentity = ElementIdentity.Positional): ProbeElement = ProbeElement(DeclarationProjection.fixed(PROJECTION, ProjectionValue.Integer(value.toLong())), children, modifier, identity)

    /**
     * Consumer-style immutable description whose typed update deliberately returns DirtyMask.None.
     */
    private class ProbeElement(override val projection: DeclarationProjection<*>, children: List<Element> = emptyList(), modifier: Modifier = Modifier.Empty, identity: ElementIdentity = ElementIdentity.Positional) : Element(identity, TYPE, children, modifier) {
        private class ProbeNode : Node()
        private companion object {
            val TYPE = ElementType(ProbeElement::class, ProbeNode::class, { _ -> }, { _ -> ProbeNode() }, { _, _, _ -> DirtyMask.None })
        }
    }

    /**
     * Test-owned current derived metadata and lifecycle counters, without a production diagnostic hook.
     */
    private class PreparedProbe {
        var value = 0
        var enabled = true
        var type = PROJECTION
        var preparations = 0
        var disposals = 0
        var failure: Throwable? = null
        var detached: () -> Unit = {}
        var disposed: () -> Unit = {}
    }

    /**
     * Retained resource capability exposing an immutable current projection after mandatory preparation.
     */
    private class PreparingElement(val probe: PreparedProbe) : Element(ElementIdentity.Positional, TYPE) {
        private class PreparedNode(private val probe: PreparedProbe) : Node(), DeclarationProjectionNode, LifecycleNode, SessionAttachmentNode {
            private var value = probe.value
            private var enabled = probe.enabled
            private var type = probe.type
            override var declarationProjection: DeclarationProjection<*> = DeclarationProjection.fixed(type, ProjectionValue.Integer(value.toLong()), enabled)
                private set

            override fun prepareDeclaration() {
                probe.preparations++
                probe.failure?.let { throw it }
                if (value != probe.value || enabled != probe.enabled || type != probe.type) {
                    value = probe.value
                    enabled = probe.enabled
                    type = probe.type
                    declarationProjection = DeclarationProjection.fixed(type, ProjectionValue.Integer(value.toLong()), enabled)
                }
            }

            override fun attach(): Unit = Unit
            override fun detach(): Unit = Unit
            override fun sessionAttached(): Unit = Unit
            override fun sessionDetached() = probe.detached()
            override fun dispose() {
                probe.disposals++
                probe.disposed()
            }
        }
        private companion object {
            val TYPE = ElementType(PreparingElement::class, PreparedNode::class, { _ -> }, { PreparedNode(it.probe) }, { _, _, _ -> DirtyMask.None })
        }
    }

    private companion object {
        val PROJECTION = ProjectionType(ResourceId("test", "declaration"))
    }
}
