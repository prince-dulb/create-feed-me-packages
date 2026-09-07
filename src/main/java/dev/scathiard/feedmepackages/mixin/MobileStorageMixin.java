package dev.scathiard.feedmepackages.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.scathiard.feedmepackages.FeedMePackages;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only supplies the context used by Mobile's own writer; ordinary carriers keep native decoding. */
@Pseudo
@Mixin(targets = "de.theidler.create_mobile_packages.robo.VirtualRobo", remap = false)
public abstract class MobileStorageMixin {
    @Redirect(method = "deserializeNBT", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"), remap = false)
    private static <T> DataResult<?> fmp$registryContext(Codec<?> codec, DynamicOps<T> ops, T input,
                                                        ServerLevel level, CompoundTag roboTag) {
        if (input instanceof CompoundTag stackTag && stackTag.getCompound("components")
                .contains(FeedMePackages.MOD_ID + ":parcel_seal", Tag.TAG_COMPOUND)) {
            return ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stackTag);
        }
        return codec.parse(ops, input);
    }
}
