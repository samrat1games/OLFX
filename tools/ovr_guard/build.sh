#!/bin/sh
# Builds olfx/src/main/assets/ovr/libovrplatformloader_guard_arm64.so with the NDK (no Gradle).
# Needs a copy of the game's real libovrplatformloader.so to enumerate the exports:
#   tools/ovr_guard/build.sh path/to/libovrplatformloader.so
set -eu
here=$(cd "$(dirname "$0")" && pwd)
real=${1:?usage: build.sh REAL_libovrplatformloader.so}
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
# newest NDK that actually contains the arm64 clang (a half-installed NDK dir is skipped)
clang=$(ls "$sdk"/ndk/*/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang | sort -V | tail -1)
bin=$(dirname "$clang")
gen="$here/gen"
mkdir -p "$gen"
python3 -I "$here/gen_fwd.py" "$real" "$gen" --skip JNI_OnLoad,ovr_PlatformInitializeAndroid,ovr_PlatformInitializeAndroidWithOptions,ovr_PlatformInitializeAndroidAsynchronous,ovr_Entitlement_GetIsViewerEntitled,ovr_UnityInitWrapper,ovr_UnityInitWrapperAsynchronous,ovr_PopMessage,ovr_FreeMessage,ovr_Message_GetRequestID,ovr_Message_GetType,ovr_Message_IsError,ovr_Message_GetPlatformInitialize,ovr_PlatformInitialize_GetResult
python3 -I - "$here/../../olfx/src/main/assets/ovr/olfx_ctx.dex" "$gen/olfx_ctx_dex.h" <<'PY'
import sys
d = open(sys.argv[1], "rb").read()
with open(sys.argv[2], "w") as f:
    f.write("// generated from olfx_ctx.dex by build.sh\nstatic const unsigned char olfx_ctx_dex[] = {")
    f.write(",".join(str(b) for b in d))
    f.write(f"}};\nstatic const unsigned long olfx_ctx_dex_len = {len(d)};\n")
PY
out="$here/../../olfx/src/main/assets/ovr/libovrplatformloader_guard_arm64.so"
"$bin/aarch64-linux-android26-clang" -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter \
	-Wl,-z,max-page-size=16384 -Wl,--no-undefined -Wl,-soname,libovrplatformloader.so \
	-I"$gen" -o "$out" "$here/guard.c" "$gen/fwd_stubs.S" -llog -ldl
echo "wrote $out ($(wc -c < "$out") bytes)"

"$bin/aarch64-linux-android26-clang" -shared -fPIC -O2 -Wl,-z,max-page-size=16384 \
    -Wl,-soname,libfrda.so -o "$here/../../olfx/src/main/assets/ovr/liboptional_hook_arm64.so" "$here/optional_hook.c"
