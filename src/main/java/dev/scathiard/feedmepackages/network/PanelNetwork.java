package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

public final class PanelNetwork {
    private PanelNetwork() {}
    // Installed only by client bootstrap. Common registration never resolves Minecraft/client classes on a server.
    private static Consumer<PanelPackets.Snapshot> clientReceiver = packet -> {};
    public static void receiveOnClient(Consumer<PanelPackets.Snapshot> receiver) { clientReceiver = Objects.requireNonNull(receiver); }
    private static final Map<ServerPlayer, Window> WINDOWS = new WeakHashMap<>();
    private static final Map<ServerPlayer, Budget> BUDGETS = new WeakHashMap<>();
    private static final class Window {
        final UUID id; final int menu; long serial; int lastSequence;
        Window(UUID id, int menu) { this.id = id; this.menu = menu; }
    }
    private static final class Budget {
        int queryTick = Integer.MIN_VALUE, actionTick, actions;
        boolean query(int tick) { if ((long) tick - queryTick < 4) return false; queryTick = tick; return true; }
        boolean action(int tick) {
            if (tick - actionTick >= 20 || tick < actionTick) { actionTick = tick; actions = 0; }
            return ++actions <= 16;
        }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToServer(PanelPackets.Query.TYPE, PanelPackets.Query.CODEC, (packet, context) -> {
            var reply = query((ServerPlayer) context.player(), packet); if (reply != null) context.reply(reply);
        });
        registrar.playToServer(PanelPackets.Command.TYPE, PanelPackets.Command.CODEC, (packet, context) -> {
            var reply = command((ServerPlayer) context.player(), packet); if (reply != null) context.reply(reply);
        });
        registrar.playToClient(PanelPackets.Snapshot.TYPE, PanelPackets.Snapshot.CODEC, (packet, context) -> clientReceiver.accept(packet));
    }
    public static void forget(ServerPlayer player) { WINDOWS.remove(player); BUDGETS.remove(player); CacheActions.close(player); }
    public static PanelPackets.Snapshot query(ServerPlayer player, PanelPackets.Query packet) {
        var window = WINDOWS.get(player);
        if (!packet.open()) {
            if (window != null && window.id.equals(packet.window())) { WINDOWS.remove(player); CacheActions.close(player); }
            return null;
        }
        if (!BUDGETS.computeIfAbsent(player, p -> new Budget()).query(player.tickCount)) return null;
        if (packet.menu() != player.containerMenu.containerId) return null;
        if (window == null || !window.id.equals(packet.window()) || window.menu != packet.menu()) {
            CacheActions.close(player); window = new Window(packet.window(), packet.menu()); WINDOWS.put(player, window);
        }
        return snapshot(player, window, 0, CacheActions.Result.OK);
    }
    public static PanelPackets.Snapshot command(ServerPlayer player, PanelPackets.Command packet) {
        var window = WINDOWS.get(player);
        if (window == null || !window.id.equals(packet.window()) || window.menu != player.containerMenu.containerId) return null;
        if (!BUDGETS.computeIfAbsent(player, p -> new Budget()).action(player.tickCount)) return null;
        if (packet.sequence() <= window.lastSequence) return snapshot(player, window, packet.sequence(), CacheActions.Result.STALE);
        window.lastSequence = packet.sequence();
        var result = packet.creativeCursor()
                ? CacheActions.executeCreative(player, packet.intent(), packet.cursorTemplate(), packet.cursorCount())
                : CacheActions.execute(player, packet.intent());
        return snapshot(player, window, packet.sequence(), result);
    }
    private static PanelPackets.Snapshot snapshot(ServerPlayer player, Window window, int ack, CacheActions.Result result) {
        var view = CacheActions.snapshot(player); var access = view.access(); var record = view.record();
        if (access.terminals().size() > PanelPackets.MAX_TERMINALS) {
            CacheActions.close(player);
            return new PanelPackets.Snapshot(window.id, null, ++window.serial, ack, CacheActions.Result.INVALID_REQUEST,
                    AccessGate.Status.INVALID_IDENTITY, -1, 0, 0, false, false, false, "", "", "", List.of(), List.of());
        }
        var terminals = access.terminals().stream().map(t -> new PanelPackets.TerminalView(t.slot(),
                ((PendantItem)t.stack().getItem()).personal(), t.disabled(), t.network() == null ? "" : t.network().toString())).toList();
        List<PanelPackets.CellView> cells = new ArrayList<>();
        String residualItem = "";
        if (record != null) {
            for (int i = 0; i < record.state().cells().size(); i++) {
                var cell = record.state().cells().get(i);
                boolean slotResidual = cell.filter() != null && !record.residual(cell.filter()).isEmpty();
                int reserved = cell.filter() == null ? 0 : dev.scathiard.feedmepackages.storage.CacheLedger.get(player.getServer()).pendingTake(access.handle().cacheId(), cell.filter());
                cells.add(new PanelPackets.CellView(cell.filter() == null ? "" : cell.filter().encoded(), cell.amount(), cell.minimum(), cell.maximum(), record.state().pending(i), reserved, cell.filter() == null ? 1 : cell.filter().stackSize(), slotResidual));
            }
            for (var box : record.residuals().values())
                if (!box.isEmpty()) { residualItem = BuiltInRegistries.ITEM.getKey(box.getItem()).toString(); break; }
        }
        String returnAddress = access.active() && access.handle() != null
                ? dev.scathiard.feedmepackages.storage.CacheLedger.get(player.getServer()).returnAddress(access.handle().cacheId()) : null;
        return new PanelPackets.Snapshot(window.id, view.session(), ++window.serial, ack, result, access.status(),
                record == null ? -1 : record.state().revision(), record == null ? 0 : record.state().level(),
                record == null ? 0 : CacheLevel.of(record.state().level()).capacity(), record != null && record.owner() != null,
                view.cacheFirst(), access.active() && access.handle().networkId() != null, SupplyService.address(player),
                returnAddress == null ? "" : returnAddress, residualItem, cells, terminals);
    }
}
