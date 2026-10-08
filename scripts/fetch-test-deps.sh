#!/bin/bash
# Download the JVM test dependencies into tools/lib/ (gitignored). Verified against the
# SHA-1 that Maven Central publishes beside each jar. org.json is test-only here: on the
# device Android provides it. google-java-format is the formatter (the Spotless rule, run directly). Usage: scripts/fetch-test-deps.sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p tools/lib
M=https://repo1.maven.org/maven2
for a in "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar" \
         "org/json/json/20240303/json-20240303.jar" \
         "com/google/googlejavaformat/google-java-format/1.25.2/google-java-format-1.25.2-all-deps.jar"; do
  f=tools/lib/$(basename "$a")
  [ -f "$f" ] && continue
  curl -sSf -m 120 -o "$f" "$M/$a"
  want=$(curl -sSf -m 30 "$M/$a.sha1" | cut -c1-40)
  got=$(shasum -a 1 "$f" | cut -c1-40)
  [ "$want" = "$got" ] || { rm -f "$f"; echo "checksum mismatch for $a" >&2; exit 1; }
done
