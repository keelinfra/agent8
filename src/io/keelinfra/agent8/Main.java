package io.keelinfra.agent8;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import java.util.jar.JarFile;

/**
 * Entry point of agent8.jar, so it can replace agent.jar without changing how
 * the agent is started: {@code java -jar agent8.jar -url ... -secret ...}.
 *
 * <p>The Java agents must be active before any remoting class loads. When the
 * JVM was not started with them, this starts the same Java again with
 * {@code -javaagent:agent8.jar -javaagent:jvmdg.jar} and the same JVM options,
 * and waits for it. Otherwise it runs remoting's launcher directly.
 *
 * <p>{@code java -jar agent8.jar --check} checks, with both Java agents active, what the agent
 * needs from the JVM before it can connect (security providers, key stores, TLS); install.sh
 * runs it.
 */
public final class Main {

    static final String CHILD = "agent8.child";

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--remoting-version")) {
            System.out.println(remotingVersion(new File(args[1])));
            return;
        }
        if (Agent8.active && jvmdgActive()) {
            if (args.length == 1 && args[0].equals("--check")) {
                System.exit(check());
            }
            hudson.remoting.Launcher.main(args);
            return;
        }
        if (Boolean.getBoolean(CHILD)) {
            throw new IllegalStateException("agent8: Java agents did not start");
        }
        System.exit(relaunch(args));
    }

    private static boolean jvmdgActive() {
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-javaagent:") && a.contains("jvmdg")) {
                return true;
            }
        }
        return false;
    }

    private static int relaunch(String[] args) throws Exception {
        // toURI() decodes the path, e.g. %20 for a space in the directory name.
        File self = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File jvmdg = jvmdg(self);
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(System.getProperty("java.home"), "bin" + File.separator + "java").getPath());
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!a.startsWith("-javaagent:")) {
                cmd.add(a);
            }
        }
        cmd.add("-D" + CHILD + "=true");
        cmd.add("-javaagent:" + self.getPath());
        cmd.add("-javaagent:" + jvmdg.getPath());
        cmd.add("-cp");
        cmd.add(self.getPath());
        cmd.add(Main.class.getName());
        for (String a : args) {
            cmd.add(a);
        }
        final Process child = new ProcessBuilder(cmd).inheritIO().start();
        // Stopping this JVM (service stop, SIGTERM) stops the agent too.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                child.destroy();
            }
        }, "agent8 stop"));
        return child.waitFor();
    }

    /** jvmdg.jar: -Dagent8.jvmdg, $AGENT8_JVMDG, or next to agent8.jar. */
    static File jvmdg(File self) {
        String p = System.getProperty("agent8.jvmdg", System.getenv("AGENT8_JVMDG"));
        File f = p != null ? new File(p) : new File(self.getAbsoluteFile().getParentFile(), "jvmdg.jar");
        if (!f.isFile()) {
            throw new IllegalStateException("agent8: cannot find " + f + "; put jvmdg.jar next to "
                    + self.getName() + " or set -Dagent8.jvmdg=/path/to/jvmdg.jar");
        }
        return f;
    }

    /**
     * Loads every security provider's classes and the key store and TLS context remoting uses.
     * Signed provider jars (IBMJCE on IBM Java, SunPKCS11 on Solaris) fail here with "signer
     * information does not match" if a Java agent broke them, before the agent ever connects.
     *
     * @return the exit code: 0 if everything works
     */
    static int check() {
        int failures = 0;
        for (Provider p : Security.getProviders()) {
            ClassLoader loader = p.getClass().getClassLoader();
            for (Provider.Service s : p.getServices()) {
                try {
                    Class.forName(s.getClassName(), true, loader);
                } catch (ClassNotFoundException e) {
                    // listed but not shipped in this JDK build: not used, not a problem
                } catch (SecurityException | LinkageError e) {
                    failures++;
                    System.out.println("agent8 check: FAIL " + p.getName() + " " + s.getType() + "." + s.getAlgorithm() + ": " + e);
                }
            }
        }
        String[][] steps = {{"KeyStore", "JKS"}, {"KeyStore", KeyStore.getDefaultType()}, {"MessageDigest", "SHA-256"}, {"SSLContext", "Default"}};
        for (String[] step : steps) {
            try {
                String provider;
                if (step[0].equals("KeyStore")) {
                    provider = KeyStore.getInstance(step[1]).getProvider().getName();
                } else if (step[0].equals("MessageDigest")) {
                    provider = MessageDigest.getInstance(step[1]).getProvider().getName();
                } else {
                    provider = SSLContext.getDefault().getProvider().getName();
                }
                System.out.println("agent8 check: ok   " + step[0] + "." + step[1] + " (" + provider + ")");
            } catch (Exception | LinkageError e) {
                failures++;
                System.out.println("agent8 check: FAIL " + step[0] + "." + step[1] + ": " + e);
                for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                    System.out.println("agent8 check:      caused by " + c);
                }
            }
        }
        System.out.println("agent8 check: " + (failures == 0 ? "ok" : failures + " failure(s)") + " on "
                + System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
        return failures == 0 ? 0 : 1;
    }

    /** Reads the remoting version of an agent.jar; works on Java 8 although its classes are newer. */
    static String remotingVersion(File jar) throws IOException {
        try (JarFile j = new JarFile(jar)) {
            String v = j.getManifest().getMainAttributes().getValue("Version");
            if (v == null) {
                throw new IOException("no remoting version in " + jar);
            }
            return v;
        }
    }
}
