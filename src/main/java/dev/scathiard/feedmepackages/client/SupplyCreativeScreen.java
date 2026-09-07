package dev.scathiard.feedmepackages.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;

/** Creative tabs and vanilla cursor semantics remain in the original screen superclass. */
public final class SupplyCreativeScreen extends CreativeModeInventoryScreen {
    public SupplyCreativeScreen(LocalPlayer player) { super(player, player.connection.enabledFeatures(), Minecraft.getInstance().options.operatorItemsTab().get()); }
    @Override protected void init() { super.init(); LogisticsPanel.mount(this); }
    @Override public void removed() { LogisticsPanel.unmount(this); super.removed(); }
}
