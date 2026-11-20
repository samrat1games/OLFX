#!/bin/sh
# Fetch the Meta/Oculus Mobile SDK VrApi 1.9.0 headers + libvrapi.so (via the ovr-mobile-sys crate) into build/vrapi.
# These are Meta SDK files under their SDK license; they are not committed to this repository.
set -e
OUT="${1:-build/vrapi}"
mkdir -p "$OUT" && cd "$OUT"
curl -fsSL -o c.crate https://static.crates.io/crates/ovr-mobile-sys/ovr-mobile-sys-0.4.0.crate
tar xzf c.crate --strip-components=2 ovr-mobile-sys-0.4.0/VrApi
rm c.crate
ls Include/VrApi.h Libs/Android/armeabi-v7a/Release/libvrapi.so Libs/Android/arm64-v8a/Release/libvrapi.so
