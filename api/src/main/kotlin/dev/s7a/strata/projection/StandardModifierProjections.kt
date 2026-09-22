package dev.s7a.strata.projection

import dev.s7a.strata.component.TiledImageContentParentData
import dev.s7a.strata.layout.ColumnAlignmentParentData
import dev.s7a.strata.layout.RowAlignmentParentData
import dev.s7a.strata.layout.StackAlignmentParentData
import dev.s7a.strata.layout.WeightParentData
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ScaleToFitModifier
import dev.s7a.strata.modifier.SizeModifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Registration boundary for portable standard modifier implementations owned by the API module.
 * Decoders validate complete property records and construct ordinary active modifiers; no retained node is shared.
 * Parent data remains private to the standard consuming layout and is never exposed as a public settings object.
 */
@InternalStrataRuntimeApi
public object StandardModifierProjections {
    /**
     * Supplies standard type/factory pairs to a runtime's extensible declaration registry.
     */
    public fun register(register: (ProjectionType, (ProjectionValue) -> ModifierElement) -> Unit) {
        register(BuiltinProjection.Size.type, SizeModifier::decode)
        register(BuiltinProjection.ScaleToFit.type, ScaleToFitModifier::decode)
        register(BuiltinProjection.Weight.type, WeightParentData::decode)
        register(BuiltinProjection.RowAlignment.type, RowAlignmentParentData::decode)
        register(BuiltinProjection.ColumnAlignment.type, ColumnAlignmentParentData::decode)
        register(BuiltinProjection.StackAlignment.type, StackAlignmentParentData::decode)
        register(BuiltinProjection.TiledImagePosition.type, TiledImageContentParentData::decode)
    }
}
