#!/bin/bash
# Fetches Husk (pinned) and builds its native parts (QEMU, ANGLE, MoltenVK, guest, ...).
# Husk is then compiled INTO the OLFX app by project.yml, so there is no separate Husk install.
# Needs a Mac with Xcode, meson, ninja, pkg-config, python3, xcodegen, qemu-img, Rust (aarch64-apple-ios).
set -euo pipefail
IOS="$(cd "$(dirname "$0")/.." && pwd)"
HUSK="$IOS/third_party/Husk"
PIN=afe83d45b31194c4cc9f7b3db7ec2c757db8346e   # bump deliberately

if [ ! -d "$HUSK/.git" ]; then
  mkdir -p "$IOS/third_party"
  git clone https://github.com/Leviidev/Husk.git "$HUSK"
fi
git -C "$HUSK" fetch --quiet origin "$PIN" 2>/dev/null || true
git -C "$HUSK" checkout --quiet "$PIN"
# OLFX patches (Husk answers dlsym on a game's OpenXR loader with our runtime): applied once.
for P in "$IOS"/patches/*.patch; do
  if git -C "$HUSK" apply --check "$P" 2>/dev/null; then git -C "$HUSK" apply "$P"; fi
done

cd "$HUSK"
step() { printf '\n\033[1;34m##### %s\033[0m\n' "$*"; }

# Same steps as Husk's scripts/ci_build.sh, minus the final "app + IPA" (OLFX packages itself).
step "fetch sources";  ./scripts/fetch_sources.sh
step "deps";           ./scripts/build_ios.sh libffi glib pixman libucontext libslirp
cp build/ios-arm64/cross-darwin.meson build/ios-arm64/cross-ios-darwin.meson
[ -f build/ios-arm64/sysroot/lib/libANGLE-shared.dylib ] || ./scripts/build_angle_ios.sh
[ -f build/ios-arm64/sysroot/lib/libvirglrenderer.a ] || \
  PKG_CONFIG_LIBDIR="$HUSK/build/ios-arm64/sysroot/lib/pkgconfig:$HUSK/build/ios-arm64/sysroot/share/pkgconfig" ./scripts/build_gpu_ios.sh
step "QEMU";           ./scripts/build_ios.sh qemu
./scripts/integrate_husk.sh; ./scripts/build_ios.sh qemurebuild
step "guest";          ./scripts/fetch_phase0_guest.sh
[ -d build/ios-arm64/lib/MoltenVK.xcframework ] || ./scripts/build_moltenvk_ios.sh
step "pairing";        ./scripts/build_rppairing_ios.sh

# Staged dylib must be the freshly built one (see Husk's package_ipa.sh).
BUILT="third_party/build/qemu-10.0.12-utm/_husk_build/libqemu-aarch64-softmmu.dylib"
[ -f "$BUILT" ] && cp "$BUILT" build/ios-arm64/lib/

step "generate OLFX glue + project"
"$IOS/scripts/gen_husk_glue.sh"
cd "$IOS" && xcodegen generate
