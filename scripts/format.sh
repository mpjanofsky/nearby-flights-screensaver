#!/bin/bash
# Formats the Java sources with google-java-format (the project's Spotless rule, run directly:
# the build is tools/build.py, not Gradle). `--check` lists unformatted files and fails instead.
# Generated FlightsHtml.java, AirportData.java and the vendored SDK are skipped. Usage: scripts/format.sh [--check]
set -u
cd "$(dirname "$0")/.."
J=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
JAR=tools/lib/google-java-format-1.25.2-all-deps.jar
[ -f "$JAR" ] || scripts/fetch-test-deps.sh || exit 1
files=$(find src test -name '*.java' ! -name FlightsHtml.java ! -name AirportData.java)
flags=(--replace); [ "${1:-}" = "--check" ] && flags=(--dry-run --set-exit-if-changed)
# shellcheck disable=SC2086
"$J/bin/java" \
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -jar "$JAR" "${flags[@]}" $files
