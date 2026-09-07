package dev.scathiard.feedmepackages.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import java.util.Objects;
import java.util.UUID;

/** Displayable bearer token; only a matching, unclaimed ledger entry grants capacity. */
public record CapacityGrant(UUID token, int level) {
    public CapacityGrant {
        Objects.requireNonNull(token);
        if (level < 2 || level > 5) throw new IllegalArgumentException("Invalid capacity grant level");
    }
    public static final Codec<CapacityGrant> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("token").forGetter(CapacityGrant::token),
            Codec.INT.fieldOf("level").forGetter(CapacityGrant::level)).apply(i, CapacityGrant::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, CapacityGrant> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CapacityGrant::token, ByteBufCodecs.VAR_INT, CapacityGrant::level, CapacityGrant::new);
}
