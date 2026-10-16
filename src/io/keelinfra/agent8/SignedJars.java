package io.keelinfra.agent8;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Stops JvmDowngrader from dropping the signers of every class loaded after it starts.
 *
 * <p>JvmDowngrader rewrites {@code java.security.CodeSource} so that its constructors ignore the
 * certificates. Classes of a signed jar loaded before that keep their signers, the ones loaded
 * after get none, and the JVM refuses the second kind in the same package: "class ... signer
 * information does not match signer information of other classes in the same package". This
 * happens on Solaris, where the first security provider is the signed SunPKCS11 / OracleUcrypto
 * jar: JvmDowngrader computes a SHA-1 (loading part of the provider) before it rewrites
 * {@code CodeSource}, and the next digest, cipher or TLS handshake loads the rest.
 *
 * <p>agent8 does not need the rewrite: its jar and the classes the controller sends are not signed.
 * So {@code ClassDowngradingAgent.retransformCodeSource} is changed to return the class unchanged.
 */
final class SignedJars {

    private static final String AGENT = "xyz/wagyourtail/jvmdg/runtime/ClassDowngradingAgent";
    private static final String METHOD = "retransformCodeSource";
    private static final String DESC = "([B)[B";

    private SignedJars() {}

    /** True for JvmDowngrader's Java agent class, also when shaded under another package. */
    static boolean applies(String name) {
        return name != null && name.endsWith(AGENT);
    }

    /** @return the class with {@code retransformCodeSource(bytes)} returning {@code bytes} */
    static byte[] patch(byte[] bytes) {
        ClassReader r = new ClassReader(bytes);
        ClassWriter w = new ClassWriter(0);
        final boolean[] found = {false};
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                if (!name.equals(METHOD) || !desc.equals(DESC) || (access & Opcodes.ACC_STATIC) != 0) {
                    return mv;
                }
                found[0] = true;
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 2);
                mv.visitEnd();
                return null; // drop the original code
            }
        }, 0);
        if (!found[0]) {
            throw new IllegalStateException("no " + METHOD + DESC + " in " + r.getClassName());
        }
        return w.toByteArray();
    }
}
