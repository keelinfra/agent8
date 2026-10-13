#!/usr/bin/env bash
# Objects serialized by a Java 21 controller must deserialize on the Java 8 agent.
# Needs: dist/agent8-java8.jar (or $AGENT_JAR), java (17+) on PATH, docker.
set -euo pipefail
cd "$(dirname "$0")/.."
T=$(mktemp -d)
java -version 2>&1 | head -1
javac -d "$T" test/SerialWrite.java && java -cp "$T" SerialWrite "$T/objects.bin"
javac -nowarn --release 8 -d "$T" test/SerialRead.java
docker run --rm -v "$T:/t" -v "$PWD/dist:/dist:ro" "${JDK8_IMAGE:-eclipse-temurin:8-jdk}" \
    java -javaagent:/dist/${AGENT_JAR:-agent8-java8.jar} -cp /t SerialRead /t/objects.bin
