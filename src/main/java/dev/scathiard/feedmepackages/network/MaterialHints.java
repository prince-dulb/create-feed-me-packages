package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

/** Read-only view, separate from a GUI window. Template updates <=40KiB; count updates <=200 bytes. */
public final class MaterialHints {
    private MaterialHints() {}
    public record Message(UUID generation, long serial, boolean full, boolean active, boolean cacheFirst,
                          List<String> templates, List<Integer> amounts, int menu, List<Integer> ownReservations,
                          List<Integer> reservedGrid) implements CustomPacketPayload {
        public Message {
            templates = List.copyOf(templates); amounts = List.copyOf(amounts);
            ownReservations = List.copyOf(ownReservations); reservedGrid = List.copyOf(reservedGrid);
            if (ownReservations.size() != amounts.size() || reservedGrid.size() > 9 || menu < 0
                    || ownReservations.stream().anyMatch(n -> n < 0 || n > 4096) || reservedGrid.stream().anyMatch(n -> n < 0 || n > 4096))
                throw new IllegalArgumentException("Invalid reservation hints");
            if (serial < 1 || amounts.size() > 36 || templates.size() > 36 || (full ? templates.size() != amounts.size() : !templates.isEmpty())
                    || amounts.stream().anyMatch(n -> n < 0 || n > 4096) || (!active && (!full || !amounts.isEmpty())))
                throw new IllegalArgumentException("Invalid material hints");
        }
        public static final Type<Message> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "material_hints"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC = StreamCodec.of((b, m) -> {
            int start = b.writerIndex(); b.writeUUID(m.generation); b.writeLong(m.serial); b.writeBoolean(m.full); b.writeBoolean(m.active); b.writeBoolean(m.cacheFirst);
            b.writeVarInt(m.amounts.size());
            for (int i = 0; i < m.amounts.size(); i++) { if (m.full) PanelPackets.text(b, m.templates.get(i), 1024); b.writeVarInt(m.amounts.get(i)); }
            b.writeVarInt(m.menu); for (int held : m.ownReservations) b.writeVarInt(held);
            b.writeVarInt(m.reservedGrid.size()); for (int held : m.reservedGrid) b.writeVarInt(held);
            PanelPackets.written(b, start, 40960);
        }, b -> {
            PanelPackets.bound(b, 40960); var generation = b.readUUID(); long serial = b.readLong();
            boolean full = b.readBoolean(), active = b.readBoolean(), first = b.readBoolean(); int size = PanelPackets.size(b, 36);
            var templates = new ArrayList<String>(); var amounts = new ArrayList<Integer>();
            for (int i = 0; i < size; i++) { if (full) templates.add(PanelPackets.text(b, 1024)); amounts.add(b.readVarInt()); }
            int menu = b.readVarInt(); var own = new ArrayList<Integer>(); for (int i = 0; i < size; i++) own.add(b.readVarInt());
            int gridSize = PanelPackets.size(b, 9); var grid = new ArrayList<Integer>(); for (int i = 0; i < gridSize; i++) grid.add(b.readVarInt());
            PanelPackets.end(b); return new Message(generation, serial, full, active, first, templates, amounts, menu, own, grid);
        });
        @Override public Type<Message> type() { return TYPE; }
    }
    private record Sent(CacheHandle handle, Message message) {}
    private static final Map<ServerPlayer, Sent> LAST = new WeakHashMap<>();
    private static Consumer<Message> clientReceiver = packet -> {};
    public static void receiveOnClient(Consumer<Message> consumer) { clientReceiver = Objects.requireNonNull(consumer); }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(Message.TYPE, Message.CODEC, (packet, context) -> clientReceiver.accept(packet));
    }
    public static void forget(ServerPlayer player) { LAST.remove(player); }
    public static void tick(ServerPlayer player) {
        if (player.tickCount % 4 != 0) return;
        var packet = next(player); if (packet != null) PacketDistributor.sendToPlayer(player, packet);
    }
    public static Message next(ServerPlayer player) {
        var access = AccessGate.resolve(player); boolean active = access.active() && !player.isSpectator();
        var handle = active ? access.handle() : null; var ledger = CacheLedger.get(player.getServer());
        var templates = new ArrayList<String>(); var amounts = new ArrayList<Integer>();
        if (active) for (var cell : ledger.find(handle.cacheId()).state().cells()) {
            templates.add(cell.filter() == null ? "" : cell.filter().encoded());
            amounts.add(Math.max(0, cell.amount() - CraftingReservations.reservedCache(handle.cacheId(), amounts.size(), null)));
        }
        int menu = player.containerMenu.containerId;
        var own = active ? CraftingReservations.cacheAmounts(player, amounts.size()) : List.<Integer>of();
        var grid = active ? CraftingReservations.gridAmounts(player) : List.<Integer>of();
        boolean first = active && ledger.cacheFirst(player.getUUID()); var last = LAST.get(player);
        if (last != null && Objects.equals(last.handle, handle) && last.message.templates.equals(templates)
                && last.message.amounts.equals(amounts) && last.message.cacheFirst == first && last.message.menu == menu
                && last.message.ownReservations.equals(own) && last.message.reservedGrid.equals(grid)) return null;
        boolean full = last == null || !Objects.equals(last.handle, handle) || !last.message.templates.equals(templates) || !active;
        UUID generation = full ? UUID.randomUUID() : last.message.generation;
        long serial = last == null ? 1 : Math.incrementExact(last.message.serial);
        var stored = new Message(generation, serial, true, active, first, templates, amounts, menu, own, grid); LAST.put(player, new Sent(handle, stored));
        return full ? stored : new Message(generation, serial, false, active, first, List.of(), amounts, menu, own, grid);
    }
}
