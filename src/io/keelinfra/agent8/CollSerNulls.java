package io.keelinfra.agent8;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Teaches Java 9 to 15 the serial form of {@code Stream.toList()}.
 *
 * <p>Java 16 added a fourth {@code java.util.CollSer} tag for immutable lists that allow nulls.
 * Older {@code CollSer} rejects it ({@code InvalidObjectException: invalid flags 0x4}), so a
 * controller on Java 17+ could not send such a list to an agent on Java 11. This transformer
 * adds that case at the start of {@code CollSer.readResolve}. Java 8 gets the whole class from
 * the compat jar instead.
 */
final class CollSerNulls implements ClassFileTransformer {

    static final String NAME = "java/util/CollSer";
    static final int IMM_LIST_NULLS = 4;

    private volatile boolean done;

    static boolean needed() {
        return Agent8.RUNTIME_CLASS_VERSION >= 53 && Agent8.RUNTIME_CLASS_VERSION < 60; // Java 9 to 15
    }

    static void install(Instrumentation inst) {
        CollSerNulls t = new CollSerNulls();
        inst.addTransformer(t, true);
        try {
            Class<?> c = Class.forName("java.util.CollSer");
            if (!t.done && inst.isRetransformClassesSupported()) {
                inst.retransformClasses(c); // it was loaded before this agent started
            }
        } catch (Exception | LinkageError e) {
            System.err.println("[agent8] cannot patch java.util.CollSer: " + e);
        }
        if (!t.done) {
            System.err.println("[agent8] java.util.CollSer not patched: lists from Stream.toList() will not deserialize");
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (loader != null || !NAME.equals(name)) {
            return null;
        }
        try {
            ClassReader r = new ClassReader(bytes);
            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            r.accept(new ClassVisitor(Opcodes.ASM9, w) {
                @Override
                public MethodVisitor visitMethod(int access, String m, String desc, String sig, String[] exc) {
                    MethodVisitor mv = super.visitMethod(access, m, desc, sig, exc);
                    if (!"readResolve".equals(m) || !"()Ljava/lang/Object;".equals(desc)) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // if ((tag & 0xff) == 4 && array != null)
                            //     return Collections.unmodifiableList(Arrays.asList(array));
                            Label other = new Label();
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitFieldInsn(Opcodes.GETFIELD, NAME, "tag", "I");
                            visitIntInsn(Opcodes.SIPUSH, 0xff);
                            visitInsn(Opcodes.IAND);
                            visitIntInsn(Opcodes.BIPUSH, IMM_LIST_NULLS);
                            visitJumpInsn(Opcodes.IF_ICMPNE, other);
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitFieldInsn(Opcodes.GETFIELD, NAME, "array", "[Ljava/lang/Object;");
                            visitJumpInsn(Opcodes.IFNULL, other);
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitFieldInsn(Opcodes.GETFIELD, NAME, "array", "[Ljava/lang/Object;");
                            visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList",
                                    "([Ljava/lang/Object;)Ljava/util/List;", false);
                            visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "unmodifiableList",
                                    "(Ljava/util/List;)Ljava/util/List;", false);
                            visitInsn(Opcodes.ARETURN);
                            visitLabel(other);
                            visitFrame(Opcodes.F_NEW, 1, new Object[] {NAME}, 0, new Object[0]);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            done = true;
            return w.toByteArray();
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[agent8] cannot patch java.util.CollSer: " + e);
            return null;
        }
    }
}
