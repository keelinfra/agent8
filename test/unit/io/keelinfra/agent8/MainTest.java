package io.keelinfra.agent8;

import static io.keelinfra.agent8.Check.contains;
import static io.keelinfra.agent8.Check.eq;
import static io.keelinfra.agent8.Check.isTrue;
import static io.keelinfra.agent8.Check.lacks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.net.ssl.SSLContext;

final class MainTest {

    /** The jars test/unit.sh builds: agent8.jar (agent8 with a stand-in remoting launcher) and a do-nothing jvmdg.jar. */
    static File fixture(String name) {
        File f = new File(System.getProperty("unit.fixtures", "target/unit/fixtures"), name);
        isTrue("missing " + f + ": run test/unit.sh", f.isFile());
        return f;
    }

    static File jar(File dir, String... manifest) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (int i = 0; i < manifest.length; i += 2) {
            m.getMainAttributes().putValue(manifest[i], manifest[i + 1]);
        }
        File f = new File(dir, "agent.jar");
        new JarOutputStream(new FileOutputStream(f), m).close();
        return f;
    }

    static void testRemotingVersion() throws Exception {
        final File agent = jar(Check.tempDir("version"), "Version", "3309.v27b_9314fd1a_4");
        String out = Check.stdout(() -> Main.main(new String[] {"--remoting-version", agent.getPath()}));
        eq("printed", "3309.v27b_9314fd1a_4" + System.lineSeparator(), out);
    }

    static void testRemotingVersionMissing() throws Exception {
        final File other = jar(Check.tempDir("no-version"), "Main-Class", "Other");
        IOException e = Check.fails(IOException.class, () -> Main.remotingVersion(other));
        eq("message", "no remoting version in " + other, e.getMessage());
    }

    static void testChildWithoutJavaAgentsStops() throws Exception {
        String old = System.getProperty(Main.CHILD);
        System.setProperty(Main.CHILD, "true");
        try {
            for (boolean agent8 : new boolean[] {false, true}) { // this JVM has no jvmdg Java agent either way
                Agent8.active = agent8;
                IllegalStateException e = Check.fails(IllegalStateException.class, () -> Main.main(new String[] {"-url", "x"}));
                eq("message", "agent8: Java agents did not start", e.getMessage());
            }
        } finally {
            Agent8.active = false;
            if (old == null) {
                System.clearProperty(Main.CHILD);
            } else {
                System.setProperty(Main.CHILD, old);
            }
        }
    }

    static void testFindsJvmdg() throws Exception {
        isTrue("run with AGENT8_JVMDG unset", System.getenv("AGENT8_JVMDG") == null);
        File dir = Check.tempDir("lib");
        final File self = new File(dir, "agent8.jar");
        File next = new File(dir, "jvmdg.jar");
        Files.createFile(next.toPath());
        eq("next to agent8.jar", next.getAbsoluteFile(), Main.jvmdg(self));

        File elsewhere = new File(Check.tempDir("opt"), "jvmdg-2.0.1.jar");
        Files.createFile(elsewhere.toPath());
        System.setProperty("agent8.jvmdg", elsewhere.getPath());
        try {
            eq("-Dagent8.jvmdg", elsewhere, Main.jvmdg(self));
            System.setProperty("agent8.jvmdg", dir.getPath());
            IllegalStateException e = Check.fails(IllegalStateException.class, () -> Main.jvmdg(self));
            eq("not a file", "agent8: cannot find " + dir + "; put jvmdg.jar next to agent8.jar or set -Dagent8.jvmdg=/path/to/jvmdg.jar",
                    e.getMessage());
        } finally {
            System.clearProperty("agent8.jvmdg");
        }

        Files.delete(next.toPath());
        IllegalStateException e = Check.fails(IllegalStateException.class, () -> Main.jvmdg(self));
        contains("missing", e.getMessage(), "agent8: cannot find " + next.getAbsoluteFile() + "; put jvmdg.jar next to agent8.jar");
    }

    static void testCheckPassesOnThisJvm() throws Exception {
        final int[] status = {-1};
        String out = Check.stdout(() -> status[0] = Main.check());
        eq("exit code", 0, status[0]);
        lacks("output", out, "FAIL");
        contains("output", out, "agent8 check: ok   KeyStore.JKS (");
        contains("output", out, "agent8 check: ok   MessageDigest.SHA-256 (");
        contains("output", out, "agent8 check: ok   SSLContext.Default (");
        contains("output", out, "agent8 check: ok on " + System.getProperty("java.vm.name"));
    }

    /** A provider class that cannot load, like a signed provider class after a Java agent broke its jar. */
    static final class BrokenInit {
        static {
            fail();
        }

        private static void fail() {
            throw new SecurityException("signer information does not match");
        }
    }

    static void testCheckReportsWhatFails() throws Exception {
        SSLContext.getDefault(); // the default TLS context reads keystore.type once; keep it out of this test
        Provider p = new Provider("Agent8Broken", 1.0, "unit test") {};
        p.put("MessageDigest.Broken", BrokenInit.class.getName());
        p.put("MessageDigest.Gone", "io.keelinfra.agent8.NotInThisJdkBuild");
        String keyStoreType = Security.getProperty("keystore.type");
        final int[] status = {-1};
        String out;
        Security.insertProviderAt(p, 1);
        Security.setProperty("keystore.type", "agent8-none");
        try {
            out = Check.stdout(() -> status[0] = Main.check());
        } finally {
            Security.removeProvider(p.getName());
            Security.setProperty("keystore.type", keyStoreType);
        }
        eq("exit code", 1, status[0]);
        contains("output", out, "agent8 check: FAIL Agent8Broken MessageDigest.Broken: java.lang.ExceptionInInitializerError");
        lacks("a provider class missing from the JDK build is fine", out, "Gone");
        contains("output", out, "agent8 check: ok   KeyStore.JKS (");
        contains("output", out, "agent8 check: FAIL KeyStore.agent8-none: java.security.KeyStoreException");
        contains("output", out, "agent8 check:      caused by java.security.NoSuchAlgorithmException");
        contains("output", out, "agent8 check: 2 failure(s) on ");
    }

    static final class Run {
        final int status;
        final String output;

        Run(int status, String output) {
            this.status = status;
            this.output = output;
        }
    }

    /** Runs this JVM's java in {@code dir}, as a user would start agent8. */
    static Run java(Map<String, String> env, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(System.getProperty("java.home"), "bin" + File.separator + "java").getPath());
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("AGENT8_JVMDG");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(Check.readAll(p.getInputStream()), "UTF-8");
        if (!p.waitFor(2, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new AssertionError("timed out: " + cmd + "\n" + out);
        }
        return new Run(p.exitValue(), out);
    }

    /** The -javaagent jars of the JVM that ran the launcher, other than a coverage tool's. */
    static List<String> javaAgents(String output) {
        List<String> agents = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.startsWith("launcher java agents:")) {
                for (String a : line.trim().split(" ")) {
                    if (a.equals("agent8.jar") || a.equals("jvmdg.jar")) {
                        agents.add(a);
                    }
                }
            }
        }
        return agents;
    }

    /** agent8.jar, and jvmdg.jar if {@code withJvmdg}, in a new directory whose name has a space. */
    static File install(boolean withJvmdg) throws IOException {
        File dir = Check.tempDir("agent dir");
        File agent8 = new File(dir, "agent8.jar");
        Files.copy(fixture("agent8.jar").toPath(), agent8.toPath());
        if (withJvmdg) {
            Files.copy(fixture("jvmdg.jar").toPath(), new File(dir, "jvmdg.jar").toPath());
        }
        return agent8;
    }

    static void testRestartsJavaWithBothAgents() throws Exception {
        File agent8 = install(true);
        Run r = java(java.util.Collections.<String, String>emptyMap(),
                "-Dlauncher.exit=3", "-jar", agent8.getPath(), "-url", "http://jenkins:8080/", "-name", "aix 1");
        // The stand-in launcher only runs once both Java agents are active, with the JVM options passed on.
        contains("launcher arguments", r.output, "launcher: -url http://jenkins:8080/ -name aix 1");
        eq("Java agents, agent8 before JvmDowngrader", Arrays.asList("agent8.jar", "jvmdg.jar"), javaAgents(r.output));
        lacks("no agent8 warnings (on Java 9 to 15: java.util.CollSer patched)", r.output, "[agent8]");
        eq("exit code of the agent JVM\n" + r.output, 3, r.status);
    }

    static void testCheckRunsWithBothAgents() throws Exception {
        Run r = java(java.util.Collections.<String, String>emptyMap(), "-jar", install(true).getPath(), "--check");
        contains("check output", r.output, "agent8 check: ok on ");
        lacks("check output", r.output, "FAIL");
        lacks("no agent8 warnings", r.output, "[agent8]");
        eq("exit code\n" + r.output, 0, r.status);
    }

    static void testJvmdgFromTheEnvironment() throws Exception {
        File jvmdg = new File(Check.tempDir("elsewhere"), "jvmdg.jar");
        Files.copy(fixture("jvmdg.jar").toPath(), jvmdg.toPath());
        // One argument other than --check goes to remoting too.
        Run r = java(java.util.Collections.singletonMap("AGENT8_JVMDG", jvmdg.getPath()), "-jar", install(false).getPath(), "-help");
        contains("launcher arguments", r.output, "launcher: -help");
        eq("exit code\n" + r.output, 0, r.status);
    }

    static void testRestartsWhenOnlyAgent8IsAJavaAgent() throws Exception {
        File agent8 = install(true);
        Run r = java(java.util.Collections.<String, String>emptyMap(), "-javaagent:" + agent8, "-jar", agent8.getPath(), "-url", "x");
        contains("launcher arguments", r.output, "launcher: -url x");
        eq("each Java agent once, agent8 first", Arrays.asList("agent8.jar", "jvmdg.jar"), javaAgents(r.output));
        eq("exit code\n" + r.output, 0, r.status);
    }

    static void testRestartedJavaWithoutJvmdgStops() throws Exception {
        // Should the restarted JVM come up without JvmDowngrader, it stops instead of starting yet another JVM.
        File agent8 = install(true);
        Run r = java(java.util.Collections.<String, String>emptyMap(), "-javaagent:" + agent8, "-D" + Main.CHILD + "=true",
                "-cp", agent8.getPath(), Main.class.getName(), "-url", "x");
        contains("error", r.output, "java.lang.IllegalStateException: agent8: Java agents did not start");
        lacks("launcher not started", r.output, "launcher:");
        eq("exit code", 1, r.status);
    }

    static void testFailsWithoutJvmdg() throws Exception {
        File agent8 = install(false);
        Run r = java(java.util.Collections.<String, String>emptyMap(), "-jar", agent8.getPath(), "-url", "x");
        contains("error", r.output, "agent8: cannot find " + new File(agent8.getParentFile(), "jvmdg.jar")
                + "; put jvmdg.jar next to agent8.jar or set -Dagent8.jvmdg=/path/to/jvmdg.jar");
        lacks("launcher not started", r.output, "launcher:");
        eq("exit code", 1, r.status);
    }
}
