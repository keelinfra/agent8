# Agent8 for Jenkins

Run a **native Jenkins agent on Java 8**, connected to a current Jenkins controller that runs on Java 21 or newer.

Jenkins requires Java 21+ on the controller and on every agent. Build hosts that cannot run Java 21 — RHEL/CentOS 6, AIX 7.1, older HP-UX and Solaris — are left behind, and their teams stay on old, unsupported Jenkins. This project builds an `agent.jar` that runs on Java 8, so these hosts stay ordinary agents: labels, executors, `sh`, file steps, `junit`, `stash`, aborts and the script console work as on any other node.

## Status

Experimental. CI tests every change, and weekly, against the current Jenkins LTS and weekly release.

| Agent platform | Status |
|---|---|
| Linux, HotSpot Java 8 (Temurin), including RHEL/CentOS 6 | Tested in CI on CentOS 6.10 |
| Linux, OpenJ9 Java 8 (IBM Semeru) — the JVM family of IBM Java 8 on AIX | Tested in CI |
| AIX 7.1 / 7.2 with IBM Java 8 | Experimental: not yet run on real AIX |
| Solaris, HP-UX with Java 8 | Experimental: not tested |

## Usage

Build the jar from your controller's own `agent.jar`, so the remoting versions match:

```sh
curl -fsSO https://jenkins.example.com/jnlpJars/agent.jar
./build.sh agent.jar        # needs JDK 17+, git, curl, unzip, python3
```

This produces `dist/agent8.jar` and `dist/jvmdg.jar`. Copy both to the legacy host, create an inbound agent in Jenkins, and start it with the two Java agents in this order:

```sh
java -javaagent:agent8.jar -javaagent:jvmdg.jar -jar agent8.jar \
     -url https://jenkins.example.com/ -secret <secret> -name <node> -workDir /srv/jenkins
```

Rebuild `agent8.jar` whenever the controller is upgraded.

The agent host needs a `kill` command on the `PATH`, which every real RHEL 6 and AIX system has.

## How it works

Jenkins agents run two kinds of Java code: remoting itself (`agent.jar`), and classes the controller sends while builds run — core and plugin code for `FilePath`, `junit`, `stash`, launching processes and so on. Both are compiled for Java 17 or 21.

1. **remoting is downgraded ahead of time.** [JvmDowngrader](https://github.com/unimined/JvmDowngrader) rewrites `agent.jar` to Java 8 bytecode and adds stand-ins for newer JDK APIs. One call it cannot handle is patched in the remoting source (`patches/`).
2. **Classes from the controller are downgraded as they load**, by JvmDowngrader's Java agent (`jvmdg.jar`).
3. **`agent8.jar` is also a Java agent** that keeps Java serialization compatible with the controller:
   - Downgrading adds synthetic members, which changes the default `serialVersionUID` of classes that do not declare one. The controller would then reject every call. The agent pins the UID the controller computes, before downgrading. Records get UID 0.
   - It adds `java.util.CollSer`, the serial form of `List.of`, `Set.of`, `Map.of` and `Stream.toList()`, which Java 8 does not have.
   - On OpenJ9 it adds a stand-in for HotSpot's internal `ClassLoader$ParallelLoaders`, which JvmDowngrader's `ClassLoader` stubs need.

## Tests

- `test/serialization.sh`: objects written on Java 21 are read on Java 8.
- `test/it.sh`: starts the agent in a container with Java 8 (CentOS 6 with HotSpot, Ubuntu with OpenJ9) against a running controller and checks Pipeline builds, abort, agent restart during a build, and the script console.

CI runs both weekly against the latest LTS and weekly Jenkins.

## Known gaps

- A newer JDK API that JvmDowngrader cannot stub fails only when that code runs. Plugins that run code on agents (git, Maven, Docker, credentials binding …) still need testing.
- AIX with IBM Java 8 is untested on real hardware; OpenJ9 on Linux covers the JVM but not AIX itself.
- The SSH launcher copies the controller's own `remoting.jar` to the agent; use an inbound agent for now.

## License

[MIT](LICENSE). `agent8.jar` contains Jenkins remoting (MIT) and ASM (BSD-3-Clause). `jvmdg.jar` is JvmDowngrader, distributed unmodified under the [LGPL-2.1](https://github.com/unimined/JvmDowngrader/blob/main/LICENSE.md).
