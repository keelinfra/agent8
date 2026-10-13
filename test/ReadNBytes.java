import java.io.ByteArrayInputStream;

/** Compiled for Java 17, run on old Java under agent8 + JvmDowngrader: InputStream.readNBytes(int) behaves like the JDK's. */
public class ReadNBytes {
    public static void main(String[] a) throws Exception {
        check("all", new String(new ByteArrayInputStream("hello".getBytes()).readNBytes(Integer.MAX_VALUE)), "hello");
        check("part", new String(new ByteArrayInputStream("abcdef".getBytes()).readNBytes(3)), "abc");
        check("eof", String.valueOf(new ByteArrayInputStream(new byte[0]).readNBytes(10).length), "0");
    }

    static void check(String what, String got, String want) {
        System.out.println((got.equals(want) ? "ok   " : "FAIL ") + "readNBytes " + what + ": " + got);
        if (!got.equals(want)) System.exit(1);
    }
}
