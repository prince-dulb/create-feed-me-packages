package dev.scathiard.feedmepackages.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.RecipeBookMenu;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookHintsMixin {
    @Shadow protected RecipeBookMenu<?, ?> menu;
    @Shadow public abstract boolean isVisible();
    @Shadow private void updateStackedContents() { throw new AssertionError(); }
    @Unique private long fmp$materialsVersion = -1;
    @WrapOperation(method = {"initVisuals", "updateStackedContents"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;fillStackedContents(Lnet/minecraft/world/entity/player/StackedContents;)V"))
    private void fmp$hints(Inventory inventory, StackedContents contents, Operation<Void> original) {
        original.call(inventory, contents);
        if (ClientMaterials.active() && menu != null && CraftingService.supported(menu))
            for (var stack : ClientMaterials.stacks()) contents.accountStack(stack, stack.getCount());
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void fmp$refresh(CallbackInfo ci) {
        long version = ClientMaterials.version();
        if (menu != null && isVisible() && fmp$materialsVersion != version) { fmp$materialsVersion = version; updateStackedContents(); }
    }
}
