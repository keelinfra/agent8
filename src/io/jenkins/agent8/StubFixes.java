package io.jenkins.agent8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Replaces JvmDowngrader stubs that behave differently from the JDK method they stand in for.
 *
 * <p>{@code InputStream.readNBytes(int)} (Java 11): JvmDowngrader allocates {@code new byte[len]}
 * up front, so the common {@code readNBytes(Integer.MAX_VALUE)} (JGit reading a config file, for
 * example) fails with "Requested array size exceeds VM limit", and at end of stream it returns null
 * instead of an empty array. The JDK reads in chunks; {@link #readNBytes} does the same.
 */
final class StubFixes {

    private static final String INPUT_STREAM_STUB = "jvmdg/j11/stub/java_base/J_I_InputStream";
    private static final String READ_N_BYTES = "readNBytes";
    private static final String READ_N_BYTES_DESC = "(Ljava/io/InputStream;I)[B";

    private StubFixes() {}

    /** True for the stub class, also when shaded into the agent jar under another package. */
    static boolean applies(String name) {
        return name != null && name.endsWith(INPUT_STREAM_STUB);
    }

    /** @return the stub class with {@code readNBytes(InputStream, int)} replaced by {@link #readNBytes} */
    static byte[] patch(byte[] bytes) throws IOException {
        final MethodNode fixed = template(READ_N_BYTES, READ_N_BYTES_DESC);
        ClassReader r = new ClassReader(bytes);
        ClassWriter w = new ClassWriter(0);
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                if (name.equals(READ_N_BYTES) && desc.equals(READ_N_BYTES_DESC)) {
                    return replaceCode(super.visitMethod(access, name, desc, sig, exc), fixed);
                }
                return super.visitMethod(access, name, desc, sig, exc);
            }
        }, 0);
        return w.toByteArray();
    }

    /** Keeps the method's annotations (JvmDowngrader finds its stubs by them), replaces its code. */
    private static MethodVisitor replaceCode(final MethodVisitor target, final MethodNode code) {
        return new MethodVisitor(Opcodes.ASM9, target) {
            @Override
            public void visitCode() {
                target.visitCode();
                for (org.objectweb.asm.tree.TryCatchBlockNode t : code.tryCatchBlocks) {
                    t.accept(target);
                }
                code.instructions.accept(target);
                target.visitMaxs(code.maxStack, code.maxLocals);
                mv = null; // drop the original code
            }

            @Override
            public void visitEnd() {
                target.visitEnd();
            }
        };
    }

    private static MethodNode template(String name, String desc) throws IOException {
        ClassNode c = new ClassNode();
        try (InputStream in = StubFixes.class.getResourceAsStream("StubFixes.class")) {
            new ClassReader(in).accept(c, 0);
        }
        for (MethodNode m : c.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        throw new IllegalStateException("no template for " + name + desc);
    }

    /** Template copied into the stub: Java 11's InputStream.readNBytes(int), reading in chunks. */
    static byte[] readNBytes(InputStream in, int len) throws IOException {
        if (len < 0) {
            throw new IllegalArgumentException("len < 0");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(len, 8192));
        byte[] buf = new byte[Math.min(len, 8192)];
        int remaining = len;
        while (remaining > 0) {
            int n = in.read(buf, 0, Math.min(remaining, buf.length));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }
}
