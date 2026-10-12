import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Runs on Java 8 with -javaagent:agent8.jar: reads what SerialWrite wrote. */
public class SerialRead {
    public static void main(String[] args) throws Exception {
        Map<String, Integer> ab = new HashMap<>();
        ab.put("a", 1);
        ab.put("b", 2);
        Object[] expected = {
            Arrays.asList(1, 2, 3),
            Collections.emptyList(),
            Collections.singleton("x"),
            ab,
            Arrays.asList("p", null),
            Collections.singletonMap("nested", Collections.singletonList(Collections.singleton(7))),
        };
        int failures = 0;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(args[0]))) {
            for (Object want : expected) {
                Object got = in.readObject();
                boolean ok = want.equals(got);
                System.out.println((ok ? "ok   " : "FAIL ") + got + (ok ? "" : " expected " + want));
                if (!ok) failures++;
                if (got instanceof List) {
                    try {
                        ((List<Object>) got).add(null);
                        System.out.println("FAIL list is modifiable");
                        failures++;
                    } catch (UnsupportedOperationException expectedException) {
                        // immutable, as on the controller
                    }
                }
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
