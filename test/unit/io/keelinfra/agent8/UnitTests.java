package io.keelinfra.agent8;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

/** Runs every static no-argument {@code test*} method of the test classes; exits 1 if one fails. */
public final class UnitTests {

    static final Class<?>[] SUITES = {
        Agent8Test.class, CollSerNullsTest.class, SignedJarsTest.class, StubFixesTest.class, MainTest.class,
    };

    public static void main(String[] args) {
        int run = 0;
        int failed = 0;
        for (Class<?> suite : SUITES) {
            Method[] methods = suite.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.getName().startsWith("test") || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
                    continue;
                }
                run++;
                String name = suite.getSimpleName() + "." + m.getName();
                try {
                    m.invoke(null);
                    System.out.println("ok   " + name);
                } catch (InvocationTargetException | IllegalAccessException e) {
                    failed++;
                    Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
                    System.out.println("FAIL " + name + ": " + cause);
                    cause.printStackTrace(System.out);
                }
            }
        }
        System.out.println(run + " Java unit tests, " + failed + " failed");
        System.exit(failed == 0 && run > 0 ? 0 : 1);
    }
}
