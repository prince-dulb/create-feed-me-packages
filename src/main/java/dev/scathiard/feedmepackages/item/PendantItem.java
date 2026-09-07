package dev.scathiard.feedmepackages.item;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import java.util.List;

public final class PendantItem extends Item {
    private final boolean personal;
    public PendantItem(Properties properties, boolean personal) { super(properties); this.personal = personal; }
    public boolean personal() { return personal; }
    @Override public boolean isFoil(ItemStack stack) { return stack.has(FmpRegistries.NETWORK.get()) || super.isFoil(stack); }

    @Override public net.minecraft.world.InteractionResultHolder<ItemStack> use(net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!stack.has(FmpRegistries.NETWORK.get()) || getPlayerPOVHitResult(level, player,
                net.minecraft.world.level.ClipContext.Fluid.NONE).getType() != net.minecraft.world.phys.HitResult.Type.MISS)
            return net.minecraft.world.InteractionResultHolder.pass(stack);
        if (!level.isClientSide()) {
            stack.remove(FmpRegistries.NETWORK.get());
            player.displayClientMessage(Component.translatable("create.logistically_linked.cleared"), true);
        }
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) return InteractionResult.PASS;
        var link = BlockEntityBehaviour.get(context.getLevel(), context.getClickedPos(), LogisticallyLinkedBehaviour.TYPE);
        if (link == null) return InteractionResult.PASS;
        if (!context.getLevel().isClientSide()) {
            if (!link.mayInteractMessage(player)) return InteractionResult.FAIL;
            context.getItemInHand().set(FmpRegistries.NETWORK.get(), link.freqId);
            player.displayClientMessage(Component.translatable("message.create_feed_me_packages.bound"), true);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(personal ? "tooltip.create_feed_me_packages.personal" : "tooltip.create_feed_me_packages.ordinary"));
        var ownerName = stack.get(FmpRegistries.OWNER_NAME.get());
        if (personal && ownerName != null) tooltip.add(Component.translatable("tooltip.create_feed_me_packages.owner", ownerName));
        tooltip.add(Component.translatable("tooltip.create_feed_me_packages.bind"));
        if (stack.has(FmpRegistries.NETWORK.get())) tooltip.add(Component.translatable("tooltip.create_feed_me_packages.clear"));
        tooltip.add(Component.translatable(stack.has(FmpRegistries.NETWORK.get()) ? "message.create_feed_me_packages.bound" : "message.create_feed_me_packages.unbound"));
        if (stack.getOrDefault(FmpRegistries.PREVIEW.get(), false)) tooltip.add(Component.translatable("tooltip.create_feed_me_packages.preview"));
    }
}
