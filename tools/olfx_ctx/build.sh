#!/bin/sh
# Builds olfx/src/main/assets/ovr/olfx_ctx.dex from src/ (javac + d8, no Gradle needed).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$(ls -d "$sdk"/platforms/android-*/android.jar | sort -V | tail -1)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 8 -Xlint:-options -cp "$jar" -d "$out/classes" "$here"/src/org/olfx/ctx/*.java
"$bt/d8" --min-api 26 --lib "$jar" --output "$out" "$out"/classes/org/olfx/ctx/*.class
dest="$here/../../olfx/src/main/assets/ovr/olfx_ctx.dex"
cp "$out/classes.dex" "$dest"
echo "wrote $dest ($(wc -c < "$dest") bytes)"
