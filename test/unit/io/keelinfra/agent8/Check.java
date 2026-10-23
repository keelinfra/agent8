package io.keelinfra.agent8;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Assertions and helpers for the unit tests (no test framework, like the rest of test/). */
final class Check {

    private Check() {}

    interface Code {
        void run() throws Exception;
    }

    static void eq(String what, Object want, Object got) {
        if (!Objects.equals(want, got)) {
            throw new AssertionError(what + ": expected <" + want + "> but got <" + got + ">");
        }
    }

    static void isTrue(String what, boolean ok) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    static void contains(String what, String text, String part) {
        if (text == null || !text.contains(part)) {
            throw new AssertionError(what + ": <" + part + "> not in <" + text + ">");
        }
    }

    static void lacks(String what, String text, String part) {
        if (text.contains(part)) {
            throw new AssertionError(what + ": unexpected <" + part + "> in <" + text + ">");
        }
    }

    /** @return what {@code code} threw, which must be a {@code type} */
    static <T extends Throwable> T fails(Class<T> type, Code code) {
        try {
            code.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw new AssertionError("expected " + type.getName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getName() + " but nothing was thrown");
    }

    /** @return what {@code code} wrote to System.err */
    static String stderr(Code code) throws Exception {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, "UTF-8"));
        try {
            code.run();
        } finally {
            System.setErr(old);
        }
        return text(buf);
    }

    /** @return what {@code code} wrote to System.out */
    static String stdout(Code code) throws Exception {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, "UTF-8"));
        try {
            code.run();
        } finally {
            System.setOut(old);
        }
        return text(buf);
    }

    private static String text(ByteArrayOutputStream buf) throws UnsupportedEncodingException {
        return buf.toString("UTF-8");
    }

    /** Defines a class from bytes in a fresh class loader, which verifies it. */
    static Class<?> load(String name, final byte[] bytes) throws ClassNotFoundException {
        ClassLoader loader = new ClassLoader(Check.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (!n.equals(name)) {
                    throw new ClassNotFoundException(n);
                }
                return defineClass(n, bytes, 0, bytes.length);
            }
        };
        return Class.forName(name, true, loader);
    }

    static ClassNode node(byte[] bytes) {
        ClassNode c = new ClassNode();
        new ClassReader(bytes).accept(c, 0);
        return c;
    }

    static FieldNode field(ClassNode c, String name) {
        FieldNode found = null;
        for (FieldNode f : c.fields) {
            if (f.name.equals(name)) {
                isTrue("one field " + name + " in " + c.name, found == null);
                found = f;
            }
        }
        return found;
    }

    static MethodNode method(ClassNode c, String name, String desc) {
        for (MethodNode m : c.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    /** A class writer for a class with a public no-argument constructor. */
    static ClassWriter begin(int version, int access, String name, String superName, String... interfaces) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(version, access, name, null, superName, interfaces.length == 0 ? null : interfaces);
        if ((access & Opcodes.ACC_INTERFACE) == 0) {
            MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        return w;
    }

    /** A Java agent's Instrumentation, answered by {@code h}. */
    static Instrumentation instrumentation(InvocationHandler h) {
        return (Instrumentation) Proxy.newProxyInstance(Check.class.getClassLoader(), new Class<?>[] {Instrumentation.class}, h);
    }

    /** The default return value of a method returning {@code type}. */
    static Object none(Class<?> type) {
        return type == boolean.class ? Boolean.FALSE : null;
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** The class file of a JDK class, such as {@code java/util/CollSer}. */
    static byte[] jdkClass(String name) throws IOException {
        InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class");
        isTrue("this JDK has " + name, in != null);
        return readAll(in);
    }

    static File tempDir(String name) throws IOException {
        File d = Files.createTempDirectory("agent8-unit").resolve(name).toFile();
        isTrue("mkdir " + d, d.mkdirs());
        return d;
    }
}
