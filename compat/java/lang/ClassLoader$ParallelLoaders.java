package java.lang;

/**
 * Stand-in for HotSpot's internal {@code ClassLoader.ParallelLoaders}, which
 * JvmDowngrader's {@code ClassLoader} stubs look up when they initialize.
 * OpenJ9 (IBM Java 8) has no such class, so every stubbed call would fail.
 * Only added when the JVM lacks it.
 */
final class ClassLoader$ParallelLoaders {
    private ClassLoader$ParallelLoaders() {}

    /** Answers {@code ClassLoader.isRegisteredAsParallelCapable()}, a Java 9 API. */
    static boolean isRegistered(Class<? extends ClassLoader> c) {
        return false;
    }
}
