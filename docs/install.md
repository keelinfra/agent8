# Installing agent8

On the build host, with its old Java (8, 11 or 17), and the controller's URL:

```sh
curl -fsSLO https://raw.githubusercontent.com/keelinfra/agent8/main/install.sh
sh install.sh --url https://jenkins.example.com/ --replace /path/to/agent.jar
```

If that `curl` fails with `SSL certificate problem: unable to get local issuer certificate`, the host's CA bundle is too old to verify GitHub, which is common on old hosts. Do not turn verification off (`-k`). Download `install.sh` on a current machine and copy it over (`scp install.sh host:`), or point curl at a current bundle, for example one copied from a current machine (`curl --cacert cacert.pem ...`; `install.sh --cacert FILE` passes it on). `install.sh` itself falls back to Java's own trust store when curl or wget cannot verify a download. If that fails too, or the host has no internet access, download the release files elsewhere and use `--from DIR` (below).

`install.sh` asks the controller for its remoting version, downloads the matching agent8 jar for the host's Java (`agent8-java8.jar`, `agent8-java11.jar` or `agent8-java17.jar`) and `jvmdg.jar` from this repository's releases, checks them against the release's `sha256sums.txt` (with `sha256sum`, `shasum`, `digest` or `openssl`, or with Java when the host has none of these), checks that they start with the host's Java and can use its security providers, key store and TLS (`java -jar agent8.jar --check`, which you can also run yourself), and swaps them in for the `agent.jar` the host runs today (the old jar is kept as `agent.jar.before-agent8`). Restart the agent with its usual command, for example:

```sh
java -jar /path/to/agent.jar -url https://jenkins.example.com/ -secret <secret> -name <node> -workDir /srv/jenkins
```

Releases are named after the Jenkins version: `jenkins-2.580.1` is agent8 for Jenkins 2.580.1 (your version is at the bottom right of every Jenkins page). What has to match underneath is the agent's remoting version, and several Jenkins versions can ship the same one, so a release also works with every other Jenkins version that ships its remoting version; the release notes name it. You rarely need to pick by hand: `install.sh --url` asks the controller for its remoting version and finds the matching release through `index.txt` on the latest release (one line per release: remoting version, tag).

Nothing else changes: the agent8 jar starts the Java agents it needs itself. With `--replace` it writes only next to the old jar, so it runs as the agent's own user; without it, it installs to `/opt/agent8` (`--dir`). Hosts without internet access: download the agent8 jars, `jvmdg.jar` and `sha256sums.txt` from the [releases](https://github.com/keelinfra/agent8/releases) and pass `--from DIR`. `--target 8|11|17` (Ansible: `agent8_target`) picks a build instead of going by the host's Java. `sh install.sh --help` lists all options.

For many hosts, the Ansible playbook runs the same script and needs no Python on the hosts:

```sh
ansible-playbook -i inventory ansible/agent8.yml \
  -e agent8_controller_url=https://jenkins.example.com/ \
  -e agent8_replace=/opt/jenkins/agent.jar \
  -e "agent8_restart='/etc/init.d/jenkins-agent restart'"
```

For SSH agents pass `-e agent8_dir=/opt/agent8` instead of `agent8_replace` and set each node's *Java Path* to `/opt/agent8/java` (see SSH agents below); Jenkins relaunches them, so no restart command is needed. The other options map one to one: `agent8_version`, `agent8_from`, `agent8_cacert`, `agent8_repo`, `agent8_java`, `agent8_target`, and `agent8_restore` to undo a swap.

**Controller setting, if the Versions Node Monitors plugin (`versioncolumn`) is installed.** Its *JVM Version* monitor takes every agent whose Java is older than the controller's offline, seconds after it connects ("incompatible JVM version between agent and controller"). For agent8 hosts, set it to not disconnect under *Manage Jenkins → Nodes → Configure Node Monitors*, or in configuration as code:

```yaml
jenkins:
  nodeMonitors:
    - jvmVersion:
        ignored: true
```

Its *Remoting Version* monitor also takes agents offline whose remoting version differs from the controller's. `install.sh` installs the matching build when a release exists for the controller's remoting version; when it falls back to the latest build ("no agent8 build for remoting … yet"), ignore that monitor too, or ask for a build for that version.

**Upgrading the controller.** It works as with the stock agent: the controller's restart drops all agents, Pipeline builds resume once their agent is back, and freestyle builds that were running are aborted. The agents reconnect on their own, still with the agent8 jar built for the old controller. Without the versioncolumn plugin that keeps working: CI runs the latest release against the newer weekly controller (remoting 3386 against 3391) through the full test, Pipeline, Git, credentials, abort and restart included. With versioncolumn, its *Remoting Version* monitor takes those agents offline until they match, unless it is set not to disconnect. This only stretches so far: Jenkins refuses agents older than a minimum remoting version that it raises over time, and Jenkins 2.586 already refuses the agent8 build for Jenkins 2.462.3 ("Agent version 3256.3258.v858f3c9a_f69d or newer is required"), so an agent8 jar cannot be pinned through all future controller upgrades. Either way, rerun `install.sh` (or the Ansible playbook) after the upgrade and restart the agent, so it matches the controller again. When a new Jenkins LTS or weekly ships a remoting version that has no agent8 release yet, a weekly workflow opens an issue here.

**The old jar.** On the first `--replace`, the host's original jar is renamed to `agent.jar.before-agent8` (a later run never overwrites it), and agent8 is moved into place under the original name, so a running agent keeps its open file. `sh install.sh --restore /path/to/agent.jar` puts the original back and removes `jvmdg.jar`. Keep the backup until the agent has run builds for a while, then delete it.

`install.sh` sticks to POSIX `sh` commands (no `command -v`, `!`, `tail -n`, `head -n`, `mktemp -d` or GNU-only flags), reruns itself under a POSIX shell (`/usr/xpg4/bin/sh`, else `ksh`) on Solaris 10's Bourne shell, and is checked with `shellcheck -s sh`. CI runs it on CentOS 6 and Ubuntu, and it has been tested on AIX, Solaris and HP-UX. Windows hosts can run a current Java and the stock `agent.jar`, so agent8 does not target them.

The agent host needs a `kill` command on the `PATH`, which every real RHEL 6 and AIX system has.

**SSH agents (SSH Build Agents plugin).** That plugin copies the controller's stock `remoting.jar` to the agent's directory and runs `java -jar remoting.jar` on every launch, so replacing a jar on the host does not last. Install without `--replace`, then set one field on the node:

```sh
sh install.sh --url https://jenkins.example.com/ --dir /opt/agent8
```

*Manage Jenkins → Nodes → the node → Configure → Launch method → Advanced → Java Path*: `/opt/agent8/java`. This is the file `install.sh` wrote, not the host's own `java` (not `/usr/bin/java`, and not empty). The launch log shows which one ran: `Starting agent process: cd "..." && /opt/agent8/java -jar remoting.jar` is right. That file, written by `install.sh`, runs the host's Java with `/opt/agent8/agent8.jar` where the plugin asks for `remoting.jar`, and passes everything else through. No *Prefix Start Agent Command* is needed, and the node's other settings (JVM options, remote root directory, credentials) stay as they are. CI tests this with the SSH Build Agents plugin on HotSpot 8 and OpenJ9 8.

**A host that already serves another controller** (for example while moving jobs from an old controller to a new one): install with its own `--dir` and give the new node its own remote root directory (`-workDir`), so the two agents share nothing. Do not `--replace` the jar the other agent runs, and never the `remoting.jar` an SSH launcher copies (`install.sh` warns about that name).

## Troubleshooting

| You see | Cause and fix |
|---|---|
| `curl: (60) SSL certificate problem` when fetching `install.sh` | The host's CA bundle is too old. Copy `install.sh` from a current machine or use `curl --cacert FILE`; never `-k`. |
| `UnsupportedClassVersionError ... class file version 55.0` (or 61, 65) on an SSH node | The host's own Java ran the stock `remoting.jar`: the launch log shows `&& java -jar remoting.jar` or `&& /usr/bin/java -jar remoting.jar`. Set the node's *Java Path* to `DIR/java`, the file `install.sh` wrote (above). |
| `ERROR: Already connected`, then "Launch failed" | A second launch of the same node started while the first was still coming up, and the first one won; the node is usually online. The first start on a slow host takes a minute or more while JvmDowngrader prepares its own jar ("this may take a minute"); later starts are fast. Click *Launch agent* once and wait. |
| `Failed to monitor for Free Swap Space`, `No suitable implementation found: os.name=HP-UX` | Jenkins' swap monitor has no HP-UX implementation; the stock agent shows the same. Harmless: only that column stays empty. |
| `signer information does not match` / `Signers of ... do not match`, `jks not found` | A signed security provider (IBMJCE on IBM Java, SunPKCS11 on Solaris) broken by JvmDowngrader. Fixed since [#9](https://github.com/keelinfra/agent8/pull/9); rerun `install.sh` to get a release with the fix. |
| The node goes offline seconds after connecting, "incompatible JVM version" | The versioncolumn plugin's JVM Version monitor; set it not to disconnect (above). |
| `Agent version ... or newer is required` | The controller was upgraded past this build; rerun `install.sh`. |

`java -jar agent8.jar --check` prints what works and what does not on a host, without connecting to anything; please include its output, `java -version` and the OS version (`oslevel -s`, `uname -a`) when you [report a problem](https://github.com/keelinfra/agent8/issues).
