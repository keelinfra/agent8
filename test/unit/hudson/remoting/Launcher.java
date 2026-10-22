package hudson.remoting;

import java.lang.management.ManagementFactory;

/** Stand-in for remoting's launcher in the unit tests: shows what agent8's Main hands it. */
public class Launcher {
    public static void main(String... args) {
        System.out.println("launcher: " + String.join(" ", args));
        StringBuilder agents = new StringBuilder("launcher java agents:");
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-javaagent:")) {
                agents.append(' ').append(a.substring(a.lastIndexOf('/') + 1));
            }
        }
        System.out.println(agents);
        Integer exit = Integer.getInteger("launcher.exit");
        if (exit != null) {
            System.exit(exit);
        }
    }
}
