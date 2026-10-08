#!/bin/bash
# Local CI. Prints failures only. Partial for now: renderer tests, Java unit tests,
# golden-fixture drift, Java formatting (google-java-format) and the package size gate.
# A full plugin build joins later.
set -u
cd "$(dirname "$0")/.."
fail=0
note() { echo "FAIL: $*"; fail=1; }
# The Java checks compile against the plugin SDK interfaces; a fresh clone has no dist/ yet.
if [ ! -f dist/kiosk-plugin-sdk-1.jar ]; then
  J=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}; sdkc=$(mktemp -d); mkdir -p dist
  "$J/bin/javac" --release 8 -d "$sdkc" $(find sdk/src -name '*.java') && "$J/bin/jar" cf dist/kiosk-plugin-sdk-1.jar -C "$sdkc" . || note "could not build the SDK jar"
  rm -rf "$sdkc"
fi
out=$(node --test assets/flights/lib.test.js 2>&1) || { note "renderer tests"; echo "$out" | grep -E "✖|Error" | head -20; }
[ -f tools/lib/json-20240303.jar ] || scripts/fetch-test-deps.sh >/dev/null 2>&1
if [ -f tools/lib/json-20240303.jar ]; then scripts/test-java.sh >/tmp/_jt.txt 2>&1 || { note "java tests"; cat /tmp/_jt.txt; }; else note "java test deps missing (scripts/fetch-test-deps.sh)"; fi
# The shipped sources, FlightsPlugin included (the unit-test run excludes it), at the device's language level.
o=$(mktemp -d)
# Prefer the device's own android.jar: its org.json throws checked exceptions, which the test jar's does not.
aj=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
jarpath="${aj:-tools/lib/json-20240303.jar}:dist/kiosk-plugin-sdk-1.jar"
"${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}/bin/javac" --release 8 -nowarn -d "$o" -cp "$jarpath" $(find src -name '*.java') >/tmp/_jc.txt 2>&1 || { note "shipped sources do not compile (--release 8)"; head -10 /tmp/_jc.txt; }
rm -rf "$o"
[ -f tools/lib/google-java-format-1.25.2-all-deps.jar ] || scripts/fetch-test-deps.sh >/dev/null 2>&1
scripts/format.sh --check >/tmp/_fmt.txt 2>&1 || { note "java formatting (run scripts/format.sh)"; head -10 /tmp/_fmt.txt; }
[ -f build/java-payload.json ] && { scripts/validate-payload.js build/java-payload.json $(ls fixtures/payload/*.json | grep -v index.json) 2>&1 || note "Java payload fails the renderer validator"; }
tmp=$(mktemp -d); cp -R fixtures/payload "$tmp/before"
scripts/gen-fixtures.py >/dev/null && diff -rq "$tmp/before" fixtures/payload >/dev/null || note "fixtures differ from scripts/gen-fixtures.py (regenerate and commit)"
rm -rf "$tmp"
tmp=$(mktemp)
# The plugin serves the generated FlightsHtml.java, not assets/ directly: stale means the device shows old UI.
f=src/me/jxl/kiosk/plugins/flights/FlightsHtml.java; cp "$f" "$tmp.fh" 2>/dev/null
scripts/bundle-page.py >/dev/null && cmp -s "$f" "$tmp.fh" || note "FlightsHtml.java is stale (run scripts/bundle-page.py and commit)"
rm -f "$tmp.fh"
for z in dist/*.zip; do
  [ -e "$z" ] || continue
  [ "$(stat -f%z "$z" 2>/dev/null || stat -c%s "$z")" -le 3500000 ] || note "$z exceeds 3.5 MB"
done
exit $fail
