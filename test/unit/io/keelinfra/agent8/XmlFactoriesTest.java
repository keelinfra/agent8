package io.keelinfra.agent8;

import static io.keelinfra.agent8.Check.eq;
import static io.keelinfra.agent8.Check.isTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class XmlFactoriesTest {

    static final String RESULT = "agent8test/TestResult";
    static final String FACTORY_DESC = "L" + XmlFactories.FACTORY + ";";

    /**
     * Like junit's TestResult: make() builds a factory with newDefaultFactory() and sets
     * {@code property} on it; other() calls an XMLInputFactory method that must stay as it is.
     */
    static byte[] testResult(int version, String property) {
        ClassWriter w = Check.begin(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, RESULT, "java/lang/Object");
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()" + FACTORY_DESC, null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, XmlFactories.FACTORY, "newDefaultFactory", XmlFactories.NEW_DEFAULT_FACTORY_DESC, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(XMLInputFactory.SUPPORT_DTD);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, XmlFactories.FACTORY, "setProperty", XmlFactories.SET_PROPERTY_DESC, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(property);
        mv.visitLdcInsn("0");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, XmlFactories.FACTORY, "setProperty", XmlFactories.SET_PROPERTY_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "other", "()" + FACTORY_DESC, null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, XmlFactories.FACTORY, "newFactory", "()" + FACTORY_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** "owner.name" of each method call in {@code method} of the class. */
    static List<String> calls(byte[] bytes, String method) {
        MethodNode m = Check.method(Check.node(bytes), method, "()" + FACTORY_DESC);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof MethodInsnNode) {
                calls.add(((MethodInsnNode) i).owner + "." + ((MethodInsnNode) i).name);
            }
        }
        return calls;
    }

    static void testApplies() {
        isTrue("refers to XMLInputFactory", XmlFactories.applies(testResult(Opcodes.V1_8, "jdk.xml.totalEntitySizeLimit")));
        isTrue("does not", !XmlFactories.applies(Agent8Test.data(Opcodes.V17)));
        isTrue("no bytes", !XmlFactories.applies(null));
    }

    static void testJava8ReplacesBothCalls() {
        byte[] patched = XmlFactories.patch(testResult(Opcodes.V1_8, "jdk.xml.totalEntitySizeLimit"), true);
        eq("make()", "[" + XmlFactories.SELF + ".newDefaultFactory, " + XmlFactories.SELF + ".setProperty, "
                + XmlFactories.SELF + ".setProperty]", calls(patched, "make").toString());
        eq("other() unchanged", "[" + XmlFactories.FACTORY + ".newFactory]", calls(patched, "other").toString());
    }

    static void testJava11KeepsNewDefaultFactory() {
        byte[] patched = XmlFactories.patch(testResult(Opcodes.V1_8, "jdk.xml.totalEntitySizeLimit"), false);
        eq("make()", "[" + XmlFactories.FACTORY + ".newDefaultFactory, " + XmlFactories.SELF + ".setProperty, "
                + XmlFactories.SELF + ".setProperty]", calls(patched, "make").toString());
    }

    static void testNothingToReplace() {
        ClassWriter w = Check.begin(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, RESULT, "java/lang/Object");
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "other", "()" + FACTORY_DESC, null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, XmlFactories.FACTORY, "newFactory", "()" + FACTORY_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        w.visitEnd();
        eq("unchanged", null, XmlFactories.patch(w.toByteArray(), true));
    }

    static void testPatchedClassRuns() throws Exception {
        // The JDK limit properties: accepted on Java 17+, skipped where the parser rejects them.
        for (String property : new String[] {"jdk.xml.maxGeneralEntitySizeLimit", "jdk.xml.totalEntitySizeLimit"}) {
            Class<?> c = Check.load(RESULT.replace('/', '.'), XmlFactories.patch(testResult(Opcodes.V1_8, property), true));
            XMLInputFactory f = (XMLInputFactory) c.getMethod("make").invoke(null);
            eq(property + ": DTDs still off", Boolean.FALSE, f.getProperty(XMLInputFactory.SUPPORT_DTD));
        }
        // Any other property the parser rejects still fails, as without agent8.
        Class<?> c = Check.load(RESULT.replace('/', '.'), XmlFactories.patch(testResult(Opcodes.V1_8, "agent8.no.such.property"), true));
        InvocationTargetException e = Check.fails(InvocationTargetException.class, () -> c.getMethod("make").invoke(null));
        isTrue("unknown property", e.getCause() instanceof IllegalArgumentException);
    }

    static void testNewDefaultFactory() {
        isTrue("a factory", XmlFactories.newDefaultFactory() != null);
    }

    static void testTransformReplacesCallsInNewerClasses() {
        byte[] newer = testResult(Agent8.RUNTIME_CLASS_VERSION + 1, "jdk.xml.totalEntitySizeLimit");
        byte[] out = new Agent8().transform(null, RESULT, null, null, newer);
        isTrue("transformed", out != null);
        eq("make()", XmlFactories.SELF + ".setProperty", calls(out, "make").get(1));
        eq("same-version class left alone", null,
                new Agent8().transform(null, RESULT, null, null, testResult(Agent8.RUNTIME_CLASS_VERSION, "jdk.xml.totalEntitySizeLimit")));
    }
}
