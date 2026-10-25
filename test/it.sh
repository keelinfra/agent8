#!/usr/bin/env bash
# Integration test: an old-Java agent built from dist/ connects to a running controller
# and runs Pipeline builds. The controller must allow anonymous script console access
# and have the workflow-aggregator style plugins (see test/Dockerfile.controller).
#
#   JENKINS_URL   controller URL (default http://localhost:8080)
#   AGENT_IMAGE   OS image for the agent (default centos:6)
#   JDK8_IMAGE    image to copy the old Java runtime from (default eclipse-temurin:8-jdk)
#   EXPECT_JAVA   java.version prefix the agent must report (default 1.8)
#   EXPECT_JAR    dist/ jar install.sh must pick for that Java (default agent8-java8.jar)
#   NO_VERSIONCOLUMN=1  the controller has no versioncolumn plugin: skip its monitor check
#   BASIC=1       bare controller without plugins (PLUGINS="" in Dockerfile.controller): a
#                 freestyle shell build instead of the Pipeline, Git and credentials checks
set -euo pipefail
cd "$(dirname "$0")/.."
URL=${JENKINS_URL:-http://localhost:8080}
AGENT_IMAGE=${AGENT_IMAGE:-centos:6}
JDK8_IMAGE=${JDK8_IMAGE:-eclipse-temurin:8-jdk}
EXPECT_JAVA=${EXPECT_JAVA:-1.8}
EXPECT_JAR=${EXPECT_JAR:-agent8-java8.jar}
T=$(mktemp -d)
C=$T/cookies
failures=0

crumb() {
    curl -fsS -c "$C" -b "$C" "$URL/crumbIssuer/api/json" |
        sed 's/.*"crumb":"\([^"]*\)".*"crumbRequestField":"\([^"]*\)".*/\2:\1/'
}
post() { curl -fsS -c "$C" -b "$C" -X POST -H "$(crumb)" "$@"; }
groovy() { post --data-urlencode "script@$1" "$URL/scriptText"; }
online() { curl -fsS "$URL/computer/agent8/api/json?tree=offline" | grep -q '"offline":false'; }
wait_online() {
    for _ in $(seq 1 60); do online && return 0; sleep 3; done
    echo "agent did not come online"; docker exec agent8-it cat /tmp/agent.log | tail -40; return 1
}
start_agent() {
    secret=$(curl -fsS "$URL/computer/agent8/jenkins-agent.jnlp" |
        sed -n 's/.*<argument>\([0-9a-f]\{64\}\)<\/argument>.*/\1/p')
    # The usual inbound agent command: install.sh swapped agent8 in for agent.jar.
    docker exec -d agent8-it sh -c "exec /opt/jdk8/bin/java -jar /opt/jenkins/agent.jar \
        -url $URL/ -secret $secret -name agent8 -workDir /srv/agent8 ${AGENT_ARGS:-} >> /tmp/agent.log 2>&1"
    wait_online
}
build() { # job -> waits, prints result
    local n
    n=$(curl -fsS "$URL/job/$1/api/json?tree=nextBuildNumber" | sed 's/.*:\([0-9]*\)}/\1/')
    post "$URL/job/$1/build" -o /dev/null
    echo "$n"
}
result() { # job number
    for _ in $(seq 1 120); do
        r=$(curl -fsS "$URL/job/$1/$2/api/json?tree=result,building" 2>/dev/null || true)
        echo "$r" | grep -q '"building":false' && { echo "$r" | sed -n 's/.*"result":"\([A-Z]*\)".*/\1/p'; return; }
        sleep 2
    done
    echo TIMEOUT
}
log() { curl -fsS "$URL/job/$1/$2/consoleText"; }
check() { # name condition...
    local name=$1; shift
    if "$@"; then echo "ok   $name"; else echo "FAIL $name"; failures=$((failures + 1)); fi
}
remote_count() { docker exec agent8-it sh -c "ps -eo args | grep -c '^$1' || true"; }

# Java 8 runtime from the JDK image, OS from the agent image.
docker create --name agent8-jdk "$JDK8_IMAGE" >/dev/null
docker cp agent8-jdk:/opt/java/openjdk "$T/jdk8"
docker rm agent8-jdk >/dev/null
docker rm -f agent8-it >/dev/null 2>&1 || true
# A Git repository for the checkout test, at file:///repo.git on the agent.
git init -q -b main "$T/src" && echo agent8-repo > "$T/src/README" &&
    printf '%s\n' '<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId>' \
        '<version>1</version><packaging>pom</packaging></project>' > "$T/src/pom.xml" &&
    git -C "$T/src" -c user.name=t -c user.email=t@t add README pom.xml &&
    git -C "$T/src" -c user.name=t -c user.email=t@t commit -qm init &&
    git clone -q --bare "$T/src" "$T/repo.git"
# Maven for the Maven project type test (Maven 3.9 runs on Java 8).
MAVEN_VERSION=3.9.9
curl -fsS "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.tar.gz" |
    tar -xz -C "$T" && mv "$T/apache-maven-$MAVEN_VERSION" "$T/maven"
docker run -d --name agent8-it --network host -v "$PWD/dist:/dist:ro" -v "$T/jdk8:/opt/jdk8:ro" \
    -v "$T/repo.git:/repo.git:ro" -v "$T/maven:/opt/maven:ro" \
    -v "$PWD/test/kill:/usr/local/bin/kill:ro" -v "$PWD/install.sh:/install.sh:ro" \
    "$AGENT_IMAGE" sleep infinity >/dev/null
[[ -n ${KEEP:-} ]] || trap 'docker rm -f agent8-it >/dev/null 2>&1 || true' EXIT
docker exec agent8-it sh -c 'cat /etc/redhat-release 2>/dev/null; /opt/jdk8/bin/java -version 2>&1 | head -1'

# A host that already runs the stock agent.jar, which its Java 8 cannot start.
curl -fsS -o "$T/agent.jar" "$URL/jnlpJars/agent.jar"
docker exec agent8-it mkdir -p /opt/jenkins
docker cp "$T/agent.jar" agent8-it:/opt/jenkins/agent.jar
docker exec agent8-it sh /install.sh --from /dist --java /opt/jdk8/bin/java --replace /opt/jenkins/agent.jar
check "install: picked $EXPECT_JAR for this Java" docker exec agent8-it sh -c \
    "[ \"\$(md5sum < /dist/$EXPECT_JAR)\" = \"\$(md5sum < /opt/jenkins/agent.jar)\" ]"
check "install: old jar kept" docker exec agent8-it test -f /opt/jenkins/agent.jar.before-agent8
check "install: jvmdg.jar next to the jar" docker exec agent8-it test -f /opt/jenkins/jvmdg.jar
check "install: --remoting-version reads the controller jar" test "$(docker exec agent8-it /opt/jdk8/bin/java \
    -cp /opt/jenkins/agent.jar io.keelinfra.agent8.Main --remoting-version /opt/jenkins/agent.jar.before-agent8)" = \
    "$(unzip -p "$T/agent.jar" META-INF/MANIFEST.MF | sed -n 's/^Version: *//p' | tr -d '\r')"

if [[ -n ${BASIC:-} ]]; then
    groovy test/setup-basic.groovy
    start_agent
    check "agent online" online
    n=$(build agent8-basic); r=$(result agent8-basic "$n"); log agent8-basic "$n" > "$T/build.log"
    check "freestyle build succeeds ($r)" test "$r" = SUCCESS
    check "build log has 'read=hello'" grep -qF 'read=hello' "$T/build.log"
    check "artifact archived" curl -fsS -o /dev/null "$URL/job/agent8-basic/$n/artifact/a.txt"
    [[ $r = SUCCESS ]] || cat "$T/build.log"
else
    groovy test/setup.groovy
    start_agent
    check "agent online" online

    n=$(build agent8-build); r=$(result agent8-build "$n"); log agent8-build "$n" > "$T/build.log"
    check "pipeline build succeeds ($r)" test "$r" = SUCCESS
    for want in 'read=hello' 'rc=3' 'out=remote-out' "greeting=it's ok" 'exists=true' 'Recording test results'; do
        check "build log has '$want'" grep -qF "$want" "$T/build.log"
    done
    [[ $r = SUCCESS ]] || cat "$T/build.log"

    n=$(build agent8-plugins); r=$(result agent8-plugins "$n"); log agent8-plugins "$n" > "$T/plugins.log"
    check "git checkout (JGit) and withCredentials build succeeds ($r)" test "$r" = SUCCESS
    for want in 'checked-out=agent8-repo' 'user=agent8-user' 'password-ok'; do
        check "plugins build log has '$want'" grep -qF "$want" "$T/plugins.log"
    done
    [[ $r = SUCCESS ]] || cat "$T/plugins.log"

    # Command-line git (the git plugin's default tool): only on images with a package manager
    # that still works; CentOS 6's repositories are gone, so it has no git binary.
    if docker exec agent8-it sh -c 'command -v apt-get >/dev/null && apt-get update -qq >/dev/null &&
            DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git >/dev/null'; then
        n=$(build agent8-cli-git); r=$(result agent8-cli-git "$n"); log agent8-cli-git "$n" > "$T/cli-git.log"
        check "git checkout (command-line git) build succeeds ($r)" test "$r" = SUCCESS
        check "cli git build log has 'cli-checked-out=agent8-repo'" grep -qF 'cli-checked-out=agent8-repo' "$T/cli-git.log"
        [[ $r = SUCCESS ]] || cat "$T/cli-git.log"
    else
        echo "skip command-line git: no git package on $AGENT_IMAGE"
    fi

    if curl -fsSg "$URL/pluginManager/api/json?tree=plugins[shortName]" | grep -q '"maven-plugin"'; then
        groovy test/setup-maven.groovy
        # Control: the same build on the controller's built-in node, with stock remoting.
        if docker cp "$T/maven" "${CONTROLLER_CONTAINER:-controller}:/opt/maven" &&
            docker cp "$T/repo.git" "${CONTROLLER_CONTAINER:-controller}:/repo.git"; then
            n=$(build agent8-maven-builtin); r=$(result agent8-maven-builtin "$n")
            log agent8-maven-builtin "$n" > "$T/maven-builtin.log"
            echo "control: Maven project type on the built-in node: $r"
            [[ $r = SUCCESS ]] || grep -E 'ERROR|Exception|Caused by|at hudson.maven' "$T/maven-builtin.log" | head -20
        fi
        n=$(build agent8-maven); r=$(result agent8-maven "$n"); log agent8-maven "$n" > "$T/maven.log"
        check "Maven project type build succeeds ($r)" test "$r" = SUCCESS
        check "maven build log has 'BUILD SUCCESS'" grep -qF 'BUILD SUCCESS' "$T/maven.log"
        [[ $r = SUCCESS ]] || grep -vE '^\s+at |^\s+\.\.\. [0-9]+ more' "$T/maven.log" | tail -40
    else
        echo "skip Maven project type: no maven-plugin on the controller"
    fi

    n=$(build agent8-abort)
    for _ in $(seq 1 30); do log agent8-abort "$n" 2>/dev/null | grep -q '^started' && break; sleep 2; done
    sleep 2
    check "abort: processes running" test "$(remote_count 'sleep 60[01]')" = 2
    post "$URL/job/agent8-abort/$n/stop" -o /dev/null
    check "abort: build aborted" test "$(result agent8-abort "$n")" = ABORTED
    sleep 3
    check "abort: processes killed" test "$(remote_count 'sleep 60[01]')" = 0

    n=$(build agent8-durable)
    for _ in $(seq 1 30); do log agent8-durable "$n" 2>/dev/null | grep -q '^+ sleep 30' && break; sleep 2; done
    docker exec agent8-it pkill -f /opt/jenkins/agent.jar
    sleep 5
    check "restart: step keeps running while agent is down" test "$(remote_count 'sleep 30')" = 1
    start_agent
    check "restart: build succeeds after agent restart" test "$(result agent8-durable "$n")" = SUCCESS
fi

printf 'println System.getProperty("java.version")\n' > "$T/console.groovy"
out=$(post --data-urlencode "script@$T/console.groovy" "$URL/computer/agent8/scriptText")
check "script console runs on Java $EXPECT_JAVA ($out)" test "${out:0:${#EXPECT_JAVA}}" = "$EXPECT_JAVA"

if [[ -z ${BASIC:-} && -z ${NO_VERSIONCOLUMN:-} ]]; then
    out=$(groovy test/jvm-monitor.groovy)
    echo "$out"
    check "JVM version monitor keeps the agent online ($out)" sh -c "echo '$out' | grep -q 'offline=false'"
fi

transform_errors=$(docker exec agent8-it sh -c 'grep -c "Failed to transform\|\[agent8\]" /tmp/agent.log || true')
check "no class transformation errors ($transform_errors)" test "$transform_errors" = 0

docker exec agent8-it pkill -f /opt/jenkins/agent.jar || true
docker exec agent8-it sh /install.sh --from /dist --java /opt/jdk8/bin/java --replace /opt/jenkins/agent.jar
check "reinstall: original jar not overwritten" docker exec agent8-it sh -c \
    "[ \"\$(md5sum < /opt/jenkins/agent.jar.before-agent8)\" != \"\$(md5sum < /opt/jenkins/agent.jar)\" ]"
docker exec agent8-it sh /install.sh --restore /opt/jenkins/agent.jar
check "restore: stock agent.jar back" test "$(docker exec agent8-it sh -c 'md5sum < /opt/jenkins/agent.jar')" = "$(md5sum < "$T/agent.jar")"
check "restore: jvmdg.jar removed" docker exec agent8-it sh -c '! test -e /opt/jenkins/jvmdg.jar'

echo "$failures failure(s)"
[[ $failures = 0 ]]
