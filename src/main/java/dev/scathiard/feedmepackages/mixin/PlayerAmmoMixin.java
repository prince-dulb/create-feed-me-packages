package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.consumption.AmmoService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerAmmoMixin {
    @WrapOperation(method = "getProjectile", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/CommonHooks;getProjectile(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack fmp$candidate(LivingEntity shooter, ItemStack weapon, ItemStack vanilla, Operation<ItemStack> original) {
        var offered = AmmoService.candidate((Player) shooter, weapon, vanilla);
        return AmmoService.afterEvent(offered, original.call(shooter, weapon, offered));
    }
}
