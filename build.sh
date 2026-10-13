#!/usr/bin/env bash
# Builds a Java 8 compatible Jenkins agent jar.
#
#   ./build.sh <remoting version>     download remoting.jar from repo.jenkins-ci.org
#   ./build.sh path/to/agent.jar      use a jar from <jenkins>/jnlpJars/agent.jar
#
# Output: dist/agent8.jar and dist/jvmdg.jar. Needs JDK 17+, git, curl, unzip.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
WORK=$ROOT/target
DIST=$ROOT/dist
DEPS=${AGENT8_DEPS:-$ROOT/.deps}
rm -rf "$WORK" "$DIST"
mkdir -p "$WORK" "$DIST" "$DEPS"
ln -s "$DEPS" "$WORK/deps"

# Dependencies, pinned by checksum and cached in .deps.
fetch() { # url sha256 file
    if ! echo "$2  $3" | sha256sum -c --quiet 2>/dev/null; then
        curl -fsSL --retry 5 --retry-all-errors -o "$3" "$1"
        echo "$2  $3" | sha256sum -c --quiet
    fi
}
MC=https://repo1.maven.org/maven2
fetch $MC/xyz/wagyourtail/jvmdowngrader/jvmdowngrader/2.0.1/jvmdowngrader-2.0.1-all.jar \
    60a5bd57846fb4be1756fb93e6f63b3d9a63c2759a63e763c44553b086326107 "$WORK/deps/jvmdg.jar"
fetch $MC/org/ow2/asm/asm/9.8/asm-9.8.jar \
    876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051 "$WORK/deps/asm.jar"
fetch $MC/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar \
    3301a1c1cb4c59fcc5292648dac1d7c5aed4c0f067dfbe88873b8cdfe77404f4 "$WORK/deps/asm-commons.jar"
fetch $MC/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar \
    14b7880cb7c85eed101e2710432fc3ffb83275532a6a894dc4c4095d49ad59f1 "$WORK/deps/asm-tree.jar"
fetch $MC/com/github/spotbugs/spotbugs-annotations/4.10.3/spotbugs-annotations-4.10.3.jar \
    3fabaf088f8cd10803cc87dba4666cf874bdcd2e07ad154ed3fe403647ce3f2a "$WORK/deps/spotbugs-annotations.jar"
JVMDG="java -jar $WORK/deps/jvmdg.jar -c 52"

# 1. The original agent jar and the remoting source of the same version.
if [[ -f ${1:?usage: build.sh <remoting version> | <agent.jar>} ]]; then
    cp "$1" "$WORK/agent.jar"
else
    curl -fsSL --retry 3 -o "$WORK/agent.jar" \
        "https://repo.jenkins-ci.org/public/org/jenkins-ci/main/remoting/$1/remoting-$1.jar"
fi
VERSION=$(unzip -p "$WORK/agent.jar" META-INF/MANIFEST.MF | sed -n 's/^Version: *//p' | tr -d '\r')
echo "remoting $VERSION"
git clone -q --depth 1 --branch "$VERSION" https://github.com/jenkinsci/remoting.git "$WORK/remoting"

# 2. Patch remoting where Java 8 lacks an API that cannot be stubbed, and
#    recompile only the patched classes against the original jar.
CHANGED=()
for p in "$ROOT"/patches/*.patch; do
    git -C "$WORK/remoting" apply "$p"
done
mapfile -t CHANGED < <(git -C "$WORK/remoting" diff --name-only)
mkdir -p "$WORK/patched"
for f in "${CHANGED[@]}"; do
    # agent.jar shades remoting's own dependencies
    sed -i 's/^import org\.jenkinsci\.constant_pool_scanner/import io.jenkins.remoting.shaded.org.jenkinsci.constant_pool_scanner/' \
        "$WORK/remoting/$f"
done
javac -nowarn --release 17 -cp "$WORK/agent.jar:$WORK/deps/spotbugs-annotations.jar" -d "$WORK/patched" \
    "${CHANGED[@]/#/$WORK/remoting/}"
cp "$WORK/agent.jar" "$WORK/agent-patched.jar"
(cd "$WORK/patched" && jar uf "$WORK/agent-patched.jar" $(find . -name '*.class'))

# 3. The Java agent (Java 8 bytecode) with ASM and the compat classes inside.
mkdir -p "$WORK/agent8/agent8-compat"
javac -nowarn --release 8 -d "$WORK/agent8/agent8-compat" $(find compat -name "*.java")
javac -nowarn --release 8 -cp "$WORK/deps/asm.jar:$WORK/deps/asm-commons.jar:$WORK/agent.jar" -d "$WORK/agent8" \
    src/io/jenkins/agent8/*.java
(cd "$WORK/agent8" && for a in asm asm-commons asm-tree; do unzip -qo "$WORK/deps/$a.jar" 'org/*'; done)

# 4. Pin serialVersionUIDs, then downgrade remoting to class version 52.
java -cp "$WORK/agent8" io.jenkins.agent8.Agent8 "$WORK/agent-patched.jar" "$WORK/agent-pinned.jar"
$JVMDG downgrade -t "$WORK/agent-pinned.jar" "$WORK/agent-dg.jar" 2>&1 | grep -v 'org/osgi' || true
$JVMDG shade -p io/jenkins/remoting/jvmdg -t "$WORK/agent-dg.jar" "$DIST/agent8.jar" 2>&1 | grep -v 'org/osgi' || true

# 5. Add the Java agent and the drop-in launcher to the same jar.
(cd "$WORK/agent8" && jar uf "$DIST/agent8.jar" .)
python3 - "$DIST/agent8.jar" "$VERSION" <<'PY'
import os, sys, zipfile
jar, version = sys.argv[1], sys.argv[2]
src = zipfile.ZipFile(jar)
lines = [l for l in src.read("META-INF/MANIFEST.MF").decode().splitlines() if l and not l.startswith("Main-Class:")]
lines += ["Main-Class: io.jenkins.agent8.Main", "Premain-Class: io.jenkins.agent8.Agent8",
          "Agent8-Remoting-Version: " + version]
tmp = jar + ".tmp"
with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as out:
    for i in src.infolist():
        data = ("\r\n".join(lines) + "\r\n").encode() if i.filename == "META-INF/MANIFEST.MF" else src.read(i)
        out.writestr(i, data)
src.close()
os.replace(tmp, jar)
PY
cp "$WORK/deps/jvmdg.jar" "$DIST/jvmdg.jar"

python3 - "$DIST/agent8.jar" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
bad = [n for n in z.namelist() if n.endswith(".class") and not n.endswith("module-info.class")
       and int.from_bytes(z.read(n)[6:8], "big") > 52]
if bad:
    sys.exit("classes above Java 8 in agent8.jar: %s" % bad[:5])
PY
echo "built $DIST/agent8.jar for remoting $VERSION"
echo "run (jvmdg.jar next to agent8.jar): java -jar agent8.jar -url ... -secret ... -name ..."
