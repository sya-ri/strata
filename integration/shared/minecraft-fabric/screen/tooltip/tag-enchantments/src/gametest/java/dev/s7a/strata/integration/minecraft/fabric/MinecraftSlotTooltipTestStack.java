package dev.s7a.strata.integration.minecraft.fabric;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/** Creates real server-owned item data for the target's verified naming and enchantment API. */
final class MinecraftSlotTooltipTestStack {
    private MinecraftSlotTooltipTestStack() {}

    /**
     * Creates one fresh authoritative fixture stack, without retaining the caller's server registry.
     *
     * @param registries current server-owned registry lookup.
     * @param fixture typed inventory input requested by the loaded acceptance scene.
     * @return the new stack owned by the caller's server-thread inventory operation.
     */
    static ItemStack create(RegistryAccess registries, MinecraftSlotTooltipFixture fixture) {
        return switch (fixture) {
            case OriginalSword, CarriedSword -> sword(registries, "Strata tooltip sword", 37);
            case UpdatedSword -> sword(registries, "Updated tooltip sword", 79);
            case Bundle, CarriedBundle -> bundle();
            case Empty -> ItemStack.EMPTY;
        };
    }

    private static ItemStack bundle() {
        ItemStack stack = new ItemStack(Items.BUNDLE);
        ListTag contents = new ListTag();
        contents.add(new ItemStack(Items.DIRT, 2).save(new CompoundTag()));
        stack.getOrCreateTag().put("Items", contents);
        return stack;
    }

    private static ItemStack sword(RegistryAccess registries, String name, int damage) {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.setHoverName(Component.literal(name));
        stack.enchant(Enchantments.UNBREAKING, 2);
        stack.setDamageValue(damage);
        return stack;
    }
}
