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
#   --dir DIR        where to install (default /opt/agent8; with --replace, only if given)
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

URL='' DIR_SET='' RESTORE='' CACERT='' TARGET='' VERSION='' REPLACE='' DIR=/opt/agent8 FROM='' JAVA='' REPO=https://github.com/keelinfra/agent8
while [ $# -gt 0 ]; do
    case $1 in
        --url) URL=$2; shift 2 ;;
        --version) VERSION=$2; shift 2 ;;
        --replace) REPLACE=$2; shift 2 ;;
        --dir) DIR=$2; DIR_SET=1; shift 2 ;;
        --from) FROM=$2; shift 2 ;;
        --java) JAVA=$2; shift 2 ;;
        --target) TARGET=$2; shift 2 ;;
        --repo) REPO=$2; shift 2 ;;
        --cacert) CACERT=$2; shift 2 ;;
        --restore) RESTORE=$2; shift 2 ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
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
yv66vgAAADQAnQoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClW
CAAIAQAILS1zaGEyNTYKAAoACwcADAwADQAOAQAQamF2YS9sYW5nL1N0cmluZwEABmVxdWFscwEA
FShMamF2YS9sYW5nL09iamVjdDspWggAEAEAB1NIQS0yNTYKABIAEwcAFAwAFQAWAQAbamF2YS9z
ZWN1cml0eS9NZXNzYWdlRGlnZXN0AQALZ2V0SW5zdGFuY2UBADEoTGphdmEvbGFuZy9TdHJpbmc7
KUxqYXZhL3NlY3VyaXR5L01lc3NhZ2VEaWdlc3Q7BwAYAQAXamF2YS9pby9GaWxlSW5wdXRTdHJl
YW0KABcAGgwABQAbAQAVKExqYXZhL2xhbmcvU3RyaW5nOylWAwABAAAKAB4AHwcAIAwAIQAiAQAT
amF2YS9pby9JbnB1dFN0cmVhbQEABHJlYWQBAAUoW0IpSQoAEgAkDAAlACYBAAZ1cGRhdGUBAAco
W0JJSSlWCgAeACgMACkABgEABWNsb3NlBwArAQAXamF2YS9sYW5nL1N0cmluZ0J1aWxkZXIKACoA
AwoAEgAuDAAvADABAAZkaWdlc3QBAAQoKVtCCAAyAQAEJTAyeAoANAA1BwA2DAA3ADgBAA5qYXZh
L2xhbmcvQnl0ZQEAB3ZhbHVlT2YBABMoQilMamF2YS9sYW5nL0J5dGU7CgAKADoMADsAPAEABmZv
cm1hdAEAOShMamF2YS9sYW5nL1N0cmluZztbTGphdmEvbGFuZy9PYmplY3Q7KUxqYXZhL2xhbmcv
U3RyaW5nOwoAKgA+DAA/AEABAAZhcHBlbmQBAC0oTGphdmEvbGFuZy9TdHJpbmc7KUxqYXZhL2xh
bmcvU3RyaW5nQnVpbGRlcjsJAEIAQwcARAwARQBGAQAQamF2YS9sYW5nL1N5c3RlbQEAA291dAEA
FUxqYXZhL2lvL1ByaW50U3RyZWFtOwoASABJBwBKDABLAEwBABNqYXZhL2lvL1ByaW50U3RyZWFt
AQAHcHJpbnRsbgEAFShMamF2YS9sYW5nL09iamVjdDspVgcATgEADGphdmEvbmV0L1VSTAoATQAa
CgBNAFEMAFIAUwEADm9wZW5Db25uZWN0aW9uAQAaKClMamF2YS9uZXQvVVJMQ29ubmVjdGlvbjsH
AFUBABpqYXZhL25ldC9IdHRwVVJMQ29ubmVjdGlvbgoAVABXDABYAFkBABpzZXRJbnN0YW5jZUZv
bGxvd1JlZGlyZWN0cwEABChaKVYIAFsBAApVc2VyLUFnZW50CABdAQAOYWdlbnQ4LWluc3RhbGwK
AFQAXwwAYABhAQASc2V0UmVxdWVzdFByb3BlcnR5AQAnKExqYXZhL2xhbmcvU3RyaW5nO0xqYXZh
L2xhbmcvU3RyaW5nOylWCgBUAGMMAGQAZQEAD2dldFJlc3BvbnNlQ29kZQEAAygpSQgAZwEACExv
Y2F0aW9uCgBUAGkMAGoAawEADmdldEhlYWRlckZpZWxkAQAmKExqYXZhL2xhbmcvU3RyaW5nOylM
amF2YS9sYW5nL1N0cmluZzsKAE0AbQwABQBuAQAjKExqYXZhL25ldC9VUkw7TGphdmEvbGFuZy9T
dHJpbmc7KVYJAEIAcAwAcQBGAQADZXJyCABzAQAHOiBIVFRQIAoAKgB1DAA/AHYBABwoSSlMamF2
YS9sYW5nL1N0cmluZ0J1aWxkZXI7CgAqAHgMAHkAegEACHRvU3RyaW5nAQAUKClMamF2YS9sYW5n
L1N0cmluZzsKAEgAfAwASwAbCgBCAH4MAH8AgAEABGV4aXQBAAQoSSlWCgBUAIIMAIMAhAEADmdl
dElucHV0U3RyZWFtAQAXKClMamF2YS9pby9JbnB1dFN0cmVhbTsHAIYBABhqYXZhL2lvL0ZpbGVP
dXRwdXRTdHJlYW0KAIUAGgoAiQCKBwCLDACMACYBABRqYXZhL2lvL091dHB1dFN0cmVhbQEABXdy
aXRlCgCJACgIAI8BABQ6IHRvbyBtYW55IHJlZGlyZWN0cwcAkQEABUZldGNoAQAEQ29kZQEABG1h
aW4BABYoW0xqYXZhL2xhbmcvU3RyaW5nOylWAQANU3RhY2tNYXBUYWJsZQcAlwEAAltCBwCZAQAT
W0xqYXZhL2xhbmcvU3RyaW5nOwEACkV4Y2VwdGlvbnMHAJwBABNqYXZhL2xhbmcvRXhjZXB0aW9u
ACEAkAACAAAAAAACAAEABQAGAAEAkgAAABEAAQABAAAABSq3AAGxAAAAAAAJAJMAlAACAJIAAAHe
AAYACQAAAWgqAzISB7YACZkAfhIPuAARTLsAF1kqBDK3ABlNEhy8CE4sLbYAHVk2BJ4ADistAxUE
tgAjp//tLLYAJ7sAKlm3ACw6BCu2AC06BRkFvjYGAzYHFQcVBqIAJxkFFQczNggZBBIxBL0AAlkD
FQi4ADNTuAA5tgA9V4QHAaf/2LIAQRkEtgBHsbsATVkqAzK3AE9MAz0cCKIAsyu2AFDAAFROLQO2
AFYtEloSXLYAXi22AGI2BBUEEQEsoQAmFQQRAZCiAB4tEma2AGjGABW7AE1ZKy0SZrYAaLcAbEyn
AGcVBBEAyJ8AJ7IAb7sAKlm3ACwqAzK2AD0ScrYAPRUEtgB0tgB3tgB7BLgAfS22AIE6BbsAhVkq
BDK3AIc6BhIcvAg6BxkFGQe2AB1ZNgieABAZBhkHAxUItgCIp//pGQa2AI2xhAIBp/9OsgBvuwAq
WbcALCoDMrYAPRKOtgA9tgB3tgB7BLgAfbEAAAABAJUAAABkAAz+ACEHABIHAB4HAJYV/wAaAAgH
AJgHABIHAB4HAJYHACoHAJYBAQAA+AAq/wAIAAEHAJgAAP0ADAcATQH9AEoHAFQBK/4AFwcAHgcA
iQcAlhn/AAUAAwcAmAcATQEAAPoABQCaAAAABAABAJsAAA==
'
fetch_class() { # -> 0 when $WORK/fetch/Fetch.class is ready
    [ -f "$WORK/fetch/Fetch.class" ] && return 0
    mkdir -p "$WORK/fetch"
    echo "$FETCH_CLASS" > "$WORK/fetch/Fetch.b64"
    { base64 -d < "$WORK/fetch/Fetch.b64" || openssl base64 -d < "$WORK/fetch/Fetch.b64" ||
      perl -MMIME::Base64 -e 'print decode_base64(join "", <STDIN>)' < "$WORK/fetch/Fetch.b64"
    } > "$WORK/fetch/Fetch.class" 2>/dev/null
}
java_fetch() { # url file -> 0 if downloaded
    fetch_class || return 1
    "$JAVA" -cp "$WORK/fetch" Fetch "$1" "$2" 2>/dev/null
}
sha256() { # file -> prints its SHA-256 (sha256sum, shasum, Solaris digest, openssl, or Java)
    if have sha256sum; then sha256sum < "$1" | sed 's/ .*//'
    elif have shasum; then shasum -a 256 < "$1" | sed 's/ .*//'
    elif have digest; then digest -a sha256 < "$1"
    elif have openssl; then openssl dgst -sha256 < "$1" | sed 's/.* //'
    else fetch_class && "$JAVA" -cp "$WORK/fetch" Fetch --sha256 "$1"
    fi
}
verify() { # sums-file -> checks $WORK/agent8.jar (as $ASSET) and $WORK/jvmdg.jar against it
    for f in "$ASSET:agent8.jar" jvmdg.jar:jvmdg.jar; do
        name=${f%%:*} file=$WORK/${f#*:}
        want=$(sed -n "s/^\([0-9a-f]\{64\}\)  *\*\{0,1\}$name\$/\1/p" "$1")
        [ -n "$want" ] || die "$name is not listed in sha256sums.txt"
        got=$(sha256 "$file") || die "cannot compute the SHA-256 of $name"
        [ "$got" = "$want" ] || die "$name does not match its SHA-256 in sha256sums.txt (download damaged or altered)"
    done
    say "checked $ASSET and jvmdg.jar against sha256sums.txt"
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
find_tag() { # remoting version -> prints the release tag built for it, from the latest release's index.txt
    fetch "$REPO/releases/latest/download/index.txt" "$WORK/index.txt" || return 1
    v=$(echo "$1" | sed 's/[.]/\\./g')
    sed -n "s/^$v //p" "$WORK/index.txt" | sed q
}
get_sums() { # tag -> downloads sha256sums.txt into $WORK
    if [ "$1" = latest ]; then base=$REPO/releases/latest/download; else base=$REPO/releases/download/$1; fi
    fetch "$base/sha256sums.txt" "$WORK/sha256sums.txt"
}

# The agent8 jar (kept as $WORK/agent8.jar) and jvmdg.jar
if [ -n "$FROM" ]; then
    [ -f "$FROM/$ASSET" ] || [ -n "$TARGET" ] || ASSET=agent8-java8.jar
    if cp "$FROM/$ASSET" "$WORK/agent8.jar" && cp "$FROM/jvmdg.jar" "$WORK/"; then :; else
        die "no $ASSET and jvmdg.jar in $FROM"
    fi
    if [ -f "$FROM/sha256sums.txt" ]; then verify "$FROM/sha256sums.txt"
    else say "no sha256sums.txt in $FROM; copy it from the release to check the jars"
    fi
else
    if [ -z "$VERSION" ] && [ -n "$URL" ]; then
        # The controller's agent.jar names its remoting version; any agent8 jar can read it.
        release latest || die "cannot download from $REPO; try --cacert FILE, or download the release files elsewhere and use --from DIR"
        fetch "${URL%/}/jnlpJars/agent.jar" "$WORK/controller-agent.jar" ||
            die "cannot download ${URL%/}/jnlpJars/agent.jar"
        # (Releases before the package rename have io.jenkins.agent8.Main.)
        VERSION=$("$JAVA" -cp "$WORK/agent8.jar" io.keelinfra.agent8.Main --remoting-version "$WORK/controller-agent.jar" 2>/dev/null ||
            "$JAVA" -cp "$WORK/agent8.jar" io.jenkins.agent8.Main --remoting-version "$WORK/controller-agent.jar")
        say "controller uses remoting $VERSION"
    fi
    TAG=latest
    if [ -n "$VERSION" ]; then
        # Releases are tagged jenkins-<version>; those from before 2026-10-14, remoting-<version>.
        TAG=$(find_tag "$VERSION") || TAG=
        if [ -n "$TAG" ] && release "$TAG"; then say "using release $TAG"
        elif release "remoting-$VERSION"; then TAG=remoting-$VERSION; else
            TAG=latest
            say "no agent8 build for remoting $VERSION yet; using the latest build"
            release latest || die "cannot download from $REPO; try --cacert FILE, or download the release files elsewhere and use --from DIR"
        fi
    else
        release latest || die "cannot download from $REPO; try --cacert FILE, or download the release files elsewhere and use --from DIR"
    fi
    get_sums "$TAG" || die "cannot download sha256sums.txt for $TAG from $REPO"
    verify "$WORK/sha256sums.txt"
fi

# Check that this Java runs it before touching anything.
# (No tail -n or head -n: Solaris's do not support them.)
if "$JAVA" -jar "$WORK/agent8.jar" -version > "$WORK/version.out" 2>&1; then :; else
    cat "$WORK/version.out" >&2
    die "$ASSET does not start with $JAVA (output above)"
fi
GOT=$(sed -n '$p' "$WORK/version.out")
say "$ASSET runs remoting $GOT on this host"

# With --replace the jar goes next to the old one; /opt/agent8 (often not writable by the
# agent's user) is only written when asked for.
if [ -z "$REPLACE" ] || [ -n "$DIR_SET" ]; then
    mkdir -p "$DIR"
    cp "$WORK/agent8.jar" "$WORK/jvmdg.jar" "$DIR/"
    say "installed $DIR/agent8.jar and $DIR/jvmdg.jar"
fi

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
say "Rolling this out to many hosts or to AIX/Solaris/HP-UX? Help and support: https://keelinfra.io/jenkins/pricing/"
