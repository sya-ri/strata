package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.render.ArgbColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies modifier positional identity, keyed component retention, cache invalidation, and validation rollback.
 */
internal class ModifierRuntimeIdentityTest {
    @Test
    fun keyedComponentsAndModifierNodesRetainIdentityAcrossShapeChanges() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 10, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 11, ModifierTestFixture.Kind.Second)
        val firstKey = TestProbe.ProbeId("first-key")
        val secondKey = TestProbe.ProbeId("second-key")
        val firstTag = TestProbe.ProbeId("first")
        val secondTag = TestProbe.ProbeId("second")

        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(firstTag, firstKey),
                    componentProbe.element(secondTag, secondKey),
                ),
            ),
        )
        val firstNode = componentProbe.nodeForTag(firstTag)
        val secondNode = componentProbe.nodeForTag(secondTag)
        val rootNode = componentProbe.nodeForTag(TestProbe.ProbeId("root"))
        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(secondTag, secondKey),
                    componentProbe.element(firstTag, firstKey),
                ),
                modifier = Modifier.Empty.then(first),
            ),
        )
        assertSame(firstNode, componentProbe.nodeForTag(firstTag))
        assertSame(secondNode, componentProbe.nodeForTag(secondTag))
        assertSame(rootNode, componentProbe.nodeForTag(TestProbe.ProbeId("root")))
        val retainedModifier = modifierProbe.nodes.getValue(10)

        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(secondTag, secondKey),
                    componentProbe.element(firstTag, firstKey),
                ),
                modifier = modifierFixture.chain(first, second),
            ),
        )
        assertSame(retainedModifier, modifierProbe.nodes.getValue(10))
        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(secondTag, secondKey),
                    componentProbe.element(firstTag, firstKey),
                ),
                modifier = Modifier.Empty,
            ),
        )
        assertSame(rootNode, componentProbe.nodeForTag(TestProbe.ProbeId("root")))
        tree.close()
    }

    @Test
    fun keyedChildModifierEditsRetainChildIdentityDuringReorder() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 12, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 13, ModifierTestFixture.Kind.Second)
        val firstKey = TestProbe.ProbeId("modifier-child-first-key")
        val secondKey = TestProbe.ProbeId("modifier-child-second-key")
        val firstTag = TestProbe.ProbeId("modifier-child-first")
        val secondTag = TestProbe.ProbeId("modifier-child-second")

        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(firstTag, firstKey, modifier = Modifier.Empty.then(first)),
                    componentProbe.element(secondTag, secondKey),
                ),
            ),
        )
        val firstNode = componentProbe.nodeForTag(firstTag)
        val secondNode = componentProbe.nodeForTag(secondTag)
        val firstModifier = modifierProbe.nodes.getValue(12)
        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(secondTag, secondKey, modifier = Modifier.Empty.then(second)),
                    componentProbe.element(firstTag, firstKey, modifier = modifierFixture.chain(first, second)),
                ),
            ),
        )
        assertSame(firstNode, componentProbe.nodeForTag(firstTag))
        assertSame(secondNode, componentProbe.nodeForTag(secondTag))
        assertSame(firstModifier, modifierProbe.nodes.getValue(12))

        tree.update(
            componentProbe.root(
                listOf(
                    componentProbe.element(secondTag, secondKey),
                    componentProbe.element(firstTag, firstKey),
                ),
            ),
        )
        assertSame(firstNode, componentProbe.nodeForTag(firstTag))
        assertSame(secondNode, componentProbe.nodeForTag(secondTag))
        tree.close()
    }

    @Test
    fun distinctTypeReorderInvalidatesMeasuredCaches() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 76, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 77, ModifierTestFixture.Kind.Second)
        val constraints = Constraints.fixed(10, 10)

        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(first, second)))
        tree.measure(constraints)
        tree.layout()
        val initialComponentMeasures = componentProbe.measureCalls
        val initialFirstMeasures = modifierProbe.measureCalls.getValue(76)
        val initialSecondMeasures = modifierProbe.measureCalls.getValue(77)

        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(second, first)))
        tree.measure(constraints)
        assertTrue(initialComponentMeasures < componentProbe.measureCalls)
        assertTrue(initialFirstMeasures < modifierProbe.measureCalls.getValue(76))
        assertTrue(initialSecondMeasures < modifierProbe.measureCalls.getValue(77))
        tree.layout()
        tree.close()
    }

    @Test
    fun repeatedSameTokenPositionsReuseEachNodeAndRunTypedUpdates() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val first = modifierFixture.modifier(modifierProbe, 78, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 79, ModifierTestFixture.Kind.First)
        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(first, second)))
        val firstNode = modifierProbe.nodes.getValue(78)
        val secondNode = modifierProbe.nodes.getValue(79)
        modifierProbe.events.clear()

        val updatedFirst = modifierFixture.modifier(modifierProbe, 80, ModifierTestFixture.Kind.First)
        val updatedSecond = modifierFixture.modifier(modifierProbe, 81, ModifierTestFixture.Kind.First)
        tree.update(componentProbe.root(emptyList(), modifier = modifierFixture.chain(updatedFirst, updatedSecond)))

        assertSame(firstNode, modifierProbe.nodes.getValue(78))
        assertSame(secondNode, modifierProbe.nodes.getValue(79))
        assertEquals(
            listOf(
                ModifierTestFixture.Update(78, 80),
                ModifierTestFixture.Update(79, 81),
            ),
            modifierProbe.updates,
        )
        assertEquals(emptyList<ModifierTestFixture.Event>(), modifierProbe.events)
        tree.close()
    }

    @Test
    fun structuralModifierEditsInvalidateCleanMeasureCache() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val childTag = TestProbe.ProbeId("child")
        val child = componentProbe.element(childTag)
        val first = modifierFixture.modifier(modifierProbe, 20, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 21, ModifierTestFixture.Kind.Second)
        val constraints = Constraints.fixed(10, 10)

        tree.update(componentProbe.root(listOf(child)))
        tree.measure(constraints)
        tree.layout()
        val initialMeasures = componentProbe.measureCalls
        tree.update(componentProbe.root(listOf(child), modifier = Modifier.Empty.then(first)))
        tree.measure(constraints)
        assertTrue(initialMeasures < componentProbe.measureCalls)
        val afterInsertion = componentProbe.measureCalls
        tree.layout()
        tree.update(componentProbe.root(listOf(child), modifier = modifierFixture.chain(first, second)))
        tree.measure(constraints)
        assertTrue(afterInsertion < componentProbe.measureCalls)
        tree.layout()
        tree.close()
    }

    @Test
    fun nestedModifierRemovalAndComponentMeasureUpdateInvalidateAncestors() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val counts = MeasurementCounts()
        val first = modifierFixture.modifier(modifierProbe, 22, ModifierTestFixture.Kind.First)
        val second = modifierFixture.modifier(modifierProbe, 23, ModifierTestFixture.Kind.Second)
        val rootTag = TestProbe.ProbeId("nested-root")
        val parentKey = TestProbe.ProbeId("nested-parent-key")
        val childKey = TestProbe.ProbeId("nested-child-key")
        val constraints = Constraints(maxWidth = 10, maxHeight = 10)
        tree.update(
            nestedMeasureElement(
                componentProbe,
                rootTag,
                parentKey,
                childKey,
                modifierFixture.chain(first, second),
                childMeasureDirty = false,
                counts,
            ),
        )
        tree.measure(constraints)
        tree.layout()
        assertEquals(1, counts.root)
        assertEquals(1, counts.parent)
        assertEquals(1, counts.child)
        assertEquals(1, modifierProbe.measureCalls.getValue(22))
        assertEquals(1, modifierProbe.measureCalls.getValue(23))

        tree.update(
            nestedMeasureElement(
                componentProbe,
                rootTag,
                parentKey,
                childKey,
                Modifier.Empty.then(second),
                childMeasureDirty = true,
                counts,
            ),
        )
        tree.measure(constraints)
        assertEquals(2, counts.root)
        assertEquals(2, counts.parent)
        assertEquals(2, counts.child)
        assertEquals(1, modifierProbe.measureCalls.getValue(22))
        assertEquals(2, modifierProbe.measureCalls.getValue(23))
        tree.layout()
        tree.close()
    }

    private fun nestedMeasureElement(
        probe: TestProbe,
        rootTag: TestProbe.ProbeId,
        parentKey: TestProbe.ProbeId,
        childKey: TestProbe.ProbeId,
        childModifier: Modifier,
        childMeasureDirty: Boolean,
        counts: MeasurementCounts,
    ): TestProbe.ProbeElement {
        val child =
            probe.element(
                TestProbe.ProbeId("nested-child"),
                childKey,
                modifier = childModifier,
                measureDirty = childMeasureDirty,
                onMeasure = { counts.child += 1 },
            )
        val parent =
            probe.element(
                TestProbe.ProbeId("nested-parent"),
                parentKey,
                children = listOf(child),
                onMeasure = { counts.parent += 1 },
            )
        return probe.element(
            rootTag,
            children = listOf(parent),
            onMeasure = { counts.root += 1 },
        )
    }

    @Test
    fun fullModifierValidationRollsBackAndValidRetryReusesOutput() {
        val componentProbe = TestProbe()
        val modifierFixture = ModifierTestFixture()
        val modifierProbe = ModifierTestFixture.Probe()
        val tree = UiTree()
        val valid = modifierFixture.modifier(modifierProbe, 30, ModifierTestFixture.Kind.First)
        tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty.then(valid)))
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        val oldPaint = tree.paint()
        val oldEvents = modifierProbe.events.toList()
        val oldNode = modifierProbe.nodes.getValue(30)

        val invalid =
            modifierFixture.modifier(
                modifierProbe,
                30,
                ModifierTestFixture.Kind.First,
                invalid = true,
            )
        assertThrows(IllegalArgumentException::class.java) {
            tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty.then(invalid)))
        }
        assertEquals(TreeState.Active, tree.state)
        assertEquals(oldEvents, modifierProbe.events)
        assertSame(oldNode, modifierProbe.nodes.getValue(30))
        assertEquals(oldPaint, tree.paint())

        tree.update(componentProbe.root(emptyList(), modifier = Modifier.Empty.then(valid)))
        assertEquals(oldEvents, modifierProbe.events)
        tree.close()
    }

    private class MeasurementCounts {
        var root: Int = 0
        var parent: Int = 0
        var child: Int = 0
    }
}
