import com.sun.tools.attach.VirtualMachine;
import java.io.File;
import java.lang.instrument.Instrumentation;

/** Recovery of this project's isolated dedicated test server when the dev task did not forward stdin. */
public final class FinishServerReview {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected: PID agentJar isolatedGameDirectory");
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(new File(args[1]).getCanonicalPath(), new File(args[2]).getCanonicalPath()); }
        finally { vm.detach(); }
    }
    public static void agentmain(String directory, Instrumentation instrumentation) throws Exception {
        File expected = new File(directory).getCanonicalFile(), actual = new File(System.getProperty("user.dir")).getCanonicalFile();
        if (!expected.equals(actual) || !actual.toPath().toString().replace('\\', '/').contains("/.fmp-reference-build-safe/run/server/"))
            throw new IllegalStateException("Wrong isolated server directory");
        Class<?> hooks = null; boolean fmp = false, client = false;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals("net.neoforged.neoforge.server.ServerLifecycleHooks")) hooks = type;
            if (type.getName().equals("dev.scathiard.feedmepackages.FeedMePackages")) fmp = true;
            if (type.getName().equals("net.minecraft.client.Minecraft")) client = true;
        }
        if (!fmp || client || hooks == null) throw new IllegalStateException("Not a reference dedicated server");
        Object server = hooks.getMethod("getCurrentServer").invoke(null);
        if (server == null || !(boolean) server.getClass().getMethod("isDedicatedServer").invoke(server)) throw new IllegalStateException("Dedicated server is missing");
        server.getClass().getMethod("halt", boolean.class).invoke(server, false);
        System.out.println("FMP_DEDICATED_RECOVERY_REQUESTED_NORMAL_SERVER_STOP");
    }
}
