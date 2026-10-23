package io.keelinfra.agent8;

import static io.keelinfra.agent8.Check.contains;
import static io.keelinfra.agent8.Check.eq;
import static io.keelinfra.agent8.Check.isTrue;
import static io.keelinfra.agent8.Check.lacks;

import java.io.InvalidObjectException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

final class CollSerNullsTest {

    static final String COPY = "agent8test/CollSer";

    /**
     * This JDK's java.util.CollSer after the transform, renamed so that it loads outside java.base.
     * Its readResolve calls only public JDK API for the cases tested here.
     */
    static Class<?> patchedCollSer() throws Exception {
        byte[] patched = new CollSerNulls().transform(null, CollSerNulls.NAME, null, null, Check.jdkClass(CollSerNulls.NAME));
        isTrue("patched", patched != null);
        ClassWriter w = new ClassWriter(0);
        new ClassReader(patched).accept(new ClassRemapper(w, new SimpleRemapper(CollSerNulls.NAME, COPY)), 0);
        return Check.load(COPY.replace('/', '.'), w.toByteArray());
    }

    /** What ObjectInputStream gets for a CollSer with this tag and these elements. */
    static Object readResolve(Class<?> collSer, int tag, Object[] array) throws Exception {
        Constructor<?> c = collSer.getDeclaredConstructor(int.class, Object[].class);
        c.setAccessible(true);
        Method m = collSer.getDeclaredMethod("readResolve");
        m.setAccessible(true);
        try {
            return m.invoke(c.newInstance(tag, array));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    static void testNeededOnJava9To15Only() {
        String spec = System.getProperty("java.specification.version");
        int feature = Integer.parseInt(spec.startsWith("1.") ? spec.substring(2) : spec);
        eq("needed on Java " + feature, feature >= 9 && feature <= 15, CollSerNulls.needed());
    }

    static void testReadsListsWithNulls() throws Exception {
        Class<?> c = patchedCollSer();
        Object list = readResolve(c, CollSerNulls.IMM_LIST_NULLS, new Object[] {"a", null});
        eq("Stream.toList() form", Arrays.asList("a", null), list);
        Check.fails(UnsupportedOperationException.class, () -> ((List<?>) list).add(null));
        eq("high tag bits ignored, as by the JDK", Arrays.asList("b"), readResolve(c, 0x100 | CollSerNulls.IMM_LIST_NULLS, new Object[] {"b"}));
    }

    static void testOtherFormsReachTheJdkCode() throws Exception {
        Class<?> c = patchedCollSer();
        eq("List.of form", Collections.singletonList("x"), readResolve(c, 1, new Object[] {"x"}));
        Check.fails(InvalidObjectException.class, () -> readResolve(c, 0, new Object[] {"x"}));
        Check.fails(InvalidObjectException.class, () -> readResolve(c, CollSerNulls.IMM_LIST_NULLS, null));
    }

    static void testOnlyPatchesTheBootstrapCollSer() throws Exception {
        byte[] bytes = Check.jdkClass(CollSerNulls.NAME);
        CollSerNulls t = new CollSerNulls();
        eq("loaded by an application class loader", null, t.transform(CollSerNullsTest.class.getClassLoader(), CollSerNulls.NAME, null, null, bytes));
        eq("another class", null, t.transform(null, "java/util/ArrayList", null, null, Check.jdkClass("java/util/ArrayList")));
    }

    static void testReportsAClassItCannotPatch() throws Exception {
        final byte[][] out = {new byte[0]};
        String err = Check.stderr(() -> out[0] = new CollSerNulls().transform(null, CollSerNulls.NAME, null, null, new byte[] {1, 2, 3}));
        eq("result", null, out[0]);
        contains("message", err, "[agent8] cannot patch java.util.CollSer: ");
    }

    /** An Instrumentation that retransforms like the JVM: it calls the transformer with the class's bytes. */
    static Instrumentation jvm(final boolean retransformSupported, final boolean loadsLater,
            final List<String> calls, final List<byte[]> results) {
        final List<ClassFileTransformer> transformers = new ArrayList<>();
        return Check.instrumentation((proxy, m, args) -> {
            calls.add(m.getName() + (args != null && args.length > 1 && m.getName().equals("addTransformer") ? "(" + args[1] + ")" : ""));
            if (m.getName().equals("addTransformer")) {
                transformers.add((ClassFileTransformer) args[0]);
                if (loadsLater) { // as if CollSer loaded only now
                    results.add(transformers.get(0).transform(null, CollSerNulls.NAME, null, null, Check.jdkClass(CollSerNulls.NAME)));
                }
            } else if (m.getName().equals("isRetransformClassesSupported")) {
                return retransformSupported;
            } else if (m.getName().equals("retransformClasses")) {
                Class<?>[] classes = (Class<?>[]) args[0];
                eq("retransformed", Collections.singletonList(Class.forName("java.util.CollSer")), Arrays.asList(classes));
                results.add(transformers.get(0).transform(null, CollSerNulls.NAME, classes[0], null, Check.jdkClass(CollSerNulls.NAME)));
            }
            return Check.none(m.getReturnType());
        });
    }

    static void testInstallRetransformsTheLoadedCollSer() throws Exception {
        List<String> calls = new ArrayList<>();
        List<byte[]> results = new ArrayList<>();
        String err = Check.stderr(() -> CollSerNulls.install(jvm(true, false, calls, results)));
        eq("calls", Arrays.asList("addTransformer(true)", "isRetransformClassesSupported", "retransformClasses"), calls);
        isTrue("patched", results.size() == 1 && results.get(0) != null);
        lacks("no warning", err, "[agent8]");
    }

    static void testInstallSkipsRetransformingWhenAlreadyPatched() throws Exception {
        List<String> calls = new ArrayList<>();
        List<byte[]> results = new ArrayList<>();
        String err = Check.stderr(() -> CollSerNulls.install(jvm(true, true, calls, results)));
        eq("calls", Arrays.asList("addTransformer(true)"), calls);
        isTrue("patched", results.size() == 1 && results.get(0) != null);
        lacks("no warning", err, "[agent8]");
    }

    static void testInstallWarnsWithoutRetransform() throws Exception {
        List<String> calls = new ArrayList<>();
        String err = Check.stderr(() -> CollSerNulls.install(jvm(false, false, calls, new ArrayList<>())));
        eq("calls", Arrays.asList("addTransformer(true)", "isRetransformClassesSupported"), calls);
        contains("warning", err, "[agent8] java.util.CollSer not patched: lists from Stream.toList() will not deserialize");
    }

    static void testInstallWarnsWhenRetransformFails() throws Exception {
        Instrumentation inst = Check.instrumentation((proxy, m, args) -> {
            if (m.getName().equals("retransformClasses")) {
                throw new UnmodifiableClassException("java.util.CollSer");
            }
            return m.getName().equals("isRetransformClassesSupported") ? Boolean.TRUE : Check.none(m.getReturnType());
        });
        String err = Check.stderr(() -> CollSerNulls.install(inst));
        contains("error", err, "[agent8] cannot patch java.util.CollSer: java.lang.instrument.UnmodifiableClassException: java.util.CollSer");
        contains("warning", err, "[agent8] java.util.CollSer not patched");
    }
}
