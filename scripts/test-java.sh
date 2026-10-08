#!/bin/bash
# JUnit for the Java (no Android classes). Needs scripts/fetch-test-deps.sh once.
# Prints failures only. Usage: scripts/test-java.sh
set -u
cd "$(dirname "$0")/.."
J=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}; L=tools/lib
# The plugin class is compiled too (with the SDK jar) so its screensaver gate can be tested with a fake host.
CP="$L/junit-platform-console-standalone-1.11.4.jar:$L/json-20240303.jar:dist/kiosk-plugin-sdk-1.jar"
out=$(mktemp -d)
"$J/bin/javac" -nowarn -d "$out" -cp "$CP" \
  $(ls src/me/jxl/kiosk/plugins/flights/*.java) src/me/jxl/kiosk/plugins/flights/source/*.java src/me/jxl/kiosk/plugins/flights/enrich/*.java \
  $(find test -name '*.java') || exit 1
"$J/bin/java" -jar "$L/junit-platform-console-standalone-1.11.4.jar" execute --class-path "$out:$L/json-20240303.jar:dist/kiosk-plugin-sdk-1.jar" \
  --scan-class-path "$out" --details=none --disable-banner 2>&1 | grep -E "✘|Failures|MethodSource|=>|tests failed" | head -30
status=${PIPESTATUS[0]}
rm -rf "$out"; exit $status
