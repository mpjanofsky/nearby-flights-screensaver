#!/bin/bash
# Builds a release package from a clean tree and tags it. Usage: scripts/release.sh 1.0.0
# The version is the tag (vX.Y.Z); tools/build.py stamps it into the package manifest, so the
# checked-in manifest version is only the dev default. Nothing is pushed anywhere.
set -eu
cd "$(dirname "$0")/.."
v=${1:-}
[[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: scripts/release.sh MAJOR.MINOR.PATCH" >&2; exit 2; }
tag="v$v"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && { echo "tag $tag already exists" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree is not clean; commit or stash first" >&2; exit 1; }
scripts/check.sh || { echo "check.sh failed; not releasing" >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
python3 tools/build.py --version "$v"
zip="dist/nearby-flights-$v.zip"
[ -f "$zip" ] || { echo "build did not produce $zip" >&2; exit 1; }
size=$(stat -f%z "$zip" 2>/dev/null || stat -c%s "$zip")
[ "$size" -le 3500000 ] || { echo "$zip is $size bytes, over the 3.5 MB gate" >&2; exit 1; }
git tag -a "$tag" -m "Nearby Flights $v"
echo "built $zip ($size bytes), tagged $tag"
echo "sha256: $(shasum -a 256 "$zip" | cut -d' ' -f1)"
