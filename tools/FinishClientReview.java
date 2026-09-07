import com.sun.tools.attach.VirtualMachine;
import java.io.File;
import java.lang.instrument.Instrumentation;

/** Debug recovery for an explicitly identified, locally started reference test client. Never force-kills it. */
public final class FinishClientReview {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected: PID agentJar isolatedGameDirectory");
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(new File(args[1]).getCanonicalPath(), new File(args[2]).getCanonicalPath()); }
        finally { vm.detach(); }
    }
    public static void agentmain(String directory, Instrumentation instrumentation) throws Exception {
        Class<?> minecraft = null, hooks = null; boolean review = false;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals("net.minecraft.client.Minecraft")) minecraft = type;
            if (type.getName().equals("net.neoforged.neoforge.server.ServerLifecycleHooks")) hooks = type;
            if (type.getName().equals("dev.scathiard.feedmepackages.gametest.ClientReview")) review = true;
        }
        if (!review || minecraft == null || hooks == null) throw new IllegalStateException("Not a reference review client");
        Object instance = minecraft.getMethod("getInstance").invoke(null);
        File actual = (File) minecraft.getField("gameDirectory").get(instance);
        if (!actual.getCanonicalFile().equals(new File(directory).getCanonicalFile())) throw new IllegalStateException("Wrong client directory");
        Object server = hooks.getMethod("getCurrentServer").invoke(null);
        if (server == null) throw new IllegalStateException("No review server remains");
        server.getClass().getMethod("halt", boolean.class).invoke(server, false);
        System.out.println("FMP_REVIEW_RECOVERY_REQUESTED_NORMAL_SERVER_STOP");
    }
}
