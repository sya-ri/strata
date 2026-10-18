package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Stack
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.ui.UiDefinition

/**
 * Builds two independent views of the first native slot followed by a view of the second slot.
 * Fixed layout makes native pointer dispatch independent of item contents and font metrics.
 */
internal fun createInventoryMirrorDefinition(): UiDefinition =
    UiDefinition("Mirrored inventory slots") {
        Stack(modifier = Modifier.size(320, 180)) {
            Row(modifier = Modifier.size(54, 18)) {
                val size = Modifier.size(18, 18)
                Slot(bind = Slots.container(0), modifier = size)
                Slot(bind = Slots.container(0), modifier = size)
                Slot(bind = Slots.container(1), modifier = size)
            }
        }
    }
