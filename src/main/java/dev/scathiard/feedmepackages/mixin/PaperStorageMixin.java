package dev.scathiard.feedmepackages.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The native SavedData receives a registry provider but 2.3.0 does not pass it into its list codec. */
@Pseudo
@Mixin(targets = "com.kreidev.cmpackagecouriers.plane.CardboardPlaneSavedData", remap = false)
public abstract class PaperStorageMixin {
    @Redirect(method = "<init>(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"), remap = false)
    private <T> DataResult<?> fmp$readContext(Codec<?> codec, DynamicOps<T> ops, T input,
                                             CompoundTag original, HolderLookup.Provider registries) {
        if (input instanceof Tag tag) return codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag);
        return codec.parse(ops, input);
    }

    @Redirect(method = "save", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"), remap = false)
    private <R, T> DataResult<?> fmp$writeContext(Codec<R> codec, DynamicOps<T> ops, R value,
                                                   CompoundTag original, HolderLookup.Provider registries) {
        return codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), value);
    }
}
