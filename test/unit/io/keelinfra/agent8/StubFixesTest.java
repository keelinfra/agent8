package io.keelinfra.agent8;

import static io.keelinfra.agent8.Check.eq;
import static io.keelinfra.agent8.Check.isTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

final class StubFixesTest {

    static final String STUB = "jvmdg/j11/stub/java_base/J_I_InputStream";
    static final String DESC = "(Ljava/io/InputStream;I)[B";
    static final String STUB_ANNOTATION = "Lxyz/wagyourtail/jvmdg/version/Stub;";

    /** JvmDowngrader's InputStream stub, reduced: readNBytes allocates the full length up front. */
    static byte[] stub() {
        ClassWriter w = Check.begin(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, STUB, "java/lang/Object");
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "readNBytes", DESC, null,
                new String[] {"java/io/IOException"});
        AnnotationVisitor av = mv.visitAnnotation(STUB_ANNOTATION, true);
        av.visit("ref", "java/io/InputStream");
        av.visitEnd();
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // The Java 9 overload, which must stay as it is.
        mv = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "readNBytes", "(Ljava/io/InputStream;[BII)I", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.BIPUSH, 7);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "transferTo", "(Ljava/io/InputStream;Ljava/io/OutputStream;)J", null, null);
        mv.visitCode();
        mv.visitLdcInsn(99L);
        mv.visitInsn(Opcodes.LRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    static InputStream in(String s) throws IOException {
        return new ByteArrayInputStream(s.getBytes("UTF-8"));
    }

    /** A stream that returns at most one byte per read, like a slow socket. */
    static InputStream trickle(final byte[] data) {
        return new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
    }

    static void testApplies() {
        isTrue("stub", StubFixes.applies(STUB));
        isTrue("shaded into agent8", StubFixes.applies("io/keelinfra/agent8/jvmdg/" + STUB));
        isTrue("other stub", !StubFixes.applies("jvmdg/j11/stub/java_base/J_L_String"));
        isTrue("no name", !StubFixes.applies(null));
    }

    static void testReadNBytesBehavesLikeTheJdk() throws Exception {
        eq("all", "hello", new String(StubFixes.readNBytes(in("hello"), Integer.MAX_VALUE), "UTF-8"));
        eq("part", "abc", new String(StubFixes.readNBytes(in("abcdef"), 3), "UTF-8"));
        eq("end of stream: empty, not null", 0, StubFixes.readNBytes(in(""), 10).length);
        eq("zero", 0, StubFixes.readNBytes(in("abc"), 0).length);
        byte[] big = new byte[20000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        isTrue("short reads, more than one buffer", Arrays.equals(big, StubFixes.readNBytes(trickle(big), Integer.MAX_VALUE)));
        IllegalArgumentException e = Check.fails(IllegalArgumentException.class, () -> StubFixes.readNBytes(in("x"), -1));
        eq("negative length", "len < 0", e.getMessage());
    }

    static void testPatchReplacesTheStubCode() throws Exception {
        byte[] patched = StubFixes.patch(stub());
        MethodNode m = Check.method(Check.node(patched), "readNBytes", DESC);
        eq("annotation kept (JvmDowngrader finds stubs by it)", STUB_ANNOTATION, m.visibleAnnotations.get(0).desc);
        eq("exceptions kept", Arrays.asList("java/io/IOException"), m.exceptions);

        Class<?> c = Check.load(STUB.replace('/', '.'), patched);
        Method read = c.getMethod("readNBytes", InputStream.class, int.class);
        eq("all", "hello", new String((byte[]) read.invoke(null, in("hello"), Integer.MAX_VALUE), "UTF-8"));
        eq("part", "abc", new String((byte[]) read.invoke(null, in("abcdef"), 3), "UTF-8"));
        eq("end of stream", 0, ((byte[]) read.invoke(null, in(""), 10)).length);
        InvocationTargetException e = Check.fails(InvocationTargetException.class, () -> read.invoke(null, in("x"), -1));
        isTrue("negative length", e.getCause() instanceof IllegalArgumentException);
        eq("readNBytes overload unchanged", 7, c.getMethod("readNBytes", InputStream.class, byte[].class, int.class, int.class)
                .invoke(null, null, null, 0, 0));
        eq("other stub unchanged", 99L, c.getMethod("transferTo", InputStream.class, java.io.OutputStream.class).invoke(null, null, null));
    }
}
