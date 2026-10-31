<a href="https://keelinfra.io">
  <picture>
    <source media="(prefers-color-scheme: dark)"
            srcset="https://raw.githubusercontent.com/keelinfra/.github/main/assets/wordmark-dark.svg">
    <img alt="keelinfra" width="280"
         src="https://raw.githubusercontent.com/keelinfra/.github/main/assets/wordmark-light.svg">
  </picture>
</a>

# Agent8 for Jenkins

[![CI](https://github.com/keelinfra/agent8/actions/workflows/ci.yml/badge.svg)](https://github.com/keelinfra/agent8/actions/workflows/ci.yml)
[![Agent Java](https://img.shields.io/badge/agent%20Java-8%20%7C%2011%20%7C%2017-blue)](#install)
[![Controller Java](https://img.shields.io/badge/controller%20Java-11%20%7C%2017%20%7C%2021%20%7C%2025-blue)](#status)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](#license)

Run a **native Jenkins agent on Java 8** (or 11, 17), connected to a Jenkins controller that runs on a newer Java: 11, 17, 21 or 25.

> Agent8 is an independent project. It is not part of, or endorsed or supported by, the Jenkins project. Please report problems [here](https://github.com/keelinfra/agent8/issues), not to the Jenkins issue tracker.

**Why.** Jenkins requires Java 21+ on every agent. Build hosts that cannot run Java 21, such as AIX 7.1, older HP-UX and Solaris, are left behind. Agent8 builds an `agent.jar` that runs on Java 8, so these hosts stay ordinary agents: labels, executors, `sh`, file steps, `junit`, `stash`, aborts and the script console work as on any other node.

On RHEL/CentOS 6, try a newer JDK first: Amazon Corretto 21 and Azul Zulu 21 run on CentOS 6.10, and with them the stock `agent.jar` works without agent8. Agent8 is for hosts with no Java 21 build at all, and for hosts whose installed JDK must not change.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/how-it-works-dark.svg">
  <img alt="How Agent8 works: without it, Jenkins' agent.jar needs Java 21 and a Java 8 host can't connect; install.sh fetches the Agent8 build for your Jenkins version, checks its sha256 and swaps it in; the host runs the same java -jar agent.jar on Java 8 and connects." src="docs/how-it-works-light.svg" width="960">
</picture>

## How it works

1. [JvmDowngrader](https://github.com/unimined/JvmDowngrader) rewrites Jenkins' `agent.jar` to Java 8 bytecode ahead of time.
2. Classes the controller sends during builds (core and plugin code) are downgraded as they load.
3. A small Java agent keeps Java serialization compatible with the controller.

Details: [docs/how-it-works.md](docs/how-it-works.md).

## Status

Free to use. Linux is tested in CI on every change and weekly, against the current Jenkins LTS and weekly release. AIX, Solaris and HP-UX have been tested; they are not in CI.

| Agent platform | Status |
|---|---|
| Linux, HotSpot Java 8 (Temurin), including RHEL/CentOS 6 | Tested in CI on CentOS 6.10 |
| Linux, OpenJ9 Java 8 (IBM Semeru) — the JVM family of IBM Java 8 on AIX | Tested in CI; best effort (see below) |
| AIX 7.1 / 7.2 / 7.3 with IBM Java 8 | Tested, including aborting builds (child processes are killed); not in CI |
| Solaris with Java 8 | Tested (Oracle Java 8, SSH agent); not in CI |
| HP-UX with Java 8 | Tested (Itanium, HP Java 8, SSH agent); not in CI |
| Linux, HotSpot Java 11 (`agent8-java11.jar`) | Tested in CI against the current LTS |
| Linux, HotSpot Java 17 (`agent8-java17.jar`) | Tested in CI against the current LTS |

IBM Java and OpenJ9 (J9) are outside Jenkins' own support, so J9 support here is best effort.

**Controllers.** What agent8 has to match is the controller's Jenkins version, not the Java it runs on. CI runs the agent tests against controllers on:

| Controller Java | Jenkins version in CI |
|---|---|
| Java 25 | current LTS |
| Java 21 | current LTS and weekly |
| Java 17 | 2.541.3, the last LTS on Java 17 |
| Java 11 | 2.462.3, the last LTS on Java 11 (bare controller: freestyle build, no plugins) |

After a controller upgrade, rerun `install.sh` so the agent matches the new Jenkins version. Releases are built for the current LTS and weekly, and a weekly check opens an issue when a new Jenkins version has none yet. For an older Jenkins version, [ask for a build](https://github.com/keelinfra/agent8/issues).

Using GitLab CI on AIX 7.2 or 7.3? See [aix-runner](https://github.com/keelinfra/aix-runner) (unofficial GitLab Runner builds).

## Install

On the build host, with its old Java and the controller's URL:

```sh
curl -fsSLO https://raw.githubusercontent.com/keelinfra/agent8/main/install.sh
sh install.sh --url https://jenkins.example.com/ --replace /path/to/agent.jar
```

`install.sh` picks the agent8 jar that matches the controller and the host's Java, checks its sha256, checks it runs on the host (`--check`), and swaps it in for your `agent.jar` (the old one is kept). Restart the agent with its usual command. Nothing needs to be installed on the controller.

**SSH agents:** install with `--dir /opt/agent8` instead of `--replace`, then set the node's *Java Path* to `/opt/agent8/java`.

More in [docs/install.md](docs/install.md): hosts without internet or with an old CA bundle, Ansible, the versioncolumn plugin, controller upgrades, and troubleshooting. Building and testing: [docs/development.md](docs/development.md).

## Known gaps

- A newer JDK API that JvmDowngrader cannot stub fails only when that code runs. CI tests Git checkout (JGit and command-line git) and credentials binding. Other plugins that run their own code on agents, such as Docker or artifact repository upload steps (Artifactory, Nexus), are untested. Try those on one host before moving a whole job.
- The Maven project type (maven-plugin) does not work: it starts its own JVM for Maven, which agent8 does not reach. Run Maven from a Pipeline or freestyle shell step (`sh 'mvn ...'`) instead; that works like any other command.

## Commercial support

Agent8 is free and stays MIT. For a pilot on AIX, Solaris or HP-UX, a plugin compatibility check, or yearly support that keeps up with controller upgrades, see [keelinfra.io/jenkins/pricing](https://keelinfra.io/jenkins/pricing/).

## License

[MIT](LICENSE). The jars also bundle Jenkins remoting (MIT), ASM (BSD-3-Clause) and parts of JvmDowngrader (LGPL-2.1); `jvmdg.jar` is JvmDowngrader itself, unmodified. Details in [docs/license.md](docs/license.md).
