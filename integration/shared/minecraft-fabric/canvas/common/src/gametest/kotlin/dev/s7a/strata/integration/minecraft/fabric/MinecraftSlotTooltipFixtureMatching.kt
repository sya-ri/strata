package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.world.item.ItemStack

/**
 * Recognizes one atomic server fixture update through client-owned scalar observations.
 *
 * Dynamic enchantment registry holders are independently owned by the server and client; cross-registry ItemStack component equality is not a delivery signal.
 * The complete client-owned stack still supplies every native/reference tooltip, and the populated sword requires its real enchantment data.
 */
internal fun matchesMinecraftTooltipFixture(
    actual: ItemStack,
    expected: ItemStack,
): Boolean {
    if (expected.isEmpty) return actual.isEmpty
    return actual.`is`(expected.item) &&
        actual.count == expected.count &&
        actual.damageValue == expected.damageValue &&
        actual.hoverName.string == expected.hoverName.string &&
        actual.isEnchanted == expected.isEnchanted
}
