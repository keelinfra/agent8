#!/usr/bin/env python3
"""Writes the CycloneDX 1.6 SBOM of one agent8 jar.

  tools/sbom.py <agent8 jar> <agent.jar it was built from> <deps dir> <out.cdx.json>

What goes in an agent8 jar: Jenkins remoting (read from the jar's manifest), the
libraries remoting itself bundles (read from its META-INF/maven entries), ASM, and the
part of JvmDowngrader that agent8 shades in. jvmdg.jar ships next to the jar, unmodified.
The version is $AGENT8_VERSION (the release tag), or the remoting version.
"""
import datetime
import hashlib
import json
import os
import subprocess
import sys
import uuid
import zipfile

jar, agent_jar, deps, out = sys.argv[1:5]


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def manifest(z):
    lines = z.read("META-INF/MANIFEST.MF").decode().splitlines()
    return dict(l.split(": ", 1) for l in lines if ": " in l)


def maven(group, artifact, version, desc, license=None, path=None, qualifier=""):
    c = {
        "type": "library",
        "bom-ref": f"pkg:maven/{group}/{artifact}@{version}{qualifier}",
        "group": group,
        "name": artifact,
        "version": version,
        "description": desc,
        "purl": f"pkg:maven/{group}/{artifact}@{version}{qualifier}",
    }
    if license:
        c["licenses"] = [{"license": license}]
    if path:
        c["hashes"] = [{"alg": "SHA-256", "content": sha256(path)}]
    return c


with zipfile.ZipFile(jar) as z:
    m = manifest(z)
remoting, target = m["Agent8-Remoting-Version"], m["Agent8-Target-Java"]
jvmdg = m.get("JvmDowngrader-Version", "2.0.1")

components = [
    maven("org.jenkins-ci.main", "remoting", remoting,
          "Jenkins agent (remoting), downgraded to Java %s bytecode" % target,
          {"id": "MIT"}, agent_jar),
]
# Libraries remoting bundles, as its own jar lists them.
with zipfile.ZipFile(agent_jar) as z:
    for n in sorted(z.namelist()):
        if n.startswith("META-INF/maven/") and n.endswith("/pom.properties"):
            p = dict(l.split("=", 1) for l in z.read(n).decode().splitlines() if "=" in l and not l.startswith("#"))
            if p.get("artifactId") == "remoting":
                continue
            components.append(maven(p["groupId"], p["artifactId"], p["version"], "Bundled inside Jenkins remoting"))
for a in ("asm", "asm-commons", "asm-tree"):
    components.append(maven("org.ow2.asm", a, "9.8", "Used by agent8's Java agent",
                            {"id": "BSD-3-Clause"}, os.path.join(deps, a + ".jar")))
components.append(maven("xyz.wagyourtail.jvmdowngrader", "jvmdowngrader", jvmdg,
                        "JvmDowngrader: its runtime API is shaded into the jar; jvmdg.jar ships next to it, unmodified",
                        {"name": "LGPL-2.1"}, os.path.join(deps, "jvmdg.jar"), "?classifier=all"))
seen = set()  # bom-refs must be unique
components = [c for c in components if not (c["bom-ref"] in seen or seen.add(c["bom-ref"]))]

try:
    commit = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True,
                            cwd=os.path.dirname(os.path.abspath(__file__))).stdout.strip()
except OSError:
    commit = ""
version = os.environ.get("AGENT8_VERSION") or remoting
name = os.path.basename(jar)
main = {
    "type": "application",
    "bom-ref": "agent8",
    "supplier": {"name": "keelinfra", "url": ["https://keelinfra.io"]},
    "name": name[:-4] if name.endswith(".jar") else name,
    "version": version,
    "description": "Jenkins agent for hosts on Java %s and newer" % target,
    "licenses": [{"license": {"id": "MIT"}}],
    "hashes": [{"alg": "SHA-256", "content": sha256(jar)}],
    "externalReferences": [{"type": "vcs", "url": "https://github.com/keelinfra/agent8"}],
    "properties": [{"name": "agent8:remoting-version", "value": remoting},
                   {"name": "agent8:target-java", "value": target}],
}
if commit:
    main["properties"].append({"name": "agent8:commit", "value": commit})

bom = {
    "bomFormat": "CycloneDX",
    "specVersion": "1.6",
    "serialNumber": "urn:uuid:%s" % uuid.uuid4(),
    "version": 1,
    "metadata": {
        "timestamp": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "component": main,
    },
    "components": components,
    "dependencies": [{"ref": "agent8", "dependsOn": [c["bom-ref"] for c in components]}]
                    + [{"ref": c["bom-ref"]} for c in components],
}
with open(out, "w") as f:
    json.dump(bom, f, indent=2)
    f.write("\n")
