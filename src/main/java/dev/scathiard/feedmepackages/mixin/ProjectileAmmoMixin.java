package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.consumption.AmmoService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Mixin(ProjectileWeaponItem.class)
public abstract class ProjectileAmmoMixin {
    @Unique private static final ThreadLocal<Integer> fmp$drawDepth = ThreadLocal.withInitial(() -> 0);
    @WrapMethod(method = "draw")
    private static List<ItemStack> fmp$draw(ItemStack weapon, ItemStack ammo, LivingEntity shooter, Operation<List<ItemStack>> original) {
        int depth = fmp$drawDepth.get(); fmp$drawDepth.set(depth + 1);
        try { AmmoService.validate(weapon, ammo, shooter); return original.call(weapon, ammo, shooter); }
        catch (AmmoService.Unavailable stale) { return List.of(); }
        finally { if (depth == 0) fmp$drawDepth.remove(); else fmp$drawDepth.set(depth); }
    }
    @WrapMethod(method = "useAmmo")
    private static ItemStack fmp$consume(ItemStack weapon, ItemStack ammo, LivingEntity shooter, boolean intangible, Operation<ItemStack> original) {
        try {
            AmmoService.validate(weapon, ammo, shooter);
            var result = original.call(weapon, ammo, shooter, intangible);
            AmmoService.used(weapon, ammo, shooter, result); return result;
        } catch (AmmoService.Unavailable stale) {
            if (fmp$drawDepth.get() > 0) throw stale;
            return ItemStack.EMPTY;
        }
    }
    @WrapOperation(method = "useAmmo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;split(I)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack fmp$split(ItemStack ammo, int count, Operation<ItemStack> original) {
        return AmmoService.isQuery(ammo) ? AmmoService.split(ammo, count) : original.call(ammo, count);
    }
}
