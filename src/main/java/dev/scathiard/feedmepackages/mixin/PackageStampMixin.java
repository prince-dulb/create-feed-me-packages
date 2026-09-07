package dev.scathiard.feedmepackages.mixin;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.scathiard.feedmepackages.logistics.DispatchScope;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PackageItem.class, remap = false)
public abstract class PackageStampMixin {
    @Inject(method = "setOrder", at = @At("TAIL"))
    private static void fmp$seal(ItemStack box, int orderId, int linkIndex, boolean finalLink,
                                 int packageIndex, boolean finalPackage, PackageOrderWithCrafts context, CallbackInfo callback) {
        DispatchScope.onCreated(box);
    }
}
