package dev.scathiard.feedmepackages.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import java.util.UUID;

/** Real server Inventory and Curios capability, without a network client or automatic world ticks. */
final class TestPlayers {
    private TestPlayers() {}
    static ServerPlayer create(GameTestHelper helper, ItemStack pendant) {
        return create(helper, UUID.randomUUID(), pendant);
    }
    static ServerPlayer create(GameTestHelper helper, UUID id, ItemStack pendant) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(id, "Fmp" + id.toString().substring(0, 8)));
        CuriosApi.getCuriosInventory(player).orElseThrow().reset();
        necklace(player).setStackInSlot(0, pendant);
        return player;
    }
    static IDynamicStackHandler necklace(ServerPlayer player) {
        return CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("necklace").orElseThrow().getStacks();
    }
    /** Retains vanilla inbound packet handlers; only outbound transport is inert. */
    static java.util.List<net.minecraft.network.protocol.Packet<?>> nativePackets(ServerPlayer player) {
        var sent = new java.util.ArrayList<net.minecraft.network.protocol.Packet<?>>();
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(player.getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { sent.add(packet); }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.PacketSendListener listener) { sent.add(packet); }
        };
        return sent;
    }
    static ServerPlayer real(GameTestHelper helper, UUID id) {
        var profile = new GameProfile(id, "Fmp" + id.toString().substring(0, 8));
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile,
                net.minecraft.server.level.ClientInformation.createDefault());
        // Only networking is inert; death and restoreFrom must execute ServerPlayer's real methods.
        player.connection = new FakePlayer(helper.getLevel(), profile).connection;
        CuriosApi.getCuriosInventory(player).orElseThrow().reset();
        player.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, 1)));
        return player;
    }
}
