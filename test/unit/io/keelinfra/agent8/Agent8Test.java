package io.keelinfra.agent8;

import static io.keelinfra.agent8.Check.contains;
import static io.keelinfra.agent8.Check.eq;
import static io.keelinfra.agent8.Check.isTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectStreamClass;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

final class Agent8Test {

    static final String DATA = "agent8test/Data";
    static final String SERIALIZABLE = "java/io/Serializable";

    /** A Serializable class without serialVersionUID, with the kinds of members the default UID depends on. */
    static byte[] data(int version) {
        ClassWriter w = Check.begin(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, DATA, "java/lang/Object", SERIALIZABLE);
        w.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null).visitEnd();
        w.visitField(Opcodes.ACC_PROTECTED, "label", "Ljava/lang/String;", null, null).visitEnd();
        w.visitField(Opcodes.ACC_STATIC, "instances", "I", null, null).visitEnd();
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT, "cache", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC, "count", "()I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, DATA, "count", "I");
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** Adds a member as JvmDowngrader does when it downgrades a class (a stub bridge, for example). */
    static byte[] downgrade(byte[] bytes) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override
            public void visitEnd() {
                MethodVisitor mv = visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "jvmdg$bridge", "()V", null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                super.visitEnd();
            }
        }, 0);
        return w.toByteArray();
    }

    static long uid(byte[] bytes) throws Exception {
        return ObjectStreamClass.lookup(Check.load(DATA.replace('/', '.'), bytes)).getSerialVersionUID();
    }

    static Long pinned(byte[] bytes) {
        FieldNode f = Check.field(Check.node(bytes), "serialVersionUID");
        isTrue("serialVersionUID declared", f != null);
        eq("serialVersionUID type", "J", f.desc);
        isTrue("serialVersionUID is static final", (f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL));
        return (Long) f.value;
    }

    static void testRuntimeClassVersion() throws Exception {
        eq("Java 8", 52, Agent8.runtimeClassVersion("1.8"));
        eq("Java 9", 53, Agent8.runtimeClassVersion("9"));
        eq("Java 11", 55, Agent8.runtimeClassVersion("11"));
        eq("Java 17", 61, Agent8.runtimeClassVersion("17"));
        eq("this JVM: its own classes' version", Agent8.majorVersion(Check.jdkClass("java/lang/Object")),
                Agent8.RUNTIME_CLASS_VERSION);
    }

    static void testMajorVersion() {
        eq("Java 8 class", 52, Agent8.majorVersion(data(Opcodes.V1_8)));
        eq("Java 17 class", 61, Agent8.majorVersion(data(Opcodes.V17)));
    }

    static void testPinsTheUidTheControllerComputes() throws Exception {
        byte[] original = data(Opcodes.V1_8);
        long controller = uid(original);
        byte[] pinned = Agent8.pinSerialVersionUID(original);
        eq("pinned value", controller, pinned(pinned));
        eq("UID of the pinned class", controller, uid(pinned));
        // What the pin is for: downgrading adds members, which changes the default UID.
        isTrue("downgrading changes the default UID", uid(downgrade(original)) != controller);
        eq("UID of the pinned class after downgrading", controller, uid(downgrade(pinned)));
    }

    static void testKeepsADeclaredUid() throws Exception {
        ClassWriter w = Check.begin(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, DATA, "java/lang/Object", SERIALIZABLE);
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "serialVersionUID", "J", null, 42L).visitEnd();
        w.visitEnd();
        byte[] pinned = Agent8.pinSerialVersionUID(w.toByteArray());
        eq("declared value", 42L, pinned(pinned));
        eq("UID", 42L, uid(pinned));
    }

    static void testLeavesInterfacesAndEnumsAlone() {
        ClassWriter i = Check.begin(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "agent8test/Api", "java/lang/Object", SERIALIZABLE);
        i.visitEnd();
        eq("interface", null, Agent8.pinSerialVersionUID(i.toByteArray()));
        ClassWriter e = Check.begin(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM | Opcodes.ACC_SUPER,
                "agent8test/Color", "java/lang/Enum");
        e.visitEnd();
        eq("enum", null, Agent8.pinSerialVersionUID(e.toByteArray()));
    }

    static void testRecordsGetUidZero() {
        ClassWriter w = Check.begin(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                "agent8test/Point", "java/lang/Record", SERIALIZABLE);
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "x", "I", null, null).visitEnd();
        w.visitEnd();
        byte[] original = w.toByteArray();
        ClassNode pinned = Check.node(Agent8.pinSerialVersionUID(original));
        FieldNode uid = Check.field(pinned, "serialVersionUID");
        eq("record UID", 0L, uid.value);
        eq("record UID access", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, uid.access);
        eq("fields", 2, pinned.fields.size());
        eq("methods unchanged", Check.node(original).methods.size(), pinned.methods.size());
    }

    static void testTransformPinsClassesNewerThanThisJvm() throws Exception {
        int newer = Agent8.RUNTIME_CLASS_VERSION + 1;
        isTrue("ASM 9.8 reads Java " + (newer - 44) + " classes: run the unit tests on Java 11 to 21", newer <= Opcodes.V25);
        byte[] bytes = data(newer);
        byte[] out = new Agent8().transform(null, DATA, null, null, bytes);
        isTrue("transformed", out != null);
        eq("still the same class file version", newer, Agent8.majorVersion(out));
        eq("UID the controller computes (the class file version does not change it)", uid(data(Opcodes.V1_8)), pinned(out));
    }

    static void testTransformLeavesClassesThisJvmCanLoad() {
        Agent8 a = new Agent8();
        eq("this JVM's version", null, a.transform(null, DATA, null, null, data(Agent8.RUNTIME_CLASS_VERSION)));
        eq("Java 8", null, a.transform(null, DATA, null, null, data(Opcodes.V1_8)));
        eq("no bytes", null, a.transform(null, DATA, null, null, null));
        eq("short", null, a.transform(null, DATA, null, null, new byte[7]));
    }

    static void testTransformReportsAClassItCannotRead() throws Exception {
        final byte[] broken = Arrays.copyOf(data(Agent8.RUNTIME_CLASS_VERSION + 1), 12);
        final byte[][] out = {new byte[0]};
        String err = Check.stderr(() -> out[0] = new Agent8().transform(null, "agent8test/Broken", null, null, broken));
        eq("result", null, out[0]);
        contains("message", err, "[agent8] cannot pin serialVersionUID of agent8test/Broken: ");
    }

    static void testTransformFixesJvmdgClasses() throws Exception {
        Agent8 a = new Agent8();
        String agent = "io/keelinfra/agent8/jvmdg/" + SignedJarsTest.AGENT;
        byte[] fixed = a.transform(null, agent, null, null, SignedJarsTest.agent(true));
        isTrue("ClassDowngradingAgent patched", Arrays.equals(SignedJars.patch(SignedJarsTest.agent(true)), fixed));
        String stub = "io/keelinfra/agent8/jvmdg/" + StubFixesTest.STUB;
        byte[] stubFixed = a.transform(null, stub, null, null, StubFixesTest.stub());
        isTrue("InputStream stub patched", Arrays.equals(StubFixes.patch(StubFixesTest.stub()), stubFixed));
    }

    static void testTransformReportsJvmdgClassesItCannotFix() throws Exception {
        final Agent8 a = new Agent8();
        final String agent = "io/keelinfra/agent8/jvmdg/" + SignedJarsTest.AGENT;
        final byte[][] out = {new byte[0], new byte[0]};
        String err = Check.stderr(() -> out[0] = a.transform(null, agent, null, null, SignedJarsTest.agent(false)));
        eq("ClassDowngradingAgent result", null, out[0]);
        contains("ClassDowngradingAgent message", err, "[agent8] cannot fix " + agent + ": java.lang.IllegalStateException: no retransformCodeSource");
        final String stub = "io/keelinfra/agent8/jvmdg/" + StubFixesTest.STUB;
        err = Check.stderr(() -> out[1] = a.transform(null, stub, null, null, new byte[] {1, 2, 3}));
        eq("stub result", null, out[1]);
        contains("stub message", err, "[agent8] cannot fix " + stub + ": ");
    }

    static void testMainPinsEveryNewerClassOfAJar() throws Exception {
        File dir = Check.tempDir("main");
        File in = new File(dir, "in.jar");
        File out = new File(dir, "out.jar");
        ClassWriter api = Check.begin(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "agent8test/Api", "java/lang/Object");
        api.visitEnd();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes("UTF-8"));
        entries.put("agent8test/Data.class", data(Opcodes.V17));
        entries.put("agent8test/Old.class", data(Opcodes.V1_8));
        entries.put("agent8test/Api.class", api.toByteArray());
        entries.put("META-INF/versions/17/agent8test/Data.class", data(Opcodes.V17));
        entries.put("agent8test/messages.properties", "hello=world\n".getBytes("UTF-8"));
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(in))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        Agent8.main(new String[] {in.getPath(), out.getPath()});
        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(out)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                names.add(entry.getName());
                byte[] got = Check.readAll(z.getInputStream(entry));
                byte[] want = entries.get(entry.getName());
                if (entry.getName().equals("agent8test/Data.class")) {
                    isTrue("Data.class pinned", Arrays.equals(Agent8.pinSerialVersionUID(want), got));
                    eq("Data.class UID", uid(data(Opcodes.V1_8)), pinned(got));
                } else {
                    isTrue(entry.getName() + " copied unchanged", Arrays.equals(want, got));
                }
            }
        }
        eq("entries, in order", new ArrayList<>(entries.keySet()), names);
    }

    static void testPremainRegistersTheTransformersAndCompatJar() throws Exception {
        final List<ClassFileTransformer> transformers = new ArrayList<>();
        final List<Boolean> retransform = new ArrayList<>();
        final List<JarFile> bootstrap = new ArrayList<>();
        Instrumentation inst = Check.instrumentation((proxy, m, args) -> {
            if (m.getName().equals("addTransformer")) {
                transformers.add((ClassFileTransformer) args[0]);
                retransform.add(args.length > 1 && (Boolean) args[1]);
            } else if (m.getName().equals("appendToBootstrapClassLoaderSearch")) {
                bootstrap.add((JarFile) args[0]);
            }
            return Check.none(m.getReturnType());
        });
        Agent8.active = false;
        String err;
        try {
            err = Check.stderr(() -> Agent8.premain(null, inst));
            isTrue("active", Agent8.active);
        } finally {
            Agent8.active = false;
        }
        if (CollSerNulls.needed()) { // this Instrumentation cannot retransform
            contains("Java 9 to 15", err, "[agent8] java.util.CollSer not patched");
        } else {
            Check.lacks("no warning", err, "[agent8]");
        }
        isTrue("Agent8 transformer first", transformers.get(0) instanceof Agent8);
        eq("Agent8 transformer does not retransform", false, retransform.get(0));
        eq("CollSerNulls registered (Java 9 to 15)", CollSerNulls.needed(), transformers.size() == 2
                && transformers.get(1) instanceof CollSerNulls && retransform.get(1));
        eq("one compat jar", 1, bootstrap.size());
        try (JarFile jar = bootstrap.get(0)) {
            for (String c : Agent8.COMPAT_CLASSES) {
                boolean jvmHasIt = ClassLoader.getSystemResource(c + ".class") != null;
                eq(c + " in the compat jar only if this JVM lacks it", !jvmHasIt, jar.getEntry(c + ".class") != null);
            }
            eq("nothing else in the compat jar", 0, jar.size() - countCompat(jar));
        }
    }

    private static int countCompat(JarFile jar) {
        int n = 0;
        for (String c : Agent8.COMPAT_CLASSES) {
            n += jar.getEntry(c + ".class") != null ? 1 : 0;
        }
        return n;
    }
}
