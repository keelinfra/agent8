import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Runs on the controller's Java: writes JDK 9+ objects that Jenkins may send to an agent. */
public class SerialWrite {
    record Point(int x, String label) implements java.io.Serializable {}

    public static void main(String[] args) throws Exception {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(args[0]))) {
            out.writeObject(List.of(1, 2, 3));
            out.writeObject(List.of());
            out.writeObject(Set.of("x"));
            out.writeObject(Map.of("a", 1, "b", 2));
            out.writeObject(Stream.of("p", null).toList());
            out.writeObject(Map.of("nested", List.of(Set.of(7))));
        }
    }
}
