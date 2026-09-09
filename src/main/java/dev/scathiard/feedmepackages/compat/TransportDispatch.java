package dev.scathiard.feedmepackages.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;

/** Isolated, reflection-based dispatch of a return package to its address.
 *  The transport add-ons are optional and not on the compile classpath; every call is guarded by a
 *  class-presence probe so the core mod loads and behaves identically when they are absent. */
public final class TransportDispatch {
    private TransportDispatch() {}
    /** Result of one dispatch attempt. dispatched=true means the carrier was accepted (deduct the cache). */
    public record Result(boolean dispatched, String transport) {}

    private static boolean present(String className) {
        try { Class.forName(className, false, TransportDispatch.class.getClassLoader()); return true; }
        catch (Throwable absent) { return false; }
    }

    /** Try to send a package with a paper plane. Returns dispatched only when addPlane accepted it. */
    public static Result paperPlane(ServerLevel level, ItemStack box, Vec3 pos) {
        if (!present("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager")) return new Result(false, "paper");
        try {
            Class<?> manager = Class.forName("com.kreidev.cmpackagecouriers.plane.CardboardPlaneManager");
            Method addPlane = manager.getMethod("addPlane", net.minecraft.world.level.Level.class, Vec3.class, float.class, float.class, ItemStack.class, boolean.class);
            Object accepted = addPlane.invoke(null, level, pos, 0f, 0f, box, false);
            return new Result(Boolean.TRUE.equals(accepted), "paper");
        } catch (Throwable failure) {
            return new Result(false, "paper");
        }
    }

    /** Try to send a package with a transport bee. dispatched=true means native transport accepted it.
     *  Supply the cache's actual network for native address lookup; fallback and later target reselection
     *  remain the transport mod's responsibility. */
    public static Result bee(ServerLevel level, ItemStack box, BlockPos spawnPos, java.util.UUID network) {
        if (!present("de.theidler.create_mobile_packages.robo.RoboManager")) return new Result(false, "bee");
        try {
            // newRobo always returns a UUID, even when no destination can receive the cargo.
            // Ask an unregistered native probe to resolve its target before handing over any real stock.
            // This keeps native fallback/reselection semantics and creates no entity or transport record.
            Class<?> robo = Class.forName("de.theidler.create_mobile_packages.robo.VirtualRobo");
            Object probe = robo.getConstructor(ServerLevel.class, java.util.UUID.class, ItemStack.class, BlockPos.class, java.util.UUID.class)
                    .newInstance(level, java.util.UUID.randomUUID(), box.copy(), spawnPos, network);
            if (robo.getMethod("getTargetPosition").invoke(probe) == null) return new Result(false, "bee");
            Object target = robo.getMethod("getTarget").invoke(probe);
            Class<?> targetType = Class.forName("de.theidler.create_mobile_packages.robo.RoboTarget");
            if (target == null || !Boolean.TRUE.equals(targetType.getMethod("isValid", robo).invoke(target, probe)))
                return new Result(false, "bee");
            Class<?> roboManager = Class.forName("de.theidler.create_mobile_packages.robo.RoboManager");
            Object manager = roboManager.getMethod("get", ServerLevel.class).invoke(null, level);
            Method newRobo = roboManager.getMethod("newRobo", ServerLevel.class, ItemStack.class, BlockPos.class, java.util.UUID.class,
                    float.class, BlockPos.class, boolean.class);
            Object uuid = newRobo.invoke(manager, level, box, spawnPos, network, 0f, null, false);
            return new Result(uuid != null, "bee");
        } catch (Throwable failure) {
            return new Result(false, "bee");
        }
    }
}
