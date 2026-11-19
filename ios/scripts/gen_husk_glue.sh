#!/bin/bash
# Derives what OLFX needs from Husk's sources: its App file without @main, and an Info.plist that keeps
# Husk's capabilities (URL scheme, document types, background modes, ...) under the OLFX name.
set -euo pipefail
IOS="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$IOS/third_party/Husk/src/app"
OUT="$IOS/Generated"
mkdir -p "$OUT"
sed 's/^@main$//' "$SRC/Husk/HuskApp.swift" > "$OUT/HuskApp.swift"
cp "$SRC/Husk/Info.plist" "$OUT/Info.plist"
P=/usr/libexec/PlistBuddy
$P -c "Set :CFBundleDisplayName OLFX" "$OUT/Info.plist"
$P -c "Add :NSCameraUsageDescription string OLFX uses the camera for hand tracking and 6DoF head tracking." "$OUT/Info.plist" 2>/dev/null || true
$P -c "Add :OLFXVersion string 1.7.0" "$OUT/Info.plist" 2>/dev/null || $P -c "Set :OLFXVersion 1.7.0" "$OUT/Info.plist"
cp "$SRC/Husk/Husk.entitlements" "$OUT/OLFX.entitlements"
