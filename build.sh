#!/usr/bin/env bash
# Builds Jenkins agent jars for hosts with an older Java than the controller needs.
#
#   ./build.sh <remoting version> [targets]   download remoting.jar from repo.jenkins-ci.org
#   ./build.sh path/to/agent.jar [targets]    use a jar from <jenkins>/jnlpJars/agent.jar
#
# targets: Java versions to build for, from 8, 11 and 17 (default: 8).
# Output in dist/: agent8-java8.jar, agent8-java11.jar, agent8-java17.jar (as requested on the command line), and jvmdg.jar.
# Every jar runs on its target Java and anything newer.
# Needs JDK 17+, git, curl, unzip, python3.
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
    if ! echo "$2  $3" | sha256sum -c --quiet >/dev/null 2>&1; then
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

# 1. The original agent jar and the remoting source of the same version.
SOURCE=${1:?usage: build.sh <remoting version> | <agent.jar> [8] [11] [17]}
shift
TARGETS=("${@:-8}")
for t in "${TARGETS[@]}"; do
    [[ $t =~ ^(8|11|17)$ ]] || { echo "unsupported target Java $t; use 8, 11 or 17" >&2; exit 2; }
done
if [[ -f $SOURCE ]]; then
    cp "$SOURCE" "$WORK/agent.jar"
else
    curl -fsSL --retry 3 -o "$WORK/agent.jar" \
        "https://repo.jenkins-ci.org/public/org/jenkins-ci/main/remoting/$SOURCE/remoting-$SOURCE.jar"
fi
VERSION=$(unzip -p "$WORK/agent.jar" META-INF/MANIFEST.MF | sed -n 's/^Version: *//p' | tr -d '\r')
echo "remoting $VERSION"
git clone -q --depth 1 --branch "$VERSION" https://github.com/jenkinsci/remoting.git "$WORK/remoting"

# 2. Patch remoting where Java 8 lacks an API that cannot be stubbed, and
#    recompile only the patched classes against the original jar.
CHANGED=()
for p in "$ROOT"/patches/*.patch; do
    if git -C "$WORK/remoting" apply "$p" 2>/dev/null; then :
    elif git -C "$WORK/remoting" apply --reverse --check "$p" 2>/dev/null; then
        echo "$(basename "$p"): not needed, remoting $VERSION already has it"
    else
        git -C "$WORK/remoting" apply "$p" # fails, showing why
    fi
done
mapfile -t CHANGED < <(git -C "$WORK/remoting" diff --name-only)
mkdir -p "$WORK/patched"
cp "$WORK/agent.jar" "$WORK/agent-patched.jar"
if [[ ${#CHANGED[@]} -gt 0 ]]; then
    for f in "${CHANGED[@]}"; do
        # agent.jar shades remoting's own dependencies
        sed -i 's/^import org\.jenkinsci\.constant_pool_scanner/import io.jenkins.remoting.shaded.org.jenkinsci.constant_pool_scanner/' \
            "$WORK/remoting/$f"
    done
    javac -nowarn --release 17 -cp "$WORK/agent.jar:$WORK/deps/spotbugs-annotations.jar" -d "$WORK/patched" \
        "${CHANGED[@]/#/$WORK/remoting/}"
    (cd "$WORK/patched" && jar uf "$WORK/agent-patched.jar" $(find . -name '*.class'))
fi

# 3. The Java agent (Java 8 bytecode) with ASM and the compat classes inside.
mkdir -p "$WORK/agent8/agent8-compat"
javac -nowarn --release 8 -d "$WORK/agent8/agent8-compat" $(find compat -name "*.java")
javac -nowarn --release 8 -cp "$WORK/deps/asm.jar:$WORK/deps/asm-commons.jar:$WORK/deps/asm-tree.jar:$WORK/agent.jar" -d "$WORK/agent8" \
    src/io/jenkins/agent8/*.java
(cd "$WORK/agent8" && for a in asm asm-commons asm-tree; do unzip -qo "$WORK/deps/$a.jar" 'org/*'; done)

# 4. Pin serialVersionUIDs once; downgrade per target.
java -cp "$WORK/agent8" io.jenkins.agent8.Agent8 "$WORK/agent-patched.jar" "$WORK/agent-pinned.jar"
cp "$WORK/deps/jvmdg.jar" "$DIST/jvmdg.jar"

for target in "${TARGETS[@]}"; do
    case $target in
        8) jar=agent8-java8.jar; cv=52 ;;
        11) jar=agent8-java11.jar; cv=55 ;;
        17) jar=agent8-java17.jar; cv=61 ;;
    esac
    out=$DIST/$jar
    java -jar "$WORK/deps/jvmdg.jar" -c $cv downgrade -t "$WORK/agent-pinned.jar" "$WORK/agent-dg-$target.jar" 2>&1 |
        grep -v 'org/osgi' || true
    java -jar "$WORK/deps/jvmdg.jar" -c $cv shade -p io/jenkins/remoting/jvmdg -t "$WORK/agent-dg-$target.jar" "$out" 2>&1 |
        grep -v 'org/osgi' || true

    # 5. Add the Java agent and the drop-in launcher to the same jar.
    (cd "$WORK/agent8" && jar uf "$out" .)
    python3 - "$out" "$VERSION" "$target" "$cv" <<'PY'
import os, sys, zipfile
jar, version, target, cv = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
src = zipfile.ZipFile(jar)
bad = [n for n in src.namelist() if n.endswith(".class") and not n.endswith("module-info.class")
       and int.from_bytes(src.read(n)[6:8], "big") > cv]
if bad:
    sys.exit("classes above Java %s in %s: %s" % (target, jar, bad[:5]))
lines = [l for l in src.read("META-INF/MANIFEST.MF").decode().splitlines() if l and not l.startswith("Main-Class:")]
lines += ["Main-Class: io.jenkins.agent8.Main", "Premain-Class: io.jenkins.agent8.Agent8", "Can-Retransform-Classes: true",
          "Agent8-Remoting-Version: " + version, "Agent8-Target-Java: " + target]
tmp = jar + ".tmp"
with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as out:
    for i in src.infolist():
        data = ("\r\n".join(lines) + "\r\n").encode() if i.filename == "META-INF/MANIFEST.MF" else src.read(i)
        out.writestr(i, data)
src.close()
os.replace(tmp, jar)
PY
    echo "built $out (Java $target and newer) for remoting $VERSION"
done
echo "run (jvmdg.jar next to the jar): java -jar agent8-javaN.jar -url ... -secret ... -name ..."
