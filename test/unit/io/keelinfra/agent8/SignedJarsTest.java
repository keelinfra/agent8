package io.keelinfra.agent8;

import static io.keelinfra.agent8.Check.contains;
import static io.keelinfra.agent8.Check.eq;
import static io.keelinfra.agent8.Check.isTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class SignedJarsTest {

    static final String AGENT = "xyz/wagyourtail/jvmdg/runtime/ClassDowngradingAgent";

    /**
     * JvmDowngrader's Java agent, reduced to what SignedJars looks at. Its retransformCodeSource
     * throws, standing in for the rewrite of CodeSource that drops signers.
     *
     * @param instance whether retransformCodeSource([B)[B is an instance method, as in JvmDowngrader
     */
    static byte[] agent(boolean instance) {
        ClassWriter w = Check.begin(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, AGENT, "java/lang/Object");
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC | (instance ? 0 : Opcodes.ACC_STATIC),
                "retransformCodeSource", "([B)[B", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("rewrote CodeSource");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // An overload and another method, which must stay as they are.
        mv = w.visitMethod(Opcodes.ACC_PUBLIC, "retransformCodeSource", "([BI)[B", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = w.visitMethod(Opcodes.ACC_PUBLIC, "other", "()I", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.BIPUSH, 5);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    static void testApplies() {
        isTrue("JvmDowngrader's agent", SignedJars.applies(AGENT));
        isTrue("shaded into agent8", SignedJars.applies("io/keelinfra/agent8/jvmdg/" + AGENT));
        isTrue("other class", !SignedJars.applies("xyz/wagyourtail/jvmdg/runtime/Bootstrap"));
        isTrue("similar name", !SignedJars.applies(AGENT + "$1"));
        isTrue("no name (lambda or hidden class)", !SignedJars.applies(null));
    }

    static void testRetransformCodeSourceReturnsItsArgument() throws Exception {
        Class<?> before = Check.load(AGENT.replace('/', '.'), agent(true));
        final Method m = before.getMethod("retransformCodeSource", byte[].class);
        final Object o = before.getConstructor().newInstance();
        InvocationTargetException e = Check.fails(InvocationTargetException.class, () -> m.invoke(o, new byte[] {1}));
        eq("unpatched", "rewrote CodeSource", e.getCause().getMessage());

        Class<?> after = Check.load(AGENT.replace('/', '.'), SignedJars.patch(agent(true)));
        Object a = after.getConstructor().newInstance();
        byte[] in = {(byte) 0xCA, (byte) 0xFE};
        isTrue("returns the same bytes", after.getMethod("retransformCodeSource", byte[].class).invoke(a, (Object) in) == in);
        eq("overload unchanged", null, after.getMethod("retransformCodeSource", byte[].class, int.class).invoke(a, in, 1));
        eq("other method unchanged", 5, after.getMethod("other").invoke(a));
    }

    static void testFailsWhenTheMethodIsMissing() {
        IllegalStateException e = Check.fails(IllegalStateException.class, () -> SignedJars.patch(agent(false)));
        contains("message", e.getMessage(), "no retransformCodeSource([B)[B in " + AGENT);
    }
}
