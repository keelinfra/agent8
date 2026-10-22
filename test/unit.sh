#!/usr/bin/env bash
# Unit tests: the Java agent (src/) in plain Java, without Docker or a controller, and
# tools/sbom.py with Python's unittest.
#
#   test/unit.sh
#
# Needs JDK 11 to 21 (javac --release 8; the tests read the JDK's own java.util.CollSer),
# curl, unzip and python3. ASM is fetched like build.sh does it, pinned by checksum and
# cached in .deps. hudson.remoting.Launcher is a stand-in (test/unit/hudson/remoting), so
# no remoting jar is needed. Runs on Linux and macOS.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
WORK=$ROOT/target/unit
DEPS=${AGENT8_DEPS:-$ROOT/.deps}
rm -rf "$WORK"
mkdir -p "$WORK/main/agent8-compat" "$WORK/test" "$WORK/fixtures" "$DEPS"

sha256() { if command -v sha256sum >/dev/null; then sha256sum < "$1"; else shasum -a 256 < "$1"; fi | cut -d' ' -f1; }
fetch() { # url sha256 file
    if [[ ! -f $3 || $(sha256 "$3") != "$2" ]]; then
        curl -fsSL --retry 5 --retry-all-errors -o "$3" "$1"
        [[ $(sha256 "$3") = "$2" ]] || { echo "checksum mismatch: $1" >&2; exit 1; }
    fi
}
MC=https://repo1.maven.org/maven2
fetch $MC/org/ow2/asm/asm/9.8/asm-9.8.jar \
    876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051 "$DEPS/asm.jar"
fetch $MC/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar \
    3301a1c1cb4c59fcc5292648dac1d7c5aed4c0f067dfbe88873b8cdfe77404f4 "$DEPS/asm-commons.jar"
fetch $MC/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar \
    14b7880cb7c85eed101e2710432fc3ffb83275532a6a894dc4c4095d49ad59f1 "$DEPS/asm-tree.jar"
ASM=$DEPS/asm.jar:$DEPS/asm-commons.jar:$DEPS/asm-tree.jar

sources() { # dir: the .java files under it, one per line
    find "$1" -name '*.java' | sort
}
COMPAT=()
while IFS= read -r f; do COMPAT+=("$f"); done < <(sources compat)
TESTS=()
while IFS= read -r f; do TESTS+=("$f"); done < <(sources test/unit/io)

# The Java agent as build.sh compiles it, with the launcher stand-in in place of remoting.
javac -nowarn --release 8 -d "$WORK/main/agent8-compat" "${COMPAT[@]}"
javac -nowarn --release 8 -cp "$ASM" -d "$WORK/main" src/io/keelinfra/agent8/*.java test/unit/hudson/remoting/Launcher.java
(cd "$WORK/main" && for a in asm asm-commons asm-tree; do unzip -qo "$DEPS/$a.jar" 'org/*'; done)
javac -nowarn --release 8 -cp "$WORK/main" -d "$WORK/test" "${TESTS[@]}"

# Jars for the tests that start java: agent8.jar with the manifest build.sh writes, and a
# do-nothing Java agent named jvmdg.jar.
printf '%s\n' "Main-Class: io.keelinfra.agent8.Main" "Premain-Class: io.keelinfra.agent8.Agent8" \
    "Can-Retransform-Classes: true" > "$WORK/agent8.mf"
printf '%s\n' "Premain-Class: io.keelinfra.agent8.FakeJvmdg" > "$WORK/jvmdg.mf"
jar cfm "$WORK/fixtures/agent8.jar" "$WORK/agent8.mf" -C "$WORK/main" .
jar cfm "$WORK/fixtures/jvmdg.jar" "$WORK/jvmdg.mf" -C "$WORK/test" io/keelinfra/agent8/FakeJvmdg.class

unset AGENT8_JVMDG
status=0
java -cp "$WORK/main:$WORK/test" -Dunit.fixtures="$WORK/fixtures" io.keelinfra.agent8.UnitTests || status=1
python3 -m unittest discover -s test -p 'test_*.py' || status=1
exit $status
