package dev.scathiard.feedmepackages.item;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import java.nio.charset.StandardCharsets;

/** Full persistent identity, not a digest. No mutable ItemStack escapes this value. */
public final class ItemVariantKey implements dev.scathiard.feedmepackages.domain.MaterialVariant {
    public static final int MAX_BYTES = 1024;
    private final String canonical;
    private final CompoundTag template;
    private final int stackSize;

    private ItemVariantKey(CompoundTag template, int stackSize) {
        this.template = template.copy();
        this.stackSize = stackSize;
        canonical = new StringTagVisitor().visit(this.template);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Item template exceeds 1024 bytes");
    }

    public static ItemVariantKey of(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty() || stack.getItem() instanceof PendantItem || PackageItem.isPackage(stack)
                || !stack.getItem().canFitInsideContainerItems())
            throw new IllegalArgumentException("Item cannot be stored in a logistics cache");
        ItemStack one = stack.copyWithCount(1);
        var key = new ItemVariantKey((CompoundTag) one.save(registries), one.getMaxStackSize());
        ItemStack restored = key.stack(registries, 1);
        if (!ItemStack.isSameItemSameComponents(one, restored))
            throw new IllegalArgumentException("Item components cannot round-trip without alteration");
        return key;
    }

    public static ItemVariantKey decode(String encoded, HolderLookup.Provider registries) {
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Item template exceeds 1024 bytes");
        try {
            CompoundTag tag = TagParser.parseTag(encoded);
            if (!tag.contains("count", 3) || tag.getInt("count") != 1)
                throw new IllegalArgumentException("Item template must contain exactly one item");
            ItemStack item = ItemStack.parse(registries, tag).orElseThrow(() -> new IllegalArgumentException("Unknown item template"));
            ItemVariantKey verified = of(item, registries);
            if (!verified.template.equals(tag))
                throw new IllegalArgumentException("Item template has unknown or noncanonical data");
            return verified;
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("Malformed item template", e);
        }
    }

    @Override public int stackSize() { return stackSize; }

    public ItemStack stack(HolderLookup.Provider registries, int count) {
        if (count < 1) throw new IllegalArgumentException("Materialized stack must be positive");
        ItemStack item = ItemStack.parse(registries, template.copy())
                .orElseThrow(() -> new IllegalArgumentException("Item template no longer decodes"));
        if (count > item.getMaxStackSize()) throw new IllegalArgumentException("Materialized stack exceeds native limit");
        item.setCount(count);
        return item;
    }

    public String encoded() { return canonical; }
    @Override public boolean equals(Object other) { return other instanceof ItemVariantKey key && canonical.equals(key.canonical); }
    @Override public int hashCode() { return canonical.hashCode(); }
    @Override public String toString() { return template.getString("id"); }
}
