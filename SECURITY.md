# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security problem in Agent8. Email **hello@keelinfra.io** with the subject **"Agent8 security report"**, and include:

- what is affected (`install.sh`, the Ansible playbook, the agent8 jars or the release workflow) and the release tag, for example `jenkins-2.580.1`;
- how to reproduce it, and what an attacker gains.

We answer within two business days, agree on a fix and a disclosure date with you, and credit you in the release notes unless you prefer not to be named.

## Scope

Agent8 repackages the Jenkins agent (remoting) for older Java. A vulnerability in remoting itself is fixed upstream by the Jenkins project: report it to them (https://www.jenkins.io/security/), and Agent8 ships the fix in a new release once the controller's remoting version includes it. Problems specific to Agent8, such as the downgrade, the run-time Java agent, `install.sh` downloads and checksum checks, or the release process, belong here.
