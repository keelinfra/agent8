package io.jenkins.agent8;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Entry point of agent8.jar, so it can replace agent.jar without changing how
 * the agent is started: {@code java -jar agent8.jar -url ... -secret ...}.
 *
 * <p>The Java agents must be active before any remoting class loads. When the
 * JVM was not started with them, this starts the same Java again with
 * {@code -javaagent:agent8.jar -javaagent:jvmdg.jar} and the same JVM options,
 * and waits for it. Otherwise it runs remoting's launcher directly.
 */
public final class Main {

    static final String CHILD = "agent8.child";

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--remoting-version")) {
            System.out.println(remotingVersion(new File(args[1])));
            return;
        }
        if (Agent8.active && jvmdgActive()) {
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

    private static int relaunch(String[] args) throws IOException, InterruptedException {
        File self = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
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
