package dev.scathiard.feedmepackages.mixin;

import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw bounded vanilla grid storage, allowing a single notification after the complete transfer. */
@Mixin(TransientCraftingContainer.class)
public interface CraftingItemsAccess {
    @Accessor("items") NonNullList<ItemStack> fmp$items();
}
