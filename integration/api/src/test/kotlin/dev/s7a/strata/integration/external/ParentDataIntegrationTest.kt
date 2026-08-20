package dev.s7a.strata.integration.external

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.layout.ParentDataScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.TreeState
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies direct-child parent-data traversal through the public external integration surface.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ParentDataIntegrationTest {
    @Test
    fun matchingProviderIsReadInMeasureAndLayoutAndChildIsMeasuredAndPlacedOnce() {
        val parentProbe = ParentDataProbe()
        val value = ParentDataValue(7)
        val child =
            ComponentParentDataElement(
                parentDataProbe = parentProbe,
                modifier = Modifier.Empty.then(ParentDataModifierElement(parentProbe, value = value)),
            )
        val description = ParentDataConsumerElement(parentProbe, children = listOf(child))
        val tree = UiTree()

        tree.update(description)
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()

        assertSame(value, parentProbe.consumerMeasureValues.single())
        assertSame(value, parentProbe.consumerLayoutValues.single())
        assertEquals(1, parentProbe.consumerMeasureCalls)
        assertEquals(1, parentProbe.consumerLayoutCalls)
        assertEquals(1, parentProbe.consumerMeasureChildCalls)
        assertEquals(1, parentProbe.consumerPlaceChildCalls)
        assertEquals(2, parentProbe.providers.single().readCount)
        assertEquals(
            listOf(
                ParentDataEvent.ProviderRead,
                ParentDataEvent.ComponentMeasure,
                ParentDataEvent.ProviderRead,
                ParentDataEvent.ComponentLayout,
            ),
            parentProbe.events,
        )
        tree.close()
    }

    @Test
    fun aDifferentKeyWithTheSameRuntimeClassDoesNotMatch() {
        val parentProbe = ParentDataProbe()
        val externalProbe = ExternalProbe()
        val description =
            treeWithChild(
                parentProbe,
                externalProbe,
                Modifier.Empty.then(ParentDataModifierElement(parentProbe)),
                parentDataKey = ParentDataModifierElement.OTHER_KEY,
            )

        val tree = UiTree()
        tree.update(description)
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()

        assertEquals(listOf(null), parentProbe.consumerMeasureValues)
        assertEquals(listOf(null), parentProbe.consumerLayoutValues)
        assertEquals(0, parentProbe.providers.single().readCount)
        tree.close()
    }

    @Test
    fun innermostMatchingProviderShadowsOuterWithoutInvokingIt() {
        val parentProbe = ParentDataProbe()
        val externalProbe = ExternalProbe()
        val outer = ParentDataModifierElement(parentProbe, value = ParentDataValue(1), throwOnRead = true)
        val inner = ParentDataModifierElement(parentProbe, value = ParentDataValue(9))
        val description = treeWithChild(parentProbe, externalProbe, Modifier.Empty.then(outer).then(inner))
        val tree = UiTree()

        tree.update(description)
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()

        assertEquals(listOf(ParentDataValue(9)), parentProbe.consumerMeasureValues)
        assertEquals(listOf(ParentDataValue(9)), parentProbe.consumerLayoutValues)
        assertEquals(0, parentProbe.providers[0].readCount)
        assertEquals(2, parentProbe.providers[1].readCount)
        tree.close()
    }

    @Test
    fun ordinaryModifierBetweenProvidersDoesNotStopTraversal() {
        val parentProbe = ParentDataProbe()
        val externalProbe = ExternalProbe()
        val outer =
            ParentDataModifierElement(
                probe = parentProbe,
                value = ParentDataValue(1),
                queryChild = true,
            )
        val ordinary = ExternalModifierElement(externalProbe)
        val inner = ParentDataModifierElement(parentProbe, value = ParentDataValue(9))
        val description =
            treeWithChild(
                parentProbe,
                externalProbe,
                Modifier.Empty
                    .then(outer)
                    .then(ordinary)
                    .then(inner),
            )
        val tree = UiTree()

        tree.update(description)
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()

        assertEquals(listOf(ParentDataValue(9)), parentProbe.consumerMeasureValues)
        assertEquals(listOf(ParentDataValue(9)), parentProbe.consumerLayoutValues)
        assertEquals(listOf(ParentDataValue(9)), parentProbe.modifierMeasureValues)
        assertEquals(listOf(ParentDataValue(9)), parentProbe.modifierLayoutValues)
        assertEquals(0, parentProbe.providers[0].readCount)
        assertEquals(4, parentProbe.providers[1].readCount)
        tree.close()
    }

    @Test
    fun componentAndGrandchildProvidersAreNotIncludedInDirectChildLookup() {
        val parentProbe = ParentDataProbe()
        val externalProbe = ExternalProbe()
        val grandchild =
            ExternalElement(
                probe = externalProbe,
                key = ElementKey("grandchild"),
                nodeId = ExternalNodeId.Child,
                modifier = Modifier.Empty.then(ParentDataModifierElement(parentProbe, value = ParentDataValue(4))),
            )
        val child =
            ComponentParentDataElement(
                parentDataProbe = parentProbe,
                children = listOf(grandchild),
            )
        val root =
            ParentDataConsumerElement(
                probe = parentProbe,
                key = ElementKey("consumer"),
                children = listOf(child),
                modifier = Modifier.Empty.then(ParentDataModifierElement(parentProbe, value = ParentDataValue(8))),
            )
        val tree = UiTree()

        tree.update(root)
        tree.measure(Constraints.fixed(20, 20))
        tree.layout()

        assertEquals(listOf(null), parentProbe.consumerMeasureValues)
        assertEquals(listOf(null), parentProbe.consumerLayoutValues)
        assertEquals(0, parentProbe.componentProviderReads)
        assertTrue(parentProbe.providers.all { provider -> provider.readCount == 0 })
        tree.close()
    }

    @Test
    fun invalidChildIndexesAreRejectedWithoutPreventingValidQueries() {
        val probe = ParentDataProbe(queryInvalidIndex = true)
        val externalProbe = ExternalProbe()
        val description =
            treeWithChild(
                probe,
                externalProbe,
                Modifier.Empty.then(ParentDataModifierElement(probe, value = ParentDataValue(5))),
            )
        val tree = UiTree()

        tree.update(description)
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()

        val measureFailure = requireNotNull(probe.measureInvalidIndexFailure)
        val layoutFailure = requireNotNull(probe.layoutInvalidIndexFailure)
        assertTrue(measureFailure is IllegalArgumentException)
        assertEquals("Child index is outside the measurement scope.", measureFailure.message)
        assertTrue(layoutFailure is IllegalArgumentException)
        assertEquals("Child index is outside the layout scope.", layoutFailure.message)
        assertEquals(listOf(ParentDataValue(5)), probe.consumerMeasureValues)
        assertEquals(listOf(ParentDataValue(5)), probe.consumerLayoutValues)
        tree.close()
    }

    @Test
    fun capturedScopesRejectLateOwnerAccessAndWrongThreadAccessWithPipelineMessages() {
        val measureProbe = ParentDataProbe(captureScopes = true, blockMeasure = true)
        val measureResult = runBlockedOwnerOperation(measureProbe, blockedPhase = BlockedPhase.Measure)
        assertEquals("The callback scope is no longer active.", measureResult.lateFailure.message)
        assertEquals("This runtime object is owned by parent-data-owner.", measureResult.wrongThreadFailure.message)

        val layoutProbe = ParentDataProbe(captureScopes = true, blockLayout = true)
        val layoutResult = runBlockedOwnerOperation(layoutProbe, blockedPhase = BlockedPhase.Layout)
        assertEquals("The callback scope is no longer active.", layoutResult.lateFailure.message)
        assertEquals("This runtime object is owned by parent-data-owner.", layoutResult.wrongThreadFailure.message)
    }

    @Test
    fun selectedProviderMeasureFailurePoisonsAndCleansExactlyOnce() {
        val probe = ParentDataProbe()
        val externalProbe = ExternalProbe()
        val description = treeWithChild(probe, externalProbe, Modifier.Empty.then(ParentDataModifierElement(probe, throwOnRead = true)))
        val tree = UiTree()
        val failure = probe.providerFailure

        tree.update(description)
        val thrown = assertThrows(IllegalStateException::class.java) { tree.measure(Constraints.fixed(10, 10)) }

        assertSame(failure, thrown)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(1, probe.providers.single().detachCount)
        assertEquals(1, probe.providers.single().disposeCount)
        val detachCount = probe.providers.single().detachCount
        val disposeCount = probe.providers.single().disposeCount
        tree.close()
        assertEquals(detachCount, probe.providers.single().detachCount)
        assertEquals(disposeCount, probe.providers.single().disposeCount)
    }

    @Test
    fun selectedProviderLayoutFailurePoisonsAndCleansExactlyOnce() {
        val probe = ParentDataProbe()
        val externalProbe = ExternalProbe()
        val description = treeWithChild(probe, externalProbe, Modifier.Empty.then(ParentDataModifierElement(probe, throwOnLayoutOnly = true)))
        val tree = UiTree()
        val failure = probe.providerFailure

        tree.update(description)
        tree.measure(Constraints.fixed(10, 10))
        val thrown = assertThrows(IllegalStateException::class.java) { tree.layout() }

        assertSame(failure, thrown)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(1, probe.providers.single().detachCount)
        assertEquals(1, probe.providers.single().disposeCount)
        tree.close()
        assertEquals(1, probe.providers.single().detachCount)
        assertEquals(1, probe.providers.single().disposeCount)
    }

    @Test
    fun maliciousWrongErasedValuePoisonsAndCleansExactlyOnce() {
        val probe = ParentDataProbe()
        val externalProbe = ExternalProbe()
        val description = treeWithChild(probe, externalProbe, Modifier.Empty.then(WrongParentDataModifierElement(probe)))
        val tree = UiTree()

        tree.update(description)
        val thrown = assertThrows(IllegalArgumentException::class.java) { tree.measure(Constraints.fixed(10, 10)) }

        assertEquals("Parent data provider returned the wrong runtime type.", thrown.message)
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(1, probe.wrongProviders.single().detachCount)
        assertEquals(1, probe.wrongProviders.single().disposeCount)
        tree.close()
        assertEquals(1, probe.wrongProviders.single().detachCount)
        assertEquals(1, probe.wrongProviders.single().disposeCount)
    }

    @Test
    fun changedProviderValueOrKeyRemeasuresAndEqualUpdateStaysCached() {
        val probe = ParentDataProbe()
        val externalProbe = ExternalProbe()
        val first = ParentDataModifierElement(probe, value = ParentDataValue(1))
        val description = treeWithChild(probe, externalProbe, Modifier.Empty.then(first))
        val tree = UiTree()

        tree.update(description)
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        tree.paint()
        tree.semantics()
        val initialCounts = Counts(probe)
        val contractNode = ParentDataProviderNode(ParentDataProbe())

        assertEquals(DirtyMask.None, ParentDataModifierElement.TYPE.updateErased(first, first, contractNode))
        val changed = first.copy(value = ParentDataValue(2))
        assertEquals(DirtyMask.of(DirtyPhase.Measure), ParentDataModifierElement.TYPE.updateErased(first, changed, contractNode))
        val changedKey = changed.copy(parentDataKey = ParentDataModifierElement.OTHER_KEY)
        assertEquals(DirtyMask.of(DirtyPhase.Measure), ParentDataModifierElement.TYPE.updateErased(changed, changedKey, contractNode))

        tree.update(treeWithChild(probe, externalProbe, Modifier.Empty.then(changed)))
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        assertEquals(listOf(ParentDataValue(2)), probe.consumerMeasureValues.takeLast(1))
        assertEquals(listOf(ParentDataValue(2)), probe.consumerLayoutValues.takeLast(1))
        assertTrue(initialCounts.measure < probe.consumerMeasureCalls)
        tree.paint()
        tree.semantics()
        assertTrue(initialCounts.paint < probe.consumerPaintCalls)
        assertTrue(initialCounts.semantics < probe.consumerSemanticsCalls)

        tree.update(treeWithChild(probe, externalProbe, Modifier.Empty.then(changedKey)))
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        assertEquals(listOf(null), probe.consumerMeasureValues.takeLast(1))
        assertEquals(listOf(null), probe.consumerLayoutValues.takeLast(1))
        tree.paint()
        tree.semantics()

        val beforeEqual = Counts(probe)
        tree.update(treeWithChild(probe, externalProbe, Modifier.Empty.then(changedKey)))
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        tree.paint()
        tree.semantics()
        assertEquals(beforeEqual, Counts(probe))
        tree.close()
    }

    private fun treeWithChild(
        probe: ParentDataProbe,
        externalProbe: ExternalProbe,
        modifier: Modifier,
        parentDataKey: ParentDataKey<ParentDataValue> = ParentDataModifierElement.KEY,
    ): ParentDataConsumerElement {
        val child =
            ExternalElement(
                probe = externalProbe,
                key = ElementKey("child"),
                nodeId = ExternalNodeId.Child,
                modifier = modifier,
            )
        val description =
            ParentDataConsumerElement(
                probe = probe,
                parentDataKey = parentDataKey,
                key = ElementKey("consumer"),
                children = listOf(child),
            )
        return description
    }

    private fun runBlockedOwnerOperation(
        probe: ParentDataProbe,
        blockedPhase: BlockedPhase,
    ): ScopeResult {
        val lateFailure = AtomicReference<Throwable>()
        val ownerFailure = AtomicReference<Throwable>()
        val ownerReady = CountDownLatch(1)
        val owner = ownerOperation(probe, blockedPhase, ownerReady, ownerFailure, lateFailure)
        owner.start()
        val wrongThreadFailure = AtomicReference<Throwable>()
        try {
            assertTrue(ownerReady.await(5, TimeUnit.SECONDS))
            assertTrue(enteredLatch(probe, blockedPhase).await(5, TimeUnit.SECONDS))
            val scope = capturedScope(probe, blockedPhase)
            val other =
                Thread {
                    wrongThreadFailure.set(parentDataFailure(scope))
                }
            other.start()
            other.join(5_000)
            assertTrue(other.isAlive.not())
        } finally {
            releaseBlockedPhase(probe, blockedPhase)
            owner.join(5_000)
        }
        assertTrue(owner.isAlive.not())
        assertEquals(null, ownerFailure.get())
        return ScopeResult(requireNotNull(lateFailure.get()), requireNotNull(wrongThreadFailure.get()))
    }

    private fun ownerOperation(
        probe: ParentDataProbe,
        blockedPhase: BlockedPhase,
        ownerReady: CountDownLatch,
        ownerFailure: AtomicReference<Throwable>,
        lateFailure: AtomicReference<Throwable>,
    ): Thread =
        Thread(
            {
                val tree = UiTree()
                val description =
                    treeWithChild(
                        probe,
                        ExternalProbe(),
                        Modifier.Empty.then(ParentDataModifierElement(probe)),
                    )
                tree.update(description)
                ownerReady.countDown()
                try {
                    tree.measure(Constraints.fixed(10, 10))
                    if (blockedPhase === BlockedPhase.Layout) {
                        tree.layout()
                    }
                } catch (failure: Throwable) {
                    ownerFailure.set(failure)
                }
                lateFailure.set(parentDataFailure(capturedScope(probe, blockedPhase)))
                tree.close()
            },
            "parent-data-owner",
        )

    private fun capturedScope(
        probe: ParentDataProbe,
        blockedPhase: BlockedPhase,
    ): ParentDataScope =
        if (blockedPhase === BlockedPhase.Measure) {
            requireNotNull(probe.measureScope)
        } else {
            requireNotNull(probe.layoutScope)
        }

    private fun parentDataFailure(scope: ParentDataScope): Throwable? =
        runCatching {
            scope.childParentData(0, ParentDataModifierElement.KEY)
        }.exceptionOrNull()

    private fun enteredLatch(
        probe: ParentDataProbe,
        blockedPhase: BlockedPhase,
    ): CountDownLatch =
        if (blockedPhase === BlockedPhase.Measure) {
            probe.measureEntered
        } else {
            probe.layoutEntered
        }

    private fun releaseBlockedPhase(
        probe: ParentDataProbe,
        blockedPhase: BlockedPhase,
    ) {
        if (blockedPhase === BlockedPhase.Measure) {
            probe.measureRelease.countDown()
        } else {
            probe.layoutRelease.countDown()
        }
    }

    private data class Counts(
        val measure: Int,
        val layout: Int,
        val paint: Int,
        val semantics: Int,
    ) {
        constructor(probe: ParentDataProbe) :
            this(
                probe.consumerMeasureCalls,
                probe.consumerLayoutCalls,
                probe.consumerPaintCalls,
                probe.consumerSemanticsCalls,
            )
    }

    private data class ScopeResult(
        val lateFailure: Throwable,
        val wrongThreadFailure: Throwable,
    )

    private enum class BlockedPhase {
        Measure,
        Layout,
    }
}
