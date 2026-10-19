package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.item.enchantment.Enchantments

/**
 * Creates fresh authoritative stacks with this target's native naming, bundle and enchantment APIs.
 */
internal object MinecraftSlotTooltipTestStack {
    /**
     * Borrows the server registry for this call; the returned stack belongs to its server-thread inventory operation.
     */
    fun create(
        registries: RegistryAccess,
        fixture: MinecraftSlotTooltipFixture,
    ): ItemStack =
        when (fixture) {
            MinecraftSlotTooltipFixture.OriginalSword, MinecraftSlotTooltipFixture.CarriedSword -> sword(registries, "Strata tooltip sword", 37)
            MinecraftSlotTooltipFixture.UpdatedSword -> sword(registries, "Updated tooltip sword", 79)
            MinecraftSlotTooltipFixture.Bundle, MinecraftSlotTooltipFixture.CarriedBundle -> bundle()
            MinecraftSlotTooltipFixture.Empty -> ItemStack.EMPTY
        }

    private fun bundle(): ItemStack {
        val stack = ItemStack(Items.BUNDLE)
        stack.set(DataComponents.BUNDLE_CONTENTS, BundleContents(listOf(ItemStack(Items.DIRT, 2))))
        return stack
    }

    private fun sword(
        registries: RegistryAccess,
        name: String,
        damage: Int,
    ): ItemStack {
        val stack = ItemStack(Items.DIAMOND_SWORD)
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name))
        stack.enchant(registries.registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.UNBREAKING), 2)
        stack.damageValue = damage
        return stack
    }
}
