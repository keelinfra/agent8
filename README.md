# Agent8 for Jenkins

Run a **native Jenkins agent on Java 8**, connected to a current Jenkins controller that runs on Java 21 or newer.

> Agent8 is an independent project. It is not part of, or endorsed or supported by, the Jenkins project. Please report problems [here](https://github.com/keelinfra/agent8/issues), not to the Jenkins issue tracker.

Jenkins requires Java 21+ on the controller and on every agent. Build hosts that cannot run Java 21 — RHEL/CentOS 6, AIX 7.1, older HP-UX and Solaris — are left behind, and their teams stay on old, unsupported Jenkins. This project builds an `agent.jar` that runs on Java 8, so these hosts stay ordinary agents: labels, executors, `sh`, file steps, `junit`, `stash`, aborts and the script console work as on any other node.

## Status

Experimental. CI tests every change, and weekly, against the current Jenkins LTS and weekly release.

| Agent platform | Status |
|---|---|
| Linux, HotSpot Java 8 (Temurin), including RHEL/CentOS 6 | Tested in CI on CentOS 6.10 |
| Linux, OpenJ9 Java 8 (IBM Semeru) — the JVM family of IBM Java 8 on AIX | Tested in CI |
| AIX 7.1 / 7.2 with IBM Java 8 | Experimental: not yet run on real AIX |
| Solaris, HP-UX with Java 8 | Experimental: not tested |
| Linux, HotSpot Java 11 (`agent8-java11.jar`) | Tested in CI against the current LTS |
| Linux, HotSpot Java 17 (`agent8-java17.jar`) | Tested in CI against the current LTS |

Java 8 is the main target. The same build also makes jars for hosts capped at Java 11 (Solaris SPARC and x86 stop at JDK 11, since JDK 15 dropped those ports) and Java 17.

## Install

On the build host, with its old Java (8, 11 or 17), and the controller's URL:

```sh
curl -fsSLO https://raw.githubusercontent.com/keelinfra/agent8/main/install.sh
sh install.sh --url https://jenkins.example.com/ --replace /path/to/agent.jar
```

`install.sh` asks the controller for its remoting version, downloads the matching agent8 jar for the host's Java (`agent8-java8.jar`, `agent8-java11.jar` or `agent8-java17.jar`) and `jvmdg.jar` from this repository's releases, checks them against the release's `sha256sums.txt` (with `sha256sum`, `shasum`, `digest` or `openssl`, or with Java when the host has none of these), checks that they start with the host's Java, and swaps them in for the `agent.jar` the host runs today (the old jar is kept as `agent.jar.before-agent8`). Restart the agent with its usual command, for example:

```sh
java -jar /path/to/agent.jar -url https://jenkins.example.com/ -secret <secret> -name <node> -workDir /srv/jenkins
```

Nothing else changes: the agent8 jar starts the Java agents it needs itself. With `--replace` it writes only next to the old jar, so it runs as the agent's own user; without it, it installs to `/opt/agent8` (`--dir`). Hosts without internet access: download the agent8 jars, `jvmdg.jar` and `sha256sums.txt` from the [releases](https://github.com/keelinfra/agent8/releases) and pass `--from DIR`. `--target 8|11|17` (Ansible: `agent8_target`) picks a build instead of going by the host's Java. `sh install.sh --help` lists all options.

Old hosts often have a CA bundle too old to verify github.com (`curl: (60) SSL certificate problem`). Do not turn verification off. `install.sh` downloads the release files with Java's own trust store when curl or wget cannot verify them; to fetch `install.sh` itself, copy it from a current machine (`scp install.sh host:`), or point curl at a current bundle (`curl --cacert /path/to/cacert.pem ...`, also `install.sh --cacert FILE`). Fully offline hosts use `--from DIR`. The script is plain `sh` for RHEL 6, AIX, Solaris and HP-UX.

For many hosts, the Ansible playbook runs the same script and needs no Python on the hosts:

```sh
ansible-playbook -i inventory ansible/agent8.yml \
  -e agent8_controller_url=https://jenkins.example.com/ \
  -e agent8_replace=/opt/jenkins/agent.jar \
  -e "agent8_restart='/etc/init.d/jenkins-agent restart'"
```

**Controller setting, if the Versions Node Monitors plugin (`versioncolumn`) is installed.** Its *JVM Version* monitor takes every agent whose Java is older than the controller's offline, seconds after it connects ("incompatible JVM version between agent and controller"). For agent8 hosts, set it to not disconnect under *Manage Jenkins → Nodes → Configure Node Monitors*, or in configuration as code:

```yaml
jenkins:
  nodeMonitors:
    - jvmVersion:
        ignored: true
```

Its *Remoting Version* monitor also takes agents offline whose remoting version differs from the controller's. `install.sh` installs the matching build when a release exists for the controller's remoting version; when it falls back to the latest build ("no agent8 build for remoting … yet"), ignore that monitor too, or ask for a build for that version.

Run `install.sh` again after upgrading the controller, so the agent's remoting version matches it.

**The old jar.** On the first `--replace`, the host's original jar is renamed to `agent.jar.before-agent8` (a later run never overwrites it), and agent8 is moved into place under the original name, so a running agent keeps its open file. `sh install.sh --restore /path/to/agent.jar` puts the original back and removes `jvmdg.jar`. Keep the backup until the agent has run builds for a while, then delete it.

`install.sh` sticks to POSIX `sh` commands (no `command -v`, `!`, `tail -n`, `head -n`, `mktemp -d` or GNU-only flags), reruns itself under `ksh` on Solaris 10's Bourne shell, and is checked with `shellcheck -s sh`. CI runs it on CentOS 6 and Ubuntu; AIX, Solaris and HP-UX are untested. Windows hosts can run a current Java and the stock `agent.jar`, so agent8 does not target them.

The agent host needs a `kill` command on the `PATH`, which every real RHEL 6 and AIX system has. Agents launched by the SSH Build Agents plugin are not supported yet: that plugin copies its own `remoting.jar` to the host and requires a newer Java; use inbound agents.

### Build it yourself

```sh
curl -fsSO https://jenkins.example.com/jnlpJars/agent.jar
./build.sh agent.jar          # needs JDK 17+, git, curl, unzip, python3; writes dist/agent8-java8.jar
./build.sh agent.jar 8 11 17  # also dist/agent8-java11.jar and dist/agent8-java17.jar
```

## How it works

Jenkins agents run two kinds of Java code: remoting itself (`agent.jar`), and classes the controller sends while builds run — core and plugin code for `FilePath`, `junit`, `stash`, launching processes and so on. Both are compiled for Java 17 or 21.

1. **remoting is downgraded ahead of time.** [JvmDowngrader](https://github.com/unimined/JvmDowngrader) rewrites `agent.jar` to Java 8 bytecode (or 11 or 17) and adds stand-ins for newer JDK APIs. One call it cannot handle is patched in the remoting source (`patches/`; older remoting versions do not need it).
2. **Classes from the controller are downgraded as they load**, by JvmDowngrader's Java agent (`jvmdg.jar`).
3. **The agent8 jar is a drop-in for `agent.jar`.** When started with plain `java -jar`, it starts the same Java again with both Java agents and the same JVM options.
4. **It is also a Java agent** that keeps Java serialization compatible with the controller:
   - Downgrading adds synthetic members, which changes the default `serialVersionUID` of classes that do not declare one. The controller would then reject every call. The agent pins the UID the controller computes, before downgrading. Records get UID 0.
   - It adds `java.util.CollSer`, the serial form of `List.of`, `Set.of`, `Map.of` and `Stream.toList()`, which Java 8 does not have. On Java 9 to 15 it teaches the JDK's own `CollSer` the form `Stream.toList()` uses since Java 16.
   - On OpenJ9 it adds a stand-in for HotSpot's internal `ClassLoader$ParallelLoaders`, which JvmDowngrader's `ClassLoader` stubs need.
   - It fixes JvmDowngrader stubs that behave differently from the JDK: `InputStream.readNBytes(int)` allocated the full length up front, so `readNBytes(Integer.MAX_VALUE)`, which JGit uses to read Git config files, failed on Java 8.

## Tests

- `test/serialization.sh`: objects written on Java 21 are read on Java 8.
- `test/it.sh`: installs agent8 with `install.sh` over a stock `agent.jar` in a container with old Java (CentOS 6 with HotSpot 8, Ubuntu with OpenJ9 8, Ubuntu with HotSpot 11 and 17), starts it with the usual command against a running controller and checks Pipeline builds, a Git checkout (JGit, i.e. plugin code from the controller running on old Java), `withCredentials`, abort, agent restart during a build, the script console, and `--replace`/`--restore`. With `BASIC=1` it runs against a bare controller (core only, freestyle shell build), for older Jenkins lines whose plugins no longer install.

CI runs both weekly against the latest LTS and weekly Jenkins.

## Releasing

Run the **Release** workflow (Actions → Release → Run workflow). By default it builds and tests the current Jenkins LTS and weekly, then publishes one release per remoting version with all three jars and `sha256sums.txt`; list other Jenkins image tags to release older versions, or tick *dry run* to build and test only. Older Jenkins lines whose plugins no longer install (for example `2.462.3-lts-jdk17`) go in the *basic* field: they are tested against a bare controller with a freestyle shell build, and their release notes say so. CI itself does not publish; a weekly *Remoting check* workflow opens an issue when the current LTS or weekly ships a remoting version that has no release yet.

## Known gaps

- A newer JDK API that JvmDowngrader cannot stub fails only when that code runs. Plugins that run code on agents (git, Maven, Docker, credentials binding …) still need testing.
- AIX with IBM Java 8 is untested on real hardware; OpenJ9 on Linux covers the JVM but not AIX itself.

## License

[MIT](LICENSE) for this project's own code. The agent8 jars also contain:

- Jenkins remoting (MIT), downgraded and with one patched class;
- ASM (BSD-3-Clause);
- classes from [JvmDowngrader](https://github.com/unimined/JvmDowngrader) (LGPL-2.1), shaded into the jar by `build.sh`: the stand-ins for newer JDK APIs that the downgraded remoting calls. They are unmodified apart from the package rename (at run time agent8 replaces one stub method, `InputStream.readNBytes(int)`, see `src/io/keelinfra/agent8/StubFixes.java`); their source is in the JvmDowngrader repository at the version `build.sh` pins (2.0.1), and `build.sh` rebuilds the jars from it, so you can swap in your own build of JvmDowngrader.

`jvmdg.jar` is JvmDowngrader 2.0.1 itself, distributed unmodified under the [LGPL-2.1](https://github.com/unimined/JvmDowngrader/blob/main/LICENSE.md).
