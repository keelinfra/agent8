#!/bin/sh
# Installs agent8 on a build host that only has old Java (8, 11 or 17), and optionally
# swaps it in for the agent.jar the host already uses. It picks the build made for the
# host's Java: agent8.jar (Java 8), agent8-java11.jar or agent8-java17.jar. POSIX sh: runs on RHEL/CentOS 6,
# AIX (ksh), Solaris and HP-UX.
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
set -e

URL= TARGET= VERSION= REPLACE= DIR=/opt/agent8 FROM= JAVA= REPO=https://github.com/keelinfra/agent8
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
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "agent8: unknown option $1 (see --help)" >&2; exit 2 ;;
    esac
done

die() { echo "agent8: $*" >&2; exit 1; }
say() { echo "agent8: $*"; }

# Java
if [ -z "$JAVA" ]; then
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA=$JAVA_HOME/bin/java
    else JAVA=$(command -v java 2>/dev/null) || die "no java found; pass --java /path/to/bin/java"
    fi
fi
[ -x "$JAVA" ] || die "$JAVA is not executable"
JV=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)
say "using $JAVA ($JV)"
case $JV in
    1.[0-7]*) die "Java $JV is too old; agent8 needs Java 8 or newer" ;;
    1.*) JMAJOR=8 ;;
    *) JMAJOR=$(echo "$JV" | sed 's/[^0-9].*//') ;;
esac
# The closest build at or below this Java; agent8.jar (Java 8) is the fallback.
if [ "$JMAJOR" -ge 17 ] 2>/dev/null; then ASSET=agent8-java17.jar
elif [ "$JMAJOR" -ge 11 ] 2>/dev/null; then ASSET=agent8-java11.jar
else ASSET=agent8.jar
fi
case $TARGET in
    '') ;;
    8) ASSET=agent8.jar ;;
    11|17) ASSET=agent8-java$TARGET.jar ;;
    *) die "--target must be 8, 11 or 17" ;;
esac
WANT=$ASSET

WORK=${TMPDIR:-/tmp}/agent8.$$
mkdir "$WORK" || die "cannot create $WORK"
trap 'rm -rf "$WORK"' 0

fetch() { # url file -> 0 if downloaded
    if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1" 2>/dev/null
    elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
    else die "neither curl nor wget found; download the release files and use --from DIR"
    fi
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
    if [ "$ASSET" != agent8.jar ] && fetch "$base/$ASSET" "$WORK/agent8.jar"; then return 0; fi
    ASSET=agent8.jar
    fetch "$base/agent8.jar" "$WORK/agent8.jar"
}

# agent8.jar and jvmdg.jar
if [ -n "$FROM" ]; then
    [ -f "$FROM/$ASSET" ] || [ -n "$TARGET" ] || ASSET=agent8.jar
    cp "$FROM/$ASSET" "$WORK/agent8.jar" && cp "$FROM/jvmdg.jar" "$WORK/" ||
        die "no $ASSET and jvmdg.jar in $FROM"
else
    if [ -z "$VERSION" ] && [ -n "$URL" ]; then
        # The controller's agent.jar names its remoting version; the latest agent8.jar can read it.
        release latest || die "cannot download from $REPO; use --from DIR"
        fetch "${URL%/}/jnlpJars/agent.jar" "$WORK/controller-agent.jar" ||
            die "cannot download ${URL%/}/jnlpJars/agent.jar"
        VERSION=$("$JAVA" -cp "$WORK/agent8.jar" io.jenkins.agent8.Main --remoting-version "$WORK/controller-agent.jar")
        say "controller uses remoting $VERSION"
    fi
    if [ -n "$VERSION" ]; then
        if ! release "remoting-$VERSION"; then
            say "no agent8 build for remoting $VERSION yet; using the latest build"
            release latest || die "cannot download from $REPO; use --from DIR"
        fi
    else
        release latest || die "cannot download from $REPO; use --from DIR"
    fi
fi

# Check that this Java runs it before touching anything.
GOT=$("$JAVA" -jar "$WORK/agent8.jar" -version 2>/dev/null | tail -n 1) ||
    die "agent8.jar does not start with $JAVA"
say "$ASSET runs remoting $GOT on this host"

mkdir -p "$DIR"
cp "$WORK/agent8.jar" "$WORK/jvmdg.jar" "$DIR/"
say "installed $DIR/agent8.jar and $DIR/jvmdg.jar"

if [ -n "$REPLACE" ]; then
    target_dir=$(dirname "$REPLACE")
    [ -d "$target_dir" ] || die "$target_dir does not exist"
    if [ -f "$REPLACE" ] && [ ! -f "$REPLACE.before-agent8" ]; then
        cp -p "$REPLACE" "$REPLACE.before-agent8"
        say "kept the old jar as $REPLACE.before-agent8"
    fi
    cp "$WORK/agent8.jar" "$REPLACE"
    cp "$WORK/jvmdg.jar" "$target_dir/jvmdg.jar"
    say "replaced $REPLACE; restart the agent with its usual command"
else
    say "start the agent as usual, with this jar:"
    say "  $JAVA -jar $DIR/agent8.jar -url <controller> -secret <secret> -name <node> -workDir <dir>"
fi
