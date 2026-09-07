package dev.scathiard.feedmepackages.growth;

import dev.scathiard.feedmepackages.domain.CacheMerge;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.item.*;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.*;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/** Explicit manufacturing exception; it never opens ordinary unworn cache operations. */
public final class GrowthService {
    private GrowthService() {}
    private static final Map<SmithingMenu, Preview> PREVIEWS = new WeakHashMap<>();
    private record Proof(CacheRecord source, UUID personalId, CacheRecord personal) {}
    private record Preview(List<ItemStack> inputs, Proof proof, ResourceLocation recipe) {}
    private record Prepared(CacheLedger.Mutation mutation, ItemStack result) {}
    private static final class Rejected extends IllegalArgumentException {
        final String code;
        Rejected(String code) { super(code); this.code = code; }
    }

    private static Player player(SmithingMenu menu) { return ((Inventory) menu.getSlot(4).container).player; }
    private static SmithingRecipeInput input(SmithingMenu menu) {
        return new SmithingRecipeInput(menu.getSlot(0).getItem(), menu.getSlot(1).getItem(), menu.getSlot(2).getItem());
    }
    private static RecipeHolder<PendantSmithingRecipe> recipe(SmithingMenu menu) {
        var player = player(menu); var input = input(menu);
        for (var holder : player.level().getRecipeManager().getRecipesFor(RecipeType.SMITHING, input, player.level()))
            if (holder.value() instanceof PendantSmithingRecipe value) return new RecipeHolder<>(holder.id(), value);
        return null;
    }
    public static boolean applies(AbstractContainerMenu menu) {
        return menu.getClass() == SmithingMenu.class && menu.slots.size() == 40 &&
                (menu.getSlot(3).getItem().getOrDefault(FmpRegistries.PREVIEW.get(), false) || recipe((SmithingMenu) menu) != null);
    }
    private static boolean usable(ServerPlayer player, SmithingMenu menu) {
        return player.getServer().isSameThread() && player.isAlive() && !player.isSpectator()
                && player.containerMenu == menu && menu.stillValid(player);
    }
    private static List<ItemStack> inputs(SmithingMenu menu) {
        return List.of(menu.getSlot(0).getItem().copy(), menu.getSlot(1).getItem().copy(), menu.getSlot(2).getItem().copy());
    }
    private static boolean sameInputs(SmithingMenu menu, List<ItemStack> before) {
        for (int index = 0; index < 3; index++) if (!ItemStack.matches(before.get(index), menu.getSlot(index).getItem())) return false;
        return true;
    }
    private static Proof proof(ServerPlayer player, ItemStack base) {
        var ledger = CacheLedger.get(player.getServer()); UUID source = base.get(FmpRegistries.IDENTITY.get()); UUID personal = ledger.personal(player.getUUID());
        return new Proof(source == null ? null : ledger.find(source), personal, personal == null ? null : ledger.find(personal));
    }

    private static Prepared prepare(ServerPlayer player, PendantSmithingRecipe recipe, SmithingRecipeInput input) {
        var ledger = CacheLedger.get(player.getServer()); ItemStack base = input.base();
        if (!ledger.problem().isEmpty()) throw new Rejected("storage_locked");
        if (dev.scathiard.feedmepackages.consumption.CraftingReservations.operating(base.get(FmpRegistries.IDENTITY.get()))
                || dev.scathiard.feedmepackages.consumption.CraftingReservations.operating(ledger.personal(player.getUUID()))) throw new Rejected("stale");
        if (!(base.getItem() instanceof PendantItem pendant) || base.getCount() != 1 || base.getOrDefault(FmpRegistries.PREVIEW.get(), false)
                || !(input.addition().getItem() instanceof SupplyLinkItem link) || link.targetLevel() != recipe.targetLevel()
                || !input.template().is(FmpRegistries.ASSEMBLY_TEMPLATE.get())) throw new Rejected("invalid_input");
        ItemStack result = recipe.display(input); result.remove(FmpRegistries.PREVIEW.get());
        CacheLedger.Mutation mutation;
        try {
            if (recipe.targetLevel() == 0) {
                if (pendant.personal()) throw new Rejected("invalid_input");
                var source = ledger.ordinaryForManufacturing(base.get(FmpRegistries.IDENTITY.get()));
                var plan = PrivateCacheMerge.simulate(ledger, player.registryAccess(), player.getUUID(), source);
                mutation = ledger.prepareOwnership(plan.source(), plan.target(), plan.replacement(), plan.sourceRoutes());
                result.remove(FmpRegistries.IDENTITY.get()); result.set(FmpRegistries.OWNER.get(), player.getUUID());
                result.set(FmpRegistries.OWNER_NAME.get(), player.getGameProfile().getName());
            } else if (!pendant.personal()) {
                var source = ledger.ordinaryForManufacturing(base.get(FmpRegistries.IDENTITY.get()));
                if (source.state().level() + 1 != recipe.targetLevel()) throw new Rejected("wrong_level");
                mutation = ledger.prepareOrdinaryUpgrade(source, recipe.targetLevel());
                result.set(FmpRegistries.IDENTITY.get(), mutation.result().state().id()); result.remove(FmpRegistries.OWNER.get());
            } else {
                // Owner-locked personal upgrade: only the owner may forge it, and the personal cache upgrades directly.
                var owner = base.get(FmpRegistries.OWNER.get());
                if (owner == null || !owner.equals(player.getUUID())) throw new Rejected("not_owner");
                UUID personal = ledger.personal(player.getUUID());
                if (personal == null || ledger.find(personal).state().level() + 1 != recipe.targetLevel()) throw new Rejected("wrong_level");
                mutation = ledger.preparePersonalUpgrade(new CacheHandle(personal, player.getUUID(), null), recipe.targetLevel());
                result.remove(FmpRegistries.IDENTITY.get()); result.set(FmpRegistries.OWNER.get(), owner);
            }
        } catch (CacheMerge.Rejected failure) {
            throw new Rejected(failure.reason() == CacheMerge.Reason.CONFIGURATION_CONFLICT ? "configuration_conflict" : "merge_full");
        } catch (PrivateCacheMerge.Rejected failure) {
            throw new Rejected(failure.reason() == PrivateCacheMerge.Reason.INVALID_RESIDUAL ? "invalid_residual" : "merge_full");
        }
        if (!(result.getItem() instanceof PendantItem output) || output.personal() != (pendant.personal() || recipe.targetLevel() == 0))
            throw new Rejected("invalid_input");
        return new Prepared(mutation, result);
    }

    /** Replaces only FMP recipe previews. Client receives the authoritative result through native menu sync. */
    public static void refresh(SmithingMenu menu) {
        if (!(player(menu) instanceof ServerPlayer player)) return;
        PREVIEWS.remove(menu); ItemStack result = ItemStack.EMPTY;
        try {
            var recipe = recipe(menu);
            if (!usable(player, menu) || recipe == null) throw new Rejected("stale");
            var inputs = inputs(menu); var proof = proof(player, inputs.get(1));
            var plan = prepare(player, recipe.value(), input(menu));
            if (!sameInputs(menu, inputs) || !proof.equals(proof(player, inputs.get(1)))) throw new Rejected("stale");
            result = plan.result().copy(); result.set(FmpRegistries.PREVIEW.get(), true);
            PREVIEWS.put(menu, new Preview(inputs, proof, recipe.id()));
        } catch (Rejected failure) { message(player, failure.code); }
        catch (IllegalStateException invalidState) { message(player, "invalid_state"); }
        menu.getSlot(3).set(result);
    }

    /** All result click variants enter here; unsupported/drop/clone paths never manufacture. */
    public static ItemStack take(SmithingMenu menu, Player actor, ClickType click, int button) {
        if (!(actor instanceof ServerPlayer player)) return ItemStack.EMPTY;
        ItemStack completed; RecipeHolder<PendantSmithingRecipe> completedRecipe;
        try {
            var preview = PREVIEWS.get(menu); var recipe = recipe(menu);
            if (!usable(player, menu) || preview == null || recipe == null || !preview.recipe().equals(recipe.id())
                    || !sameInputs(menu, preview.inputs()) || !preview.proof().equals(proof(player, input(menu).base()))) throw new Rejected("stale");
            int destination;
            if (click == ClickType.PICKUP && (button == 0 || button == 1)) {
                if (!menu.getCarried().isEmpty()) throw new Rejected("no_space"); destination = -1;
            } else if (click == ClickType.QUICK_MOVE) {
                destination = emptySlot(player.getInventory()); if (destination < 0) throw new Rejected("no_space");
            } else if (click == ClickType.SWAP && ((button >= 0 && button < 9) || button == 40)) {
                destination = button; if (!player.getInventory().getItem(destination).isEmpty()) throw new Rejected("no_space");
            } else throw new Rejected("unsupported_click");
            var plan = prepare(player, recipe.value(), input(menu));
            if (!sameInputs(menu, preview.inputs()) || !preview.proof().equals(proof(player, input(menu).base())) || !plan.mutation().current())
                throw new Rejected("stale");
            ItemStack actual = plan.result(); var inputs = menu.getSlot(0).container;
            // All fallible calculations and ownership checks are complete. No callbacks until notifications below.
            plan.mutation().commit();
            inputs.removeItemNoUpdate(1);
            inputs.getItem(2).shrink(1); if (inputs.getItem(2).isEmpty()) inputs.removeItemNoUpdate(2);
            if (destination == -1) menu.setCarried(actual);
            else if (destination == 40) player.getInventory().offhand.set(0, actual);
            else player.getInventory().items.set(destination, actual);
            PREVIEWS.remove(menu);
            completed = actual; completedRecipe = recipe;
        } catch (Rejected failure) {
            refresh(menu); message(player, failure.code); menu.broadcastFullState(); return ItemStack.EMPTY;
        } catch (IllegalStateException invalidState) {
            refresh(menu); message(player, "invalid_state"); menu.broadcastFullState(); return ItemStack.EMPTY;
        }
        // A notification failure cannot be reported as an uncommitted operation or rolled back.
        try {
            menu.getSlot(3).set(ItemStack.EMPTY); menu.getSlot(0).container.setChanged(); player.getInventory().setChanged();
            player.awardRecipes(List.of(completedRecipe)); player.inventoryMenu.broadcastChanges(); menu.broadcastFullState();
            player.serverLevel().levelEvent(1044, player.blockPosition(), 0); message(player, "success");
        } catch (RuntimeException notificationFailure) {
            FeedMePackages.LOGGER.error("FMP manufacturing committed, but its post-commit notification failed", notificationFailure);
            message(player, "committed_sync_error");
        }
        return completed.copy();
    }

    private static int emptySlot(Inventory inventory) {
        for (int slot = 8; slot >= 0; slot--) if (inventory.getItem(slot).isEmpty()) return slot;
        for (int slot = 35; slot >= 9; slot--) if (inventory.getItem(slot).isEmpty()) return slot;
        return -1;
    }
    private static void message(ServerPlayer player, String code) {
        player.displayClientMessage(Component.translatable("message.create_feed_me_packages.growth." + code), true);
    }
}
