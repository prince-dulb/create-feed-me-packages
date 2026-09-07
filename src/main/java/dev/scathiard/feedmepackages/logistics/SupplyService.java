package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import java.util.UUID;

public final class SupplyService {
    private SupplyService() {}
    public static String address(ServerPlayer player) { return "FMP@" + player.getGameProfile().getName(); }

    public static void tick(ServerPlayer player) {
        var currentAccess = AccessGate.resolve(player);
        if (currentAccess.active() && dev.scathiard.feedmepackages.consumption.CraftingReservations.operating(currentAccess.handle().cacheId())) return;
        // Arrival precedes demand evaluation, including the first tick after re-equipping.
        ReceiveService.resume(player);
        if (player.tickCount % 20 != 0) return;
        var access = AccessGate.resolve(player);
        if (!access.active() || access.handle().networkId() == null) return;
        if (!Create.LOGISTICS.mayInteract(access.handle().networkId(), player)) return;
        var ledger = CacheLedger.get(player.getServer());
        var initialHandle = access.handle();
        int count = ledger.find(access.handle().cacheId()).state().cells().size();
        for (int slot = 0; slot < count; slot++) {
            access = AccessGate.resolve(player);
            if (!access.active() || !initialHandle.equals(access.handle())
                    || !Create.LOGISTICS.mayInteract(access.handle().networkId(), player)) return;
            var before = ledger.find(access.handle().cacheId()); var state = before.state();
            int wanted = state.requestable(slot);
            if (wanted == 0 || state.orders().size() >= CacheState.MAX_ORDERS) continue;
            var cell = state.cells().get(slot);
            var template = cell.filter().stack(player.registryAccess(), 1);
            var order = PackageOrderWithCrafts.simple(List.of(new BigItemStack(template, wanted)));
            var requests = LogisticsManager.findPackagersForRequest(access.handle().networkId(), order, null, address(player));
            if (requests.isEmpty() || requests.keySet().stream().anyMatch(packager -> packager.isTooBusyFor(RequestType.RESTOCK))) continue;
            int planned = requests.values().stream().mapToInt(request -> request.getCount()).sum();
            if (planned <= 0 || planned > wanted) continue;
            UUID requestId = UUID.randomUUID(); var edit = state.edit();
            edit.request(slot, requestId, player.getUUID(), planned, true);
            ledger.replace(access.handle(), state.revision(), before.withState(edit.finish()));
            var route = new ParcelSeal(UUID.randomUUID(), state.id(), player.getUUID(), requestId,
                    cell.filter().encoded(), cell.filterRevision(), address(player), false, "");
            try (var scope = new DispatchScope(ledger, player.registryAccess(), route)) {
                LogisticsManager.performPackageRequests(requests);
                int unsent = requests.values().stream().mapToInt(request -> request.getCount()).sum();
                var current = ledger.find(state.id()); var confirmation = current.state().edit();
                confirmation.confirmDispatch(requestId, unsent);
                ledger.replace(access.handle(), current.state().revision(), current.withState(confirmation.finish()));
            } catch (RuntimeException unknownOutcome) {
                // Create may have produced real boxes already. Keep the reservation; never compensate/retry automatically.
                FeedMePackages.LOGGER.error("FMP dispatch outcome uncertain for request {}; manual reset may be needed", requestId, unknownOutcome);
            }
        }
    }
}
