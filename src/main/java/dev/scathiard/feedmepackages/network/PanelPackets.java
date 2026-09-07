package dev.scathiard.feedmepackages.network;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.service.AccessGate;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small explicit wire models. No ledger, cache capability, secret, or mutable stack crosses this API. */
public final class PanelPackets {
    private PanelPackets() {}
    public static final int C2S_LIMIT = 8192, S2C_LIMIT = 65536, MAX_TERMINALS = 64;
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, path));
    }
    public record Query(UUID window, int menu, boolean open) implements CustomPacketPayload {
        public static final Type<Query> TYPE = PanelPackets.type("panel_query");
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.window); buf.writeVarInt(value.menu); buf.writeBoolean(value.open);
        }, buf -> {
            bound(buf, 128); var result = new Query(buf.readUUID(), buf.readVarInt(), buf.readBoolean()); end(buf); return result;
        });
        @Override public Type<Query> type() { return TYPE; }
    }
    public record Command(UUID window, int sequence, CacheActions.Intent intent, boolean creativeCursor, String cursorTemplate, int cursorCount) implements CustomPacketPayload {
        public static final Type<Command> TYPE = PanelPackets.type("panel_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC = StreamCodec.of((buf, value) -> {
            int start = buf.writerIndex(); var i = value.intent;
            buf.writeUUID(value.window); buf.writeVarInt(value.sequence); buf.writeUUID(i.session()); buf.writeLong(i.revision());
            buf.writeEnum(i.action()); buf.writeVarInt(i.slot()); buf.writeVarInt(i.first()); buf.writeVarInt(i.second()); text(buf, i.template(), 1024);
            buf.writeBoolean(value.creativeCursor); text(buf, value.cursorTemplate, 1024); buf.writeVarInt(value.cursorCount);
            written(buf, start, C2S_LIMIT);
        }, buf -> {
            bound(buf, C2S_LIMIT); UUID window = buf.readUUID(); int sequence = buf.readVarInt();
            var intent = new CacheActions.Intent(buf.readUUID(), buf.readLong(), buf.readEnum(CacheActions.Action.class),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), text(buf, 1024));
            boolean creative = buf.readBoolean(); String cursor = text(buf, 1024); int count = buf.readVarInt();
            end(buf); return new Command(window, sequence, intent, creative, cursor, count);
        });
        @Override public Type<Command> type() { return TYPE; }
    }
    public record CellView(String template, int amount, int minimum, int maximum, int pending, int stackSize, boolean residual) {}
    public record TerminalView(int slot, boolean personal, boolean disabled, String network) {}
    public record Snapshot(UUID window, UUID session, long serial, int acknowledged, CacheActions.Result result,
                           AccessGate.Status status, long revision, int level, int capacity, boolean personal,
                           boolean cacheFirst, boolean bound, String address, String returnAddress, String residualItem,
                           List<CellView> cells, List<TerminalView> terminals) implements CustomPacketPayload {
        public Snapshot {
            cells = List.copyOf(cells); terminals = List.copyOf(terminals);
            if (cells.size() > 36 || terminals.size() > MAX_TERMINALS) throw new IllegalArgumentException("Oversized panel model");
        }
        /** Uniform group capacity: same for all items at this level. */
        public int groupCapacity() { return capacity / 64; }
        public static final Type<Snapshot> TYPE = PanelPackets.type("panel_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(PanelPackets::encodeSnapshot, PanelPackets::decodeSnapshot);
        @Override public Type<Snapshot> type() { return TYPE; }
    }
    private static void encodeSnapshot(RegistryFriendlyByteBuf b, Snapshot s) {
        int start = b.writerIndex(); b.writeUUID(s.window); b.writeBoolean(s.session != null); if (s.session != null) b.writeUUID(s.session);
        b.writeLong(s.serial); b.writeVarInt(s.acknowledged); b.writeEnum(s.result); b.writeEnum(s.status); b.writeLong(s.revision);
        b.writeVarInt(s.level); b.writeVarInt(s.capacity); b.writeBoolean(s.personal); b.writeBoolean(s.cacheFirst); b.writeBoolean(s.bound);
        text(b, s.address, 128); text(b, s.returnAddress, 128); text(b, s.residualItem, 256); b.writeVarInt(s.cells.size());
        for (var c : s.cells) {
            text(b, c.template, 1024); b.writeVarInt(c.amount); b.writeVarInt(c.minimum); b.writeVarInt(c.maximum); b.writeVarInt(c.pending); b.writeVarInt(c.stackSize); b.writeBoolean(c.residual);
        }
        b.writeVarInt(s.terminals.size());
        for (var t : s.terminals) { b.writeVarInt(t.slot); b.writeBoolean(t.personal); b.writeBoolean(t.disabled); text(b, t.network, 36); }
        written(b, start, S2C_LIMIT);
    }
    private static Snapshot decodeSnapshot(RegistryFriendlyByteBuf b) {
        bound(b, S2C_LIMIT); UUID window = b.readUUID(), session = b.readBoolean() ? b.readUUID() : null;
        long serial = b.readLong(); int ack = b.readVarInt(); var result = b.readEnum(CacheActions.Result.class);
        var status = b.readEnum(AccessGate.Status.class); long revision = b.readLong();
        int level = b.readVarInt(), capacity = b.readVarInt(); boolean personal = b.readBoolean(), cacheFirst = b.readBoolean(), bound = b.readBoolean();
        String address = text(b, 128), returnAddress = text(b, 128), residual = text(b, 256); int size = size(b, 36); List<CellView> cells = new ArrayList<>(size);
        for (int i = 0; i < size; i++) cells.add(new CellView(text(b, 1024), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean()));
        size = size(b, MAX_TERMINALS); List<TerminalView> terminals = new ArrayList<>(size);
        for (int i = 0; i < size; i++) terminals.add(new TerminalView(b.readVarInt(), b.readBoolean(), b.readBoolean(), text(b, 36)));
        end(b); return new Snapshot(window, session, serial, ack, result, status, revision, level, capacity, personal, cacheFirst, bound, address, returnAddress, residual, cells, terminals);
    }
    static int size(RegistryFriendlyByteBuf b, int max) {
        int size = b.readVarInt(); if (size < 0 || size > max) throw new DecoderException("Panel list limit exceeded"); return size;
    }
    static String text(RegistryFriendlyByteBuf b, int maxBytes) {
        // readUtf checks the encoded length before allocating or decoding; additionally enforce the byte budget.
        String value = b.readUtf(maxBytes);
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new DecoderException("Panel text byte limit exceeded");
        return value;
    }
    static void text(RegistryFriendlyByteBuf b, String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new EncoderException("Panel text byte limit exceeded");
        b.writeUtf(value, maxBytes);
    }
    static void bound(RegistryFriendlyByteBuf b, int limit) {
        if (b.readableBytes() > limit) throw new DecoderException("Panel message limit exceeded");
    }
    static void end(RegistryFriendlyByteBuf b) { if (b.isReadable()) throw new DecoderException("Trailing panel data"); }
    static void written(RegistryFriendlyByteBuf b, int start, int limit) {
        if (b.writerIndex() - start > limit) throw new EncoderException("Panel message limit exceeded");
    }
}
