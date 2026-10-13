#!/bin/sh
# Installs agent8 on a build host that only has old Java (8, 11 or 17), and optionally
# swaps it in for the agent.jar the host already uses. It picks the build made for the
# host's Java: agent8-java8.jar, agent8-java11.jar or agent8-java17.jar; it is installed as agent8.jar. POSIX sh: runs on RHEL/CentOS 6,
# AIX (ksh), Solaris (reruns itself with ksh on Solaris 10) and HP-UX.
#
#   sh install.sh --url https://jenkins.example.com/ [--replace /path/to/agent.jar]
#
# Options:
#   --url URL        Jenkins controller; agent8 is matched to its remoting version
#   --version V      remoting version to install instead of asking the controller
#   --replace PATH   swap the agent.jar at PATH for agent8 (a backup is kept), so the
#                    existing start command keeps working unchanged
#   --dir DIR        where to install (default /opt/agent8)
#   --from DIR       offline: take the agent8 jar and jvmdg.jar from DIR, download nothing
#   --java PATH      java to use (default $JAVA_HOME/bin/java, then java on PATH)
#   --target N       which build to install: 8, 11 or 17 (default: from the host's Java)
#   --repo URL       release source (default https://github.com/keelinfra/agent8)
#   --cacert FILE    CA bundle for curl/wget, when the host's own bundle is too old
#   --restore PATH   undo --replace: put back PATH.before-agent8 and remove jvmdg.jar
#
# Old hosts often have a CA bundle too old for github.com. If curl/wget cannot verify a
# download, the script downloads it with Java instead, which has its own trust store.
# Solaris 10's /bin/sh is a Bourne shell without $(...): rerun with a POSIX shell.
if (eval 'test "$(echo x)" = x') 2>/dev/null; then :; else
    for sh in /usr/xpg4/bin/sh /bin/ksh /usr/bin/ksh; do
        if [ -x $sh ]; then exec $sh "$0" "$@"; fi
    done
    echo "agent8: run this script with ksh" >&2; exit 1
fi
set -e

URL='' RESTORE='' CACERT='' TARGET='' VERSION='' REPLACE='' DIR=/opt/agent8 FROM='' JAVA='' REPO=https://github.com/keelinfra/agent8
while [ $# -gt 0 ]; do
    case $1 in
        --url) URL=$2; shift 2 ;;
        --version) VERSION=$2; shift 2 ;;
        --replace) REPLACE=$2; shift 2 ;;
        --dir) DIR=$2; shift 2 ;;
        --from) FROM=$2; shift 2 ;;
        --java) JAVA=$2; shift 2 ;;
        --target) TARGET=$2; shift 2 ;;
        --repo) REPO=$2; shift 2 ;;
        --cacert) CACERT=$2; shift 2 ;;
        --restore) RESTORE=$2; shift 2 ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "agent8: unknown option $1 (see --help)" >&2; exit 2 ;;
    esac
done

die() { echo "agent8: $*" >&2; exit 1; }
say() { echo "agent8: $*"; }
# Portable to Bourne-derived shells on AIX, Solaris and HP-UX: no `command -v`, no `!`,
# no head/tail options, no GNU-only flags. CI runs shellcheck -s sh on this file.
have() { type "$1" >/dev/null 2>&1; }
on_path() { # name -> prints the first executable on PATH
    old_ifs=$IFS; IFS=:
    for d in $PATH; do
        if [ -n "$d" ] && [ -f "$d/$1" ] && [ -x "$d/$1" ]; then IFS=$old_ifs; echo "$d/$1"; return 0; fi
    done
    IFS=$old_ifs; return 1
}

if [ -n "$RESTORE" ]; then
    [ -f "$RESTORE.before-agent8" ] || die "no $RESTORE.before-agent8 to restore"
    mv "$RESTORE.before-agent8" "$RESTORE"
    rm -f "$(dirname "$RESTORE")/jvmdg.jar"
    say "restored the original $RESTORE; restart the agent with its usual command"
    exit 0
fi

# Java
if [ -z "$JAVA" ]; then
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA=$JAVA_HOME/bin/java
    else JAVA=$(on_path java) || die "no java found; pass --java /path/to/bin/java"
    fi
fi
[ -x "$JAVA" ] || die "$JAVA is not executable"
JV=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | sed -n '1p')
say "using $JAVA ($JV)"
case $JV in
    1.[0-7]*) die "Java $JV is too old; agent8 needs Java 8 or newer" ;;
    1.*) JMAJOR=8 ;;
    *) JMAJOR=$(echo "$JV" | sed 's/[^0-9].*//') ;;
esac
# The closest build at or below this Java; the Java 8 build is the fallback.
if [ "$JMAJOR" -ge 17 ] 2>/dev/null; then ASSET=agent8-java17.jar
elif [ "$JMAJOR" -ge 11 ] 2>/dev/null; then ASSET=agent8-java11.jar
else ASSET=agent8-java8.jar
fi
case $TARGET in
    '') ;;
    8|11|17) ASSET=agent8-java$TARGET.jar ;;
    *) die "--target must be 8, 11 or 17" ;;
esac
WANT=$ASSET

WORK=${TMPDIR:-/tmp}/agent8.$$
mkdir "$WORK" || die "cannot create $WORK"
trap 'rm -rf "$WORK"' 0

fetch() { # url file -> 0 if downloaded
    if have curl; then
        curl -fsSL ${CACERT:+--cacert "$CACERT"} -o "$2" "$1" 2>/dev/null && return 0
    elif have wget; then
        wget -q ${CACERT:+--ca-certificate="$CACERT"} -O "$2" "$1" 2>/dev/null && return 0
    fi
    java_fetch "$1" "$2"
}

# tools/Fetch.java compiled for Java 8 (javac --release 8 -g:none), base64. It downloads
# with Java's trust store, which is usually newer than an old host's CA bundle.
FETCH_CLASS='
yv66vgAAADQAcAoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClW
BwAIAQAMamF2YS9uZXQvVVJMCgAHAAoMAAUACwEAFShMamF2YS9sYW5nL1N0cmluZzspVgoABwAN
DAAOAA8BAA5vcGVuQ29ubmVjdGlvbgEAGigpTGphdmEvbmV0L1VSTENvbm5lY3Rpb247BwARAQAa
amF2YS9uZXQvSHR0cFVSTENvbm5lY3Rpb24KABAAEwwAFAAVAQAac2V0SW5zdGFuY2VGb2xsb3dS
ZWRpcmVjdHMBAAQoWilWCAAXAQAKVXNlci1BZ2VudAgAGQEADmFnZW50OC1pbnN0YWxsCgAQABsM
ABwAHQEAEnNldFJlcXVlc3RQcm9wZXJ0eQEAJyhMamF2YS9sYW5nL1N0cmluZztMamF2YS9sYW5n
L1N0cmluZzspVgoAEAAfDAAgACEBAA9nZXRSZXNwb25zZUNvZGUBAAMoKUkIACMBAAhMb2NhdGlv
bgoAEAAlDAAmACcBAA5nZXRIZWFkZXJGaWVsZAEAJihMamF2YS9sYW5nL1N0cmluZzspTGphdmEv
bGFuZy9TdHJpbmc7CgAHACkMAAUAKgEAIyhMamF2YS9uZXQvVVJMO0xqYXZhL2xhbmcvU3RyaW5n
OylWCQAsAC0HAC4MAC8AMAEAEGphdmEvbGFuZy9TeXN0ZW0BAANlcnIBABVMamF2YS9pby9Qcmlu
dFN0cmVhbTsHADIBABdqYXZhL2xhbmcvU3RyaW5nQnVpbGRlcgoAMQADCgAxADUMADYANwEABmFw
cGVuZAEALShMamF2YS9sYW5nL1N0cmluZzspTGphdmEvbGFuZy9TdHJpbmdCdWlsZGVyOwgAOQEA
BzogSFRUUCAKADEAOwwANgA8AQAcKEkpTGphdmEvbGFuZy9TdHJpbmdCdWlsZGVyOwoAMQA+DAA/
AEABAAh0b1N0cmluZwEAFCgpTGphdmEvbGFuZy9TdHJpbmc7CgBCAEMHAEQMAEUACwEAE2phdmEv
aW8vUHJpbnRTdHJlYW0BAAdwcmludGxuCgAsAEcMAEgASQEABGV4aXQBAAQoSSlWCgAQAEsMAEwA
TQEADmdldElucHV0U3RyZWFtAQAXKClMamF2YS9pby9JbnB1dFN0cmVhbTsHAE8BABhqYXZhL2lv
L0ZpbGVPdXRwdXRTdHJlYW0KAE4ACgMAAQAACgBTAFQHAFUMAFYAVwEAE2phdmEvaW8vSW5wdXRT
dHJlYW0BAARyZWFkAQAFKFtCKUkKAFkAWgcAWwwAXABdAQAUamF2YS9pby9PdXRwdXRTdHJlYW0B
AAV3cml0ZQEAByhbQklJKVYKAFkAXwwAYAAGAQAFY2xvc2UIAGIBABQ6IHRvbyBtYW55IHJlZGly
ZWN0cwcAZAEABUZldGNoAQAEQ29kZQEABG1haW4BABYoW0xqYXZhL2xhbmcvU3RyaW5nOylWAQAN
U3RhY2tNYXBUYWJsZQcAagEAAltCBwBsAQATW0xqYXZhL2xhbmcvU3RyaW5nOwEACkV4Y2VwdGlv
bnMHAG8BABNqYXZhL2xhbmcvRXhjZXB0aW9uACEAYwACAAAAAAACAAEABQAGAAEAZQAAABEAAQAB
AAAABSq3AAGxAAAAAAAJAGYAZwACAGUAAAEjAAUACQAAAOK7AAdZKgMytwAJTAM9HAiiALMrtgAM
wAAQTi0DtgASLRIWEhi2ABottgAeNgQVBBEBLKEAJhUEEQGQogAeLRIitgAkxgAVuwAHWSstEiK2
ACS3AChMpwBnFQQRAMifACeyACu7ADFZtwAzKgMytgA0Eji2ADQVBLYAOrYAPbYAQQS4AEYttgBK
OgW7AE5ZKgQytwBQOgYSUbwIOgcZBRkHtgBSWTYIngAQGQYZBwMVCLYAWKf/6RkGtgBesYQCAaf/
TrIAK7sAMVm3ADMqAzK2ADQSYbYANLYAPbYAQQS4AEaxAAAAAQBoAAAALwAH/QANBwAHAf0ASgcA
EAEr/gAXBwBTBwBZBwBpGf8ABQADBwBrBwAHAQAA+gAFAG0AAAAEAAEAbgAA
'
java_fetch() { # url file -> 0 if downloaded
    if [ ! -f "$WORK/fetch/Fetch.class" ]; then
        mkdir -p "$WORK/fetch"
        echo "$FETCH_CLASS" > "$WORK/fetch/Fetch.b64"
        { base64 -d < "$WORK/fetch/Fetch.b64" || openssl base64 -d < "$WORK/fetch/Fetch.b64" ||
          perl -MMIME::Base64 -e 'print decode_base64(join "", <STDIN>)' < "$WORK/fetch/Fetch.b64"
        } > "$WORK/fetch/Fetch.class" 2>/dev/null || return 1
    fi
    "$JAVA" -cp "$WORK/fetch" Fetch "$1" "$2" 2>/dev/null
}
release() { # tag -> as get_release, retried: assets are briefly missing while a release is re-published
    get_release "$1" && return 0
    sleep 5; get_release "$1" && return 0
    sleep 15; get_release "$1"
}
get_release() { # tag -> downloads the agent8 jar for this Java (as agent8.jar) and jvmdg.jar into $WORK
    if [ "$1" = latest ]; then base=$REPO/releases/latest/download; else base=$REPO/releases/download/$1; fi
    fetch "$base/jvmdg.jar" "$WORK/jvmdg.jar" || return 1
    ASSET=$WANT
    if [ "$ASSET" != agent8-java8.jar ] && fetch "$base/$ASSET" "$WORK/agent8.jar"; then return 0; fi
    ASSET=agent8-java8.jar
    fetch "$base/$ASSET" "$WORK/agent8.jar"
}

# The agent8 jar (kept as $WORK/agent8.jar) and jvmdg.jar
if [ -n "$FROM" ]; then
    [ -f "$FROM/$ASSET" ] || [ -n "$TARGET" ] || ASSET=agent8-java8.jar
    if cp "$FROM/$ASSET" "$WORK/agent8.jar" && cp "$FROM/jvmdg.jar" "$WORK/"; then :; else
        die "no $ASSET and jvmdg.jar in $FROM"
    fi
else
    if [ -z "$VERSION" ] && [ -n "$URL" ]; then
        # The controller's agent.jar names its remoting version; any agent8 jar can read it.
        release latest || die "cannot download from $REPO; try --cacert FILE, or download the release files elsewhere and use --from DIR"
        fetch "${URL%/}/jnlpJars/agent.jar" "$WORK/controller-agent.jar" ||
            die "cannot download ${URL%/}/jnlpJars/agent.jar"
        VERSION=$("$JAVA" -cp "$WORK/agent8.jar" io.jenkins.agent8.Main --remoting-version "$WORK/controller-agent.jar")
        say "controller uses remoting $VERSION"
    fi
    if [ -n "$VERSION" ]; then
        if release "remoting-$VERSION"; then :; else
            say "no agent8 build for remoting $VERSION yet; using the latest build"
            release latest || die "cannot download from $REPO; try --cacert FILE, or download the release files elsewhere and use --from DIR"
        fi
    else
        release latest || die "cannot download from $REPO; try --cacert FILE, or download the release files elsewhere and use --from DIR"
    fi
fi

# Check that this Java runs it before touching anything.
# (No tail -n or head -n: Solaris's do not support them.)
if "$JAVA" -jar "$WORK/agent8.jar" -version > "$WORK/version.out" 2>&1; then :; else
    cat "$WORK/version.out" >&2
    die "$ASSET does not start with $JAVA (output above)"
fi
GOT=$(sed -n '$p' "$WORK/version.out")
say "$ASSET runs remoting $GOT on this host"

mkdir -p "$DIR"
cp "$WORK/agent8.jar" "$WORK/jvmdg.jar" "$DIR/"
say "installed $DIR/agent8.jar and $DIR/jvmdg.jar"

if [ -n "$REPLACE" ]; then
    target_dir=$(dirname "$REPLACE")
    [ -d "$target_dir" ] || die "$target_dir does not exist"
    # Copy next to the target, then rename over it: a running agent keeps the old file
    # open and is not disturbed. The original jar is kept once, on the first install.
    cp "$WORK/agent8.jar" "$REPLACE.agent8-new"
    cp "$WORK/jvmdg.jar" "$target_dir/jvmdg.jar.agent8-new"
    if [ -f "$REPLACE" ] && [ ! -f "$REPLACE.before-agent8" ]; then
        mv "$REPLACE" "$REPLACE.before-agent8"
        say "kept the original jar as $REPLACE.before-agent8 (undo: --restore $REPLACE)"
    fi
    mv "$REPLACE.agent8-new" "$REPLACE"
    mv "$target_dir/jvmdg.jar.agent8-new" "$target_dir/jvmdg.jar"
    say "replaced $REPLACE; restart the agent with its usual command"
else
    say "start the agent as usual, with this jar:"
    say "  $JAVA -jar $DIR/agent8.jar -url <controller> -secret <secret> -name <node> -workDir <dir>"
fi
