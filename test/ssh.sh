#!/usr/bin/env bash
# Integration test: the SSH Build Agents launcher starts agent8. It copies the controller's
# stock remoting.jar to the host and runs "<Java Path> -jar remoting.jar" on every launch;
# Java Path is set to the wrapper install.sh writes, which runs agent8.jar instead.
# The host's first security provider is a signed jar, as SunPKCS11 or OracleUcrypto are
# on Solaris (JvmDowngrader used to break signed jars: "signer information does not match").
# The controller needs the ssh-slaves plugin (PLUGINS=ssh-slaves in Dockerfile.controller).
#
#   JENKINS_URL   controller URL (default http://localhost:8080)
#   JDK8_IMAGE    Debian/Ubuntu-based Java 8 JDK image for the agent (default eclipse-temurin:8-jdk)
set -euo pipefail
cd "$(dirname "$0")/.."
URL=${JENKINS_URL:-http://localhost:8080}
JDK8_IMAGE=${JDK8_IMAGE:-eclipse-temurin:8-jdk}
T=$(mktemp -d)
C=$T/cookies
failures=0

crumb() {
    curl -fsS -c "$C" -b "$C" "$URL/crumbIssuer/api/json" |
        sed 's/.*"crumb":"\([^"]*\)".*"crumbRequestField":"\([^"]*\)".*/\2:\1/'
}
post() { curl -fsS -c "$C" -b "$C" -X POST -H "$(crumb)" "$@"; }
online() { curl -fsS "$URL/computer/agent8-ssh/api/json?tree=offline" | grep -q '"offline":false'; }
check() { # name condition...
    local name=$1; shift
    if "$@"; then echo "ok   $name"; else echo "FAIL $name"; failures=$((failures + 1)); fi
}
on_agent() { docker exec agent8-ssh sh -c "$1"; }

docker rm -f agent8-ssh >/dev/null 2>&1 || true
docker run -d --name agent8-ssh --network host -v "$PWD/dist:/dist:ro" -v "$PWD/install.sh:/install.sh:ro" \
    -v "$PWD/test/signed-provider:/signed-provider:ro" "$JDK8_IMAGE" sleep infinity >/dev/null
[[ -n ${KEEP:-} ]] || trap 'docker rm -f agent8-ssh >/dev/null 2>&1 || true' EXIT
JH=$(on_agent 'echo "$JAVA_HOME"') # /opt/java/openjdk in the eclipse-temurin images

# sshd on port 2222, user jenkins
on_agent 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -qq -y openssh-server >/dev/null'
on_agent 'useradd -m -s /bin/sh jenkins && echo jenkins:agent8-pw | chpasswd && mkdir -p /run/sshd &&
    /usr/sbin/sshd -p 2222 -o PasswordAuthentication=yes'

# The signed provider jar
on_agent "set -e; mkdir -p /tmp/sp /opt/signed
    $JH/bin/javac -d /tmp/sp /signed-provider/keeltest/*.java
    $JH/bin/jar cf /opt/signed/keeltest.jar -C /tmp/sp keeltest
    $JH/bin/keytool -genkeypair -keystore /tmp/ks.jks -storepass secret -keypass secret -alias t \
        -dname CN=agent8-test -keyalg RSA -keysize 2048 2>/dev/null
    $JH/bin/jarsigner -keystore /tmp/ks.jks -storepass secret /opt/signed/keeltest.jar t >/dev/null
    $JH/bin/jarsigner -verify /opt/signed/keeltest.jar | tail -1"
EXT=$JH/jre/lib/ext
JVM_OPTIONS="-Djava.ext.dirs=$EXT:/opt/signed -Djava.security.properties=/signed-provider/java.security"

on_agent "sh /install.sh --from /dist --java $JH/bin/java --dir /opt/agent8"
check "install: Java Path wrapper written" on_agent 'test -x /opt/agent8/java'
check "wrapper passes other commands through" on_agent '/opt/agent8/java -version 2>&1 | grep -q "version \"1.8"'

sed "s|JVM_OPTIONS|$JVM_OPTIONS|" test/setup-ssh.groovy > "$T/setup.groovy"
post --data-urlencode "script@$T/setup.groovy" "$URL/scriptText"
for _ in $(seq 1 60); do online && break; sleep 3; done
check "SSH agent online" online
if ! online; then curl -fsS "$URL/computer/agent8-ssh/logText/progressiveText?start=0" | tail -60; fi
check "launcher copied the stock remoting.jar" on_agent \
    'test -f /home/jenkins/agent/remoting.jar && ! cmp -s /home/jenkins/agent/remoting.jar /opt/agent8/agent8.jar'

printf 'println System.getProperty("java.version")\n' > "$T/v.groovy"
out=$(post --data-urlencode "script@$T/v.groovy" "$URL/computer/agent8-ssh/scriptText" || true)
check "agent runs on Java 8 ($out)" test "${out:0:3}" = 1.8
printf 'println java.security.MessageDigest.getInstance("KEELTEST").provider.name\n' > "$T/p.groovy"
out=$(post --data-urlencode "script@$T/p.groovy" "$URL/computer/agent8-ssh/scriptText" 2>&1 || true)
check "signed security provider loads ($out)" test "$out" = KeelTest

n=$(curl -fsS "$URL/job/agent8-ssh-build/api/json?tree=nextBuildNumber" | sed 's/.*:\([0-9]*\)}/\1/')
post "$URL/job/agent8-ssh-build/build" -o /dev/null
r=TIMEOUT
for _ in $(seq 1 60); do
    s=$(curl -fsS "$URL/job/agent8-ssh-build/$n/api/json?tree=result,building" 2>/dev/null || true)
    if echo "$s" | grep -q '"building":false'; then r=$(echo "$s" | sed -n 's/.*"result":"\([A-Z]*\)".*/\1/p'); break; fi
    sleep 2
done
check "freestyle build on the SSH agent succeeds ($r)" test "$r" = SUCCESS
curl -fsS "$URL/job/agent8-ssh-build/$n/consoleText" > "$T/build.log" || true
check "build log has 'read=hello'" grep -qF 'read=hello' "$T/build.log"
[[ $r = SUCCESS ]] || cat "$T/build.log"

echo "$failures failure(s)"
[[ $failures = 0 ]]
