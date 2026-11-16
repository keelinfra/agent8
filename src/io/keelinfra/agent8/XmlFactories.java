package io.keelinfra.agent8;

import java.nio.charset.StandardCharsets;
import javax.xml.stream.XMLInputFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * StAX factory calls in controller classes that the agent's older Java cannot run.
 *
 * <p>The junit plugin (1462 and the 1458.1460 backport, jenkinsci/junit-plugin#1295) sets up an
 * {@code XMLInputFactory} in the static initializer of {@code hudson.tasks.junit.TestResult}, which
 * also runs on the agent when the {@code junit} step parses test reports there:
 * <ul>
 * <li>{@code XMLInputFactory.newDefaultFactory()} is Java 9 API that JvmDowngrader has no stub for:
 * {@code NoSuchMethodError} on Java 8. {@link #newDefaultFactory} returns the JDK's parser, as Java 9 does.</li>
 * <li>{@code setProperty("jdk.xml.maxGeneralEntitySizeLimit", "0")} and {@code jdk.xml.totalEntitySizeLimit}:
 * the JDK parser of Java 8 and 11 rejects them with {@code IllegalArgumentException}.
 * {@link #setProperty} skips {@code jdk.xml.*} properties the parser does not support, so the JVM's own
 * limits stay in place.</li>
 * </ul>
 * {@link #patch} points those calls at this class in classes the controller sends.
 */
public final class XmlFactories {

    static final String FACTORY = "javax/xml/stream/XMLInputFactory";
    static final String SELF = "io/keelinfra/agent8/XmlFactories";
    static final String NEW_DEFAULT_FACTORY_DESC = "()L" + FACTORY + ";";
    static final String SET_PROPERTY_DESC = "(Ljava/lang/String;Ljava/lang/Object;)V";
    static final String STATIC_SET_PROPERTY_DESC = "(L" + FACTORY + ";Ljava/lang/String;Ljava/lang/Object;)V";

    /** The JDK's StAX parser, which Java 9's newDefaultFactory() returns. */
    static final String JDK_FACTORY = "com.sun.xml.internal.stream.XMLInputFactoryImpl";

    private static final byte[] FACTORY_BYTES = FACTORY.getBytes(StandardCharsets.UTF_8);

    private XmlFactories() {}

    /** True if the class refers to {@code XMLInputFactory} at all, a cheap check before parsing it. */
    static boolean applies(byte[] bytes) {
        return bytes != null && indexOf(bytes, FACTORY_BYTES) >= 0;
    }

    /**
     * @param java8 also replace {@code newDefaultFactory()}, which Java 8 lacks
     * @return the class with the calls replaced, or null if it has none
     */
    static byte[] patch(byte[] bytes, final boolean java8) {
        ClassReader r = new ClassReader(bytes);
        ClassWriter w = new ClassWriter(0); // same stack effect, so maxs and frames stay valid
        final boolean[] changed = {false};
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, exc)) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String m, String d, boolean itf) {
                        if (FACTORY.equals(owner) && !itf) {
                            if (java8 && op == Opcodes.INVOKESTATIC && m.equals("newDefaultFactory")
                                    && d.equals(NEW_DEFAULT_FACTORY_DESC)) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, m, d, false);
                                return;
                            }
                            if (op == Opcodes.INVOKEVIRTUAL && m.equals("setProperty") && d.equals(SET_PROPERTY_DESC)) {
                                changed[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, m, STATIC_SET_PROPERTY_DESC, false);
                                return;
                            }
                        }
                        super.visitMethodInsn(op, owner, m, d, itf);
                    }
                };
            }
        }, 0);
        return changed[0] ? w.toByteArray() : null;
    }

    /** Java 9's {@code XMLInputFactory.newDefaultFactory()} on Java 8. */
    public static XMLInputFactory newDefaultFactory() {
        try {
            return (XMLInputFactory) Class.forName(JDK_FACTORY).newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return XMLInputFactory.newFactory(); // a JVM without the OpenJDK parser
        }
    }

    /** {@code factory.setProperty(name, value)}, skipping {@code jdk.xml.*} properties the parser rejects. */
    public static void setProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException e) {
            if (name == null || !name.startsWith("jdk.xml.")) {
                throw e;
            }
        }
    }

    private static int indexOf(byte[] data, byte[] part) {
        outer:
        for (int i = 0; i <= data.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
