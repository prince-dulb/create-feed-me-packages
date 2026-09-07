package dev.scathiard.feedmepackages.mixin;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Inspects bytecode without loading optional game classes during bootstrap. */
public final class OptionalMixins implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean receive = mixinClassName.endsWith("MobileReceiveMixin");
        boolean storage = mixinClassName.endsWith("MobileStorageMixin");
        boolean paper = mixinClassName.endsWith("PaperStorageMixin");
        if (!receive && !storage && !paper) return true;
        try {
            ClassNode target = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName);
            boolean compatible = receive ? target.methods.stream().anyMatch(method -> method.name.equals("sendPackageToPlayer")
                    && method.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Z"))
                    : storage ? hasStorageHook(target) : hasPaperStorageHooks(target);
            if (!compatible) LogUtils.getLogger().warn("FMP optional adapter {} disabled: incompatible method shape", mixinClassName);
            return compatible;
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (IOException unreadable) {
            LogUtils.getLogger().warn("FMP optional service port disabled: cannot inspect {}", targetClassName, unreadable);
            return false;
        }
    }
    private static boolean hasStorageHook(ClassNode target) {
        return hasCall(target, "deserializeNBT", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/nbt/CompoundTag;)Lde/theidler/create_mobile_packages/robo/VirtualRobo;", "parse");
    }
    private static boolean hasPaperStorageHooks(ClassNode target) {
        String arguments = "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)";
        return hasCall(target, "<init>", arguments + "V", "parse")
                && hasCall(target, "save", arguments + "Lnet/minecraft/nbt/CompoundTag;", "encodeStart");
    }
    private static boolean hasCall(ClassNode target, String name, String descriptor, String invocation) {
        for (var method : target.methods) {
            if (!method.name.equals(name) || !method.desc.equals(descriptor)) continue;
            int matches = 0;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/serialization/Codec") && call.name.equals(invocation)
                    && call.desc.equals("(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;")) matches++;
            return matches == 1;
        }
        return false;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
