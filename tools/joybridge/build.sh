#!/bin/sh
# Rebuilds third_party/ovrplugin-openxr-shim/shim/src/joy_dex.h from tools/joybridge/org/olfx/JoyBridge.java (javac + d8).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
jar=$(ls -d "$sdk"/platforms/android-*/android.jar | sort -V | tail -1)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 8 -Xlint:-options -cp "$jar" -d "$out/classes" "$here"/org/olfx/JoyBridge.java
"$bt/d8" --min-api 26 --lib "$jar" --output "$out" "$out"/classes/org/olfx/*.class
dest=${1:-"$here/../../third_party/ovrplugin-openxr-shim/shim/src/joy_dex.h"}
python3 - "$out/classes.dex" "$dest" <<'PY'
import sys
d = open(sys.argv[1], 'rb').read()
with open(sys.argv[2], 'w') as f:
    f.write('unsigned char olfx_joy_dex[] = {\n')
    for i in range(0, len(d), 12):
        f.write('  ' + ', '.join('0x%02x' % b for b in d[i:i + 12]) + (',\n' if i + 12 < len(d) else '\n'))
    f.write('};\nunsigned int olfx_joy_dex_len = %d;\n' % len(d))
PY
echo "wrote $dest ($(wc -c < "$out/classes.dex") dex bytes)"
