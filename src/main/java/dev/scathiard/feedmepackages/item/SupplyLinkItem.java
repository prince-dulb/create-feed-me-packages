package dev.scathiard.feedmepackages.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;

public final class SupplyLinkItem extends Item {
    private final int targetLevel;
    public SupplyLinkItem(Properties properties, int targetLevel) {
        super(properties);
        if (targetLevel != 0 && (targetLevel < 2 || targetLevel > 5)) throw new IllegalArgumentException("Invalid link target");
        this.targetLevel = targetLevel;
    }
    public int targetLevel() { return targetLevel; }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flags) {
        tooltip.add(Component.translatable(targetLevel == 0 ? "tooltip.create_feed_me_packages.private_link" : "tooltip.create_feed_me_packages.upgrade_link", targetLevel));
        tooltip.add(Component.translatable("tooltip.create_feed_me_packages.smithing"));
    }
}
