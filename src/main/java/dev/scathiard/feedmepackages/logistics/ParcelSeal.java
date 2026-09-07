package dev.scathiard.feedmepackages.logistics;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import java.util.UUID;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

/** The world authenticates this value together with actual package contents. */
public record ParcelSeal(UUID parcelId, UUID cacheId, UUID playerId, UUID requestId,
                         String variant, long filterRevision, String address,
                         boolean arrived, String signature) {
    public static final Codec<ParcelSeal> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("parcel").forGetter(ParcelSeal::parcelId),
            UUIDUtil.CODEC.fieldOf("cache").forGetter(ParcelSeal::cacheId),
            UUIDUtil.CODEC.fieldOf("player").forGetter(ParcelSeal::playerId),
            UUIDUtil.CODEC.fieldOf("request").forGetter(ParcelSeal::requestId),
            Codec.STRING.fieldOf("variant").forGetter(ParcelSeal::variant),
            Codec.LONG.fieldOf("revision").forGetter(ParcelSeal::filterRevision),
            Codec.STRING.fieldOf("address").forGetter(ParcelSeal::address),
            Codec.BOOL.fieldOf("arrived").forGetter(ParcelSeal::arrived),
            Codec.STRING.fieldOf("signature").forGetter(ParcelSeal::signature)
    ).apply(instance, ParcelSeal::new));

    public ParcelSeal {
        Objects.requireNonNull(parcelId); Objects.requireNonNull(cacheId); Objects.requireNonNull(playerId); Objects.requireNonNull(requestId);
        Objects.requireNonNull(variant); Objects.requireNonNull(address); Objects.requireNonNull(signature);
        if (variant.getBytes(StandardCharsets.UTF_8).length > 1024 || address.length() > 128 || signature.length() > 64 || filterRevision < 0)
            throw new IllegalArgumentException("Invalid parcel seal");
    }
    public ParcelSeal signed(boolean received, String mac) {
        return new ParcelSeal(parcelId, cacheId, playerId, requestId, variant, filterRevision, address, received, mac);
    }
}
