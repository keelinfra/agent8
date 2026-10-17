# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security problem in Agent8. Email **hello@keelinfra.io** with the subject **"Agent8 security report"**, and include:

- what is affected (`install.sh`, the Ansible playbook, the agent8 jars or the release workflow) and the release tag, for example `jenkins-2.580.1`;
- how to reproduce it, and what an attacker gains.

We answer within two business days, agree on a fix and a disclosure date with you, and credit you in the release notes unless you prefer not to be named.

## Scope

Agent8 repackages the Jenkins agent (remoting) for older Java. A vulnerability in remoting itself is fixed upstream by the Jenkins project: report it to them (https://www.jenkins.io/security/), and Agent8 ships the fix in a new release once the controller's remoting version includes it. Problems specific to Agent8, such as the downgrade, the run-time Java agent, `install.sh` downloads and checksum checks, or the release process, belong here.

## Supported versions

Agent8 is supported until at least **31 December 2031**. Security updates are free, for everyone.

Fixes ship in a new release for each supported Jenkins version; rerun `install.sh` to get one. When a fix ships we publish a GitHub security advisory saying who is affected and what to do.

## Checking a release

Every release carries `sha256sums.txt`, a CycloneDX SBOM per jar (`agent8-javaN.cdx.json`), and signed build-provenance and SBOM attestations. To check a jar came unmodified from this repository's release workflow:

```sh
gh attestation verify agent8-java8.jar --repo keelinfra/agent8
```

## EU Cyber Resilience Act

keelinfra sells services for Agent8, so we treat ourselves as its manufacturer under the CRA ([Regulation (EU) 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847/oj)). Agent8 is a default-class product (not listed in Annex III). Actively exploited vulnerabilities are reported through ENISA's Single Reporting Platform within 24 hours, as the CRA requires since 11 September 2026; vulnerabilities in remoting are reported to the Jenkins project. How we read the CRA, and what is still open before 11 December 2027, is in [keelinfra's CRA notes](https://github.com/keelinfra/keycloak/blob/main/CRA.md).
