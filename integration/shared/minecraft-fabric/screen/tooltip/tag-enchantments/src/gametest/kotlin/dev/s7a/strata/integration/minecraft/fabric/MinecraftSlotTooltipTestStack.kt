package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments

/**
 * Creates fresh authoritative stacks with this target's native naming, bundle and enchantment APIs.
 */
internal object MinecraftSlotTooltipTestStack {
    /**
     * Borrows the server registry for this call; the returned stack belongs to its server-thread inventory operation.
     */
    @Suppress("UnusedParameter")
    fun create(
        registries: RegistryAccess,
        fixture: MinecraftSlotTooltipFixture,
    ): ItemStack =
        when (fixture) {
            MinecraftSlotTooltipFixture.OriginalSword, MinecraftSlotTooltipFixture.CarriedSword -> sword("Strata tooltip sword", 37)
            MinecraftSlotTooltipFixture.UpdatedSword -> sword("Updated tooltip sword", 79)
            MinecraftSlotTooltipFixture.Bundle, MinecraftSlotTooltipFixture.CarriedBundle -> bundle()
            MinecraftSlotTooltipFixture.Empty -> ItemStack.EMPTY
        }

    private fun bundle(): ItemStack {
        val stack = ItemStack(Items.BUNDLE)
        val contents = ListTag()
        contents.add(ItemStack(Items.DIRT, 2).save(CompoundTag()))
        stack.orCreateTag.put("Items", contents)
        return stack
    }

    private fun sword(
        name: String,
        damage: Int,
    ): ItemStack {
        val stack = ItemStack(Items.DIAMOND_SWORD)
        stack.hoverName = Component.literal(name)
        stack.enchant(Enchantments.UNBREAKING, 2)
        stack.damageValue = damage
        return stack
    }
}
