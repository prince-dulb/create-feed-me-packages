import com.sun.tools.attach.VirtualMachine;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Main-menu-only artifact verification; never touches a loaded world or force-kills a process. */
public final class VerifyPackagedClient {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected: PID agentJar isolatedGameDirectory mainJar sha256");
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(new File(args[1]).getCanonicalPath(), new File(args[2]).getCanonicalPath() + "|" + new File(args[3]).getCanonicalPath() + "|" + args[4]); }
        finally { vm.detach(); }
    }

    public static void agentmain(String argument, Instrumentation instrumentation) throws Exception {
        var fields = argument.split("\\|", -1);
        if (fields.length != 3) throw new IllegalArgumentException("Invalid packaged verification arguments");
        var expectedDirectory = new File(fields[0]).getCanonicalFile(); var artifact = new File(fields[1]).getCanonicalFile();
        if (!expectedDirectory.getPath().replace('\\', '/').contains("/.fmp-reference-build-safe/run/client/")
                || !expectedDirectory.getName().endsWith("-packaged")) throw new IllegalStateException("Not an isolated packaged client directory");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact.toPath())));
        if (!hash.equals(fields[2])) throw new IllegalStateException("Artifact digest changed");
        Class<?> minecraft = null, mod = null;
        for (var type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals("net.minecraft.client.Minecraft")) minecraft = type;
            if (type.getName().equals("dev.scathiard.feedmepackages.FeedMePackages")) mod = type;
            if (type.getName().equals("dev.scathiard.feedmepackages.gametest.ClientReview")) throw new IllegalStateException("Development review driver leaked into packaged client");
        }
        if (minecraft == null || mod == null) throw new IllegalStateException("Packaged client has not loaded Minecraft and FMP");
        String origin = URLDecoder.decode(mod.getProtectionDomain().getCodeSource().getLocation().toString(), StandardCharsets.UTF_8).replace('\\', '/');
        if (!origin.toLowerCase(Locale.ROOT).contains(artifact.getPath().replace('\\', '/').toLowerCase(Locale.ROOT)))
            throw new IllegalStateException("FMP loaded from an unexpected source: " + origin);
        Object instance = minecraft.getMethod("getInstance").invoke(null);
        if (!((File)minecraft.getField("gameDirectory").get(instance)).getCanonicalFile().equals(expectedDirectory)) throw new IllegalStateException("Wrong client directory");
        Class<?> clientType = minecraft;
        var ready = new CompletableFuture<Void>();
        minecraft.getMethod("execute", Runnable.class).invoke(instance, (Runnable) () -> {
            try {
                if (clientType.getField("level").get(instance) != null || clientType.getMethod("getSingleplayerServer").invoke(instance) != null)
                    throw new IllegalStateException("A world is active; refusing packaged title verification");
                Object screen = clientType.getField("screen").get(instance);
                String name = screen == null ? "null" : screen.getClass().getName();
                if (name.equals("net.minecraft.client.gui.screens.AccessibilityOnboardingScreen")) {
                    System.out.println("FMP_PACKAGED_CLIENT_ONBOARDING native-continue");
                    screen.getClass().getMethod("onClose").invoke(screen);
                } else if (!name.equals("net.minecraft.client.gui.screens.TitleScreen")) {
                    throw new IllegalStateException("Unexpected packaged client screen: " + name);
                }
                ready.complete(null);
            } catch (Exception failure) { ready.completeExceptionally(failure); }
        });
        ready.get(10, TimeUnit.SECONDS);
        Thread.sleep(3000); // Native title widgets fade in after the first panorama-only second.
        var finished = new CompletableFuture<Void>();
        minecraft.getMethod("execute", Runnable.class).invoke(instance, (Runnable) () -> {
            try {
                Object screen = clientType.getField("screen").get(instance);
                if (clientType.getField("level").get(instance) != null || clientType.getMethod("getSingleplayerServer").invoke(instance) != null
                        || screen == null || !screen.getClass().getName().equals("net.minecraft.client.gui.screens.TitleScreen"))
                    throw new IllegalStateException("Packaged verification only accepts an idle vanilla title screen with no world: "
                            + (screen == null ? "null" : screen.getClass().getName()));
                var screenshot = Class.forName("net.minecraft.client.Screenshot", false, clientType.getClassLoader());
                Method grab = null;
                for (var method : screenshot.getMethods()) if (method.getName().equals("grab") && method.getParameterCount() == 4
                        && method.getParameterTypes()[1] == String.class) grab = method;
                if (grab == null) throw new IllegalStateException("Native screenshot API changed");
                String name = "fmp-packaged-title-" + System.currentTimeMillis() + ".png";
                Consumer<Object> captured = message -> {
                    try {
                        if (!new File(expectedDirectory, "screenshots/" + name).isFile()) throw new IllegalStateException("Native title screenshot was not saved: " + message);
                        clientType.getMethod("execute", Runnable.class).invoke(instance, (Runnable) () -> {
                            try {
                                if (clientType.getField("level").get(instance) != null) throw new IllegalStateException("A world opened during artifact verification");
                                System.out.println("FMP_PACKAGED_CLIENT_PASSED sha256=" + hash + " screenshot=" + name + " source=" + origin);
                                clientType.getMethod("stop").invoke(instance); finished.complete(null);
                            } catch (Exception failure) { finished.completeExceptionally(failure); }
                        });
                    } catch (Exception failure) { finished.completeExceptionally(failure); }
                };
                grab.invoke(null, expectedDirectory, name, clientType.getMethod("getMainRenderTarget").invoke(instance), captured);
            } catch (Exception failure) { finished.completeExceptionally(failure); }
        });
        finished.get(40, TimeUnit.SECONDS);
    }
}
