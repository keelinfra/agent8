package io.jenkins.agent8;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.SerialVersionUIDAdder;

/**
 * Java agent that makes classes sent by a newer controller usable on Java 8.
 *
 * <p>It must come before the JvmDowngrader agent on the command line:
 * <ol>
 * <li>Downgrading adds synthetic members, which changes the default
 * {@code serialVersionUID} of every {@code Serializable} class that does not
 * declare one. This agent pins the UID the controller computes, before the
 * bytecode is downgraded. Records always use UID 0.</li>
 * <li>JDK classes that only exist in newer Java but appear in serialized data
 * ({@code java.util.CollSer}) are added to the bootstrap class path.</li>
 * </ol>
 */
public final class Agent8 implements ClassFileTransformer {

    static final String COMPAT = "agent8-compat/";

    /** Whether this Java agent was started, see {@link Main}. */
    static volatile boolean active;

    public static void premain(String args, Instrumentation inst) throws IOException {
        inst.appendToBootstrapClassLoaderSearch(new JarFile(compatJar()));
        inst.addTransformer(new Agent8());
        active = true;
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (bytes == null || bytes.length < 8 || majorVersion(bytes) <= RUNTIME_CLASS_VERSION) {
            return null; // JvmDowngrader leaves it alone
        }
        try {
            return pinSerialVersionUID(bytes);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[agent8] cannot pin serialVersionUID of " + name + ": " + e);
            return null;
        }
    }

    /** Class file version of this JVM: 52 on Java 8, 55 on Java 11. */
    static final int RUNTIME_CLASS_VERSION = runtimeClassVersion(System.getProperty("java.specification.version"));

    static int runtimeClassVersion(String spec) {
        return 44 + Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec);
    }

    static int majorVersion(byte[] bytes) {
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    /** @return the class with an explicit serialVersionUID, or null if unchanged */
    static byte[] pinSerialVersionUID(byte[] bytes) {
        ClassReader r = new ClassReader(bytes);
        if ((r.getAccess() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM)) != 0) {
            return null;
        }
        ClassWriter w = new ClassWriter(0);
        if ("java/lang/Record".equals(r.getSuperName())) {
            r.accept(new ClassVisitor(Opcodes.ASM9, w) {
                @Override
                public void visitEnd() {
                    visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                    "serialVersionUID", "J", null, 0L)
                            .visitEnd();
                    super.visitEnd();
                }
            }, 0);
        } else {
            r.accept(new SerialVersionUIDAdder(Opcodes.ASM9, w) {}, 0);
        }
        return w.toByteArray();
    }

    /** JDK classes newer JDKs or other JVMs have, which this JVM may lack. */
    static final String[] COMPAT_CLASSES = {"java/util/CollSer", "java/lang/ClassLoader$ParallelLoaders"};

    /** Copies the compat classes this JVM lacks out of this jar, since the bootstrap path needs a jar file. */
    private static File compatJar() throws IOException {
        File jar = File.createTempFile("agent8-compat", ".jar");
        jar.deleteOnExit();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar))) {
            for (String name : COMPAT_CLASSES) {
                if (ClassLoader.getSystemResource(name + ".class") != null) {
                    continue; // the JVM has its own
                }
                String cls = name + ".class";
                try (InputStream in = Agent8.class.getClassLoader().getResourceAsStream(COMPAT + cls)) {
                    if (in == null) {
                        throw new IOException("missing " + COMPAT + cls);
                    }
                    out.putNextEntry(new ZipEntry(cls));
                    out.write(readAll(in));
                    out.closeEntry();
                }
            }
        }
        return jar;
    }

    /** Build step: pins serialVersionUIDs in every class of a jar. Usage: Agent8 in.jar out.jar */
    public static void main(String[] args) throws IOException {
        try (ZipFile in = new ZipFile(args[0]);
                ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            for (Enumeration<? extends ZipEntry> e = in.entries(); e.hasMoreElements(); ) {
                ZipEntry z = e.nextElement();
                byte[] b;
                try (InputStream s = in.getInputStream(z)) {
                    b = readAll(s);
                }
                if (z.getName().endsWith(".class") && !z.getName().startsWith("META-INF/") && majorVersion(b) > 52) {
                    byte[] pinned = pinSerialVersionUID(b);
                    if (pinned != null) {
                        b = pinned;
                    }
                }
                out.putNextEntry(new ZipEntry(z.getName()));
                out.write(b);
                out.closeEntry();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            o.write(buf, 0, n);
        }
        return o.toByteArray();
    }
}
