package dev.scathiard.feedmepackages.mixin;

import dev.scathiard.feedmepackages.client.LogisticsPanel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only classifies operations; vanilla still performs the actual click and sends its native packets. */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeCursorClickMixin {
    @Shadow private Slot destroyItemSlot;
    @Shadow protected abstract boolean isCreativeSlot(Slot slot);
    @Unique private LogisticsPanel.CreativeClick fmp$click;

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
    private void fmp$before(Slot slot, int slotId, int button, ClickType type, CallbackInfo ci) {
        fmp$click = LogisticsPanel.beforeCreativeClick((Screen) (Object) this,
                isCreativeSlot(slot), slot != null && slot == destroyItemSlot, slot, button, type);
        if (fmp$click != null && fmp$click.sequence() < 0) { fmp$click = null; ci.cancel(); }
    }

    @Inject(method = "slotClicked", at = @At("RETURN"))
    private void fmp$after(Slot slot, int slotId, int button, ClickType type, CallbackInfo ci) {
        var click = fmp$click; fmp$click = null;
        LogisticsPanel.afterCreativeClick(click);
    }
}
