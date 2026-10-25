# Building, testing and releasing agent8

## Build it yourself

```sh
curl -fsSO https://jenkins.example.com/jnlpJars/agent.jar
./build.sh agent.jar          # needs JDK 17+, git, curl, unzip, python3; writes dist/agent8-java8.jar
./build.sh agent.jar 8 11 17  # also dist/agent8-java11.jar and dist/agent8-java17.jar
```

## Tests

- `test/serialization.sh`: objects written on Java 21 are read on Java 8.
- `test/it.sh`: installs agent8 with `install.sh` over a stock `agent.jar` in a container with old Java (CentOS 6 with HotSpot 8, Ubuntu with OpenJ9 8, Ubuntu with HotSpot 11 and 17), starts it with the usual command against a running controller and checks Pipeline builds, a Git checkout (JGit, i.e. plugin code from the controller running on old Java), `withCredentials`, abort, agent restart during a build, the script console, and `--replace`/`--restore`. With `BASIC=1` it runs against a bare controller (core only, freestyle shell build), for older Jenkins lines whose plugins no longer install.

- `test/ssh.sh`: an SSH Build Agents node with *Java Path* set to the file `install.sh` writes, on a HotSpot 8 and an OpenJ9 8 host whose first security provider is a signed jar (as on Solaris and IBM Java); checks `--check`, that the node comes online, the script console, the signed provider, and a freestyle build.

CI runs these weekly against the latest LTS and weekly Jenkins (`test/ssh.sh` against the LTS).

- `test/unit.sh`: unit tests without Docker or a controller, in plain Java (`test/unit/`) and Python's `unittest` (`test/test_sbom.py`): the `serialVersionUID` pinning against what Java computes, the `CollSer`, `readNBytes` and signed-jar fixes on generated classes, the restart with both Java agents and `--check`, and the SBOM. Needs JDK 11 to 21 and python3; CI runs it on Java 11, 17 and 21 when the code changes.

## Releasing

Run the **Release** workflow (Actions → Release → Run workflow). By default it builds and tests the current Jenkins LTS and weekly, then publishes one release per Jenkins version (tag `jenkins-<version>`) with all three jars, a CycloneDX SBOM per jar, `sha256sums.txt` and signed attestations (see [SECURITY.md](../SECURITY.md#checking-a-release)), and refreshes `index.txt` on the latest release; list other Jenkins image tags to release older versions, or tick *dry run* to build and test only. Older Jenkins lines whose plugins no longer install (for example `2.462.3-lts-jdk17`) go in the *basic* field: they are tested against a bare controller with a freestyle shell build, and their release notes say so. CI itself does not publish; a weekly *Remoting check* workflow opens an issue when the current LTS or weekly ships a remoting version that has no release yet.
