package dev.s7a.strata.quality.fixture

import kotlin.test.Test

/**
 * Runs the exact same independent controls and inventory on JVM, Node.js and browser JavaScript.
 */
internal class EmptyChildControlsTest {
    @Test
    fun freshEquivalentLeaf() {
        EmptyChildControls.verify(EmptyChildControls.Control.FreshEquivalentLeaf)
    }

    @Test
    fun changedLeafPayload() {
        EmptyChildControls.verify(EmptyChildControls.Control.ChangedLeafPayload)
    }

    @Test
    fun sameDescription() {
        EmptyChildControls.verify(EmptyChildControls.Control.SameDescription)
    }

    @Test
    fun coldCreation() {
        EmptyChildControls.verify(EmptyChildControls.Control.ColdCreation)
    }

    @Test
    fun emptyToOne() {
        EmptyChildControls.verify(EmptyChildControls.Control.EmptyToOne)
    }

    @Test
    fun oneToEmpty() {
        EmptyChildControls.verify(EmptyChildControls.Control.OneToEmpty)
    }

    @Test
    fun nonemptyToNonempty() {
        EmptyChildControls.verify(EmptyChildControls.Control.NonemptyToNonempty)
    }

    @Test
    fun keyedInsertion() {
        EmptyChildControls.verify(EmptyChildControls.Control.KeyedInsertion)
    }

    @Test
    fun keyedRemoval() {
        EmptyChildControls.verify(EmptyChildControls.Control.KeyedRemoval)
    }

    @Test
    fun keyedReorder() {
        EmptyChildControls.verify(EmptyChildControls.Control.KeyedReorder)
    }

    @Test
    fun positionalReplacement() {
        EmptyChildControls.verify(EmptyChildControls.Control.PositionalReplacement)
    }

    @Test
    fun keyedTypeReplacement() {
        EmptyChildControls.verify(EmptyChildControls.Control.KeyedTypeReplacement)
    }

    @Test
    fun invalidDuplicateKeys() {
        EmptyChildControls.verify(EmptyChildControls.Control.InvalidDuplicateKeys)
    }

    @Test
    fun localValidationFailure() {
        EmptyChildControls.verify(EmptyChildControls.Control.LocalValidationFailure)
    }

    @Test
    fun componentUpdateFailure() {
        EmptyChildControls.verify(EmptyChildControls.Control.ComponentUpdateFailure)
    }

    @Test
    fun modifierUpdateFailure() {
        EmptyChildControls.verify(EmptyChildControls.Control.ModifierUpdateFailure)
    }

    @Test
    fun createFailure() {
        EmptyChildControls.verify(EmptyChildControls.Control.CreateFailure)
    }

    @Test
    fun attachFailure() {
        EmptyChildControls.verify(EmptyChildControls.Control.AttachFailure)
    }

    @Test
    fun detachFailure() {
        EmptyChildControls.verify(EmptyChildControls.Control.DetachFailure)
    }

    @Test
    fun disposeFailure() {
        EmptyChildControls.verify(EmptyChildControls.Control.DisposeFailure)
    }

    @Test
    fun modifierIdentity() {
        EmptyChildControls.verify(EmptyChildControls.Control.ModifierIdentity)
    }

    @Test
    fun effectiveAncestry() {
        EmptyChildControls.verify(EmptyChildControls.Control.EffectiveAncestry)
    }

    @Test
    fun dynamicEmptyOutput() {
        EmptyChildControls.verify(EmptyChildControls.Control.DynamicEmptyOutput)
    }

    @Test
    fun deferredEmptyOutput() {
        EmptyChildControls.verify(EmptyChildControls.Control.DeferredEmptyOutput)
    }

    @Test
    fun dynamicChangedOutput() {
        EmptyChildControls.verify(EmptyChildControls.Control.DynamicChangedOutput)
    }

    @Test
    fun localizedUpdates() {
        EmptyChildControls.verify(EmptyChildControls.Control.LocalizedUpdates)
    }

    @Test
    fun frameIdentity() {
        EmptyChildControls.verify(EmptyChildControls.Control.FrameIdentity)
    }

    @Test
    fun pixelsAndGeometry() {
        EmptyChildControls.verify(EmptyChildControls.Control.PixelsAndGeometry)
    }

    @Test
    fun inputAndSemantics() {
        EmptyChildControls.verify(EmptyChildControls.Control.InputAndSemantics)
    }

    @Test
    fun monitoringParity() {
        EmptyChildControls.verify(EmptyChildControls.Control.MonitoringParity)
    }

    @Test
    fun ownerIsolation() {
        EmptyChildControls.verify(EmptyChildControls.Control.OwnerIsolation)
    }

    @Test
    fun terminalRetention() {
        EmptyChildControls.verify(EmptyChildControls.Control.TerminalRetention)
    }

    @Test
    fun allSixtyWorkloadCases() {
        EmptyChildControls.verifyInventory()
    }
}
