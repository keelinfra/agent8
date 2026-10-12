package java.util;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;

/**
 * Java 8 stand-in for the serial proxy of {@code List.of}, {@code Set.of},
 * {@code Map.of} and {@code Stream.toList()} (JDK 9+). Same name, UID and
 * stream format, so objects serialized by a newer controller deserialize
 * into unmodifiable Java 8 collections.
 */
final class CollSer implements Serializable {
    private static final long serialVersionUID = 6309168927139932177L;

    static final int IMM_LIST = 1;
    static final int IMM_SET = 2;
    static final int IMM_MAP = 3;
    static final int IMM_LIST_NULLS = 4;

    private final int tag;
    private transient Object[] array;

    CollSer(int t, Object... a) {
        tag = t;
        array = a;
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        int len = ois.readInt();
        if (len < 0) {
            throw new InvalidObjectException("negative length " + len);
        }
        Object[] a = new Object[len];
        for (int i = 0; i < len; i++) {
            a[i] = ois.readObject();
        }
        array = a;
    }

    private Object readResolve() throws InvalidObjectException {
        if (array == null) {
            throw new InvalidObjectException("null array");
        }
        switch (tag & 0xff) {
            case IMM_LIST:
            case IMM_LIST_NULLS:
                return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(array)));
            case IMM_SET:
                return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(array)));
            case IMM_MAP:
                Map<Object, Object> m = new LinkedHashMap<>();
                for (int i = 0; i + 1 < array.length; i += 2) {
                    m.put(array[i], array[i + 1]);
                }
                return Collections.unmodifiableMap(m);
            default:
                throw new InvalidObjectException(String.format("invalid flags 0x%x", tag));
        }
    }
}
