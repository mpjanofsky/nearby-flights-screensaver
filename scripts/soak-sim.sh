#!/bin/bash
# Accelerated soak: days of synthetic traffic through the engine and enricher on a fake clock.
# Prints heap after GC, cache and queue sizes per simulated interval. Usage: scripts/soak-sim.sh [days=30] [heapMB=48] [outageMin=10] [periodMin=120]
set -u
cd "$(dirname "$0")/.."
J=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}; L=tools/lib
CP="$L/json-20240303.jar:dist/kiosk-plugin-sdk-1.jar"
out=$(mktemp -d)
"$J/bin/javac" -nowarn -d "$out" -cp "$CP" \
  src/me/jxl/kiosk/plugins/flights/*.java src/me/jxl/kiosk/plugins/flights/source/*.java \
  src/me/jxl/kiosk/plugins/flights/enrich/*.java test/me/jxl/kiosk/plugins/flights/SoakMain.java || exit 1
"$J/bin/java" -Xmx${2:-48}m -cp "$out:$CP" me.jxl.kiosk.plugins.flights.SoakMain "${1:-30}" "${3:-10}" "${4:-120}"
status=$?; rm -rf "$out"; exit $status
