package dev.scathiard.feedmepackages.client;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;

/** FMP inventory companion owns its panel lifecycle and keeps the vanilla crafting/inventory menu. */
public final class SupplyInventoryScreen extends InventoryScreen {
    public SupplyInventoryScreen(Player player) { super(player); }
    @Override protected void init() { super.init(); LogisticsPanel.mount(this); }
    @Override public void removed() { LogisticsPanel.unmount(this); super.removed(); }
}
