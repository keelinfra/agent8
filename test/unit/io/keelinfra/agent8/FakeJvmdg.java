package io.keelinfra.agent8;

import java.lang.instrument.Instrumentation;

/** The Java agent in the unit tests' jvmdg.jar: Main only checks that a jvmdg agent is on the command line. */
public final class FakeJvmdg {
    public static void premain(String args, Instrumentation inst) {}
}
